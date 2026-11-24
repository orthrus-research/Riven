"""Small owned launcher using the pinned CC loader/library resolver; no benchmark mod.

An instance is built from a launch file (Workbench's lab.resolve writes it: every mod by SHA-256 from the set
this worktree builds against, Riven itself from the Store by the tree CI built), so every mod an instance loads
is the one build the set names: `wb run lab.resolve folder=<worktree>`, then `prepare --launch <file>`."""

import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from types import SimpleNamespace

import jdks
from checkouts import CUBATURE_TOOLS
from observability.profiles import sha

ROOT = Path(__file__).resolve().parents[1]
# After Riven's own modules, so a Cubature tool of the same name (test_frozen_manifest) never stands in
# for Riven's; the lab's modules are Cubature's alone and are found here.
if str(CUBATURE_TOOLS) not in sys.path:
    sys.path.append(str(CUBATURE_TOOLS))
import cubic_lab as lab
import cubic_standalone as standalone
import lab_files
import lab_instance
import lab_profiles

# Cubature's delivery modes in stage order, as command lines spell them.
DELIVERY_MODES = tuple(mode.lower() for mode in lab_profiles.DELIVERY_MODES)

# prepare's world without a preset: the 8 km tuning scale on the lab seed.
DEFAULT_WORLD = {"seed": 8675309, "scale": 1.0}
# Named worlds for prepare --preset; canonical is the 32 km world terrain is judged in.
WORLD_PRESETS = {"canonical": {"seed": 2485331, "scale": 4.0}}


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def launch_members(path):
    """A launch file (schema 1) and its members, each its Store file re-hashed, with the name it is placed under."""
    path = Path(path).resolve()
    doc = load_json(path)
    if doc.get("schema") != 1:
        raise RuntimeError("Not a launch file (schema 1): " + str(path))
    if (doc.get("runtime") or {}).get("kind") != "cleanroom":
        raise RuntimeError("The launch's runtime is not Cleanroom: " + str(path))
    properties = dict(
        line.split("=", 1)
        for line in (CUBATURE_TOOLS.parent / "lab/runtime.properties").read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    if doc["runtime"].get("version") != properties.get("runtime_version"):
        raise RuntimeError(
            f"The launch names Cleanroom {doc['runtime'].get('version')}, but Cubature's lab installs "
            f"{properties.get('runtime_version')} (lab/runtime.properties)"
        )
    store = Path(doc["store"])
    members = []
    for member in doc["members"]:
        source = store / member["path"]
        if not source.is_file() or sha(source) != member["sha256"]:
            raise RuntimeError(
                f"Launch member {member['member']} is not in the Store as {member['sha256'][:12]}: {source}"
            )
        sides = ["server", "client"] if member["side"] == "both" else [member["side"]]
        members.append(
            {
                **member,
                "source": source,
                "sides": sides,
                "place": member.get("place") or member["file"],
            }
        )
    for kind, test in (
        ("Riven", lambda m: m["kind"] == "product"),
        ("Cubature", lambda m: m["name"] == "cubature"),
    ):
        if len([m for m in members if test(m)]) != 1:
            raise RuntimeError(f"The launch must name {kind} once: {path}")
    return path, doc, members


def launch_properties(doc, side):
    """The launch's JVM arguments for one side as system properties (each -Dname=value)."""
    found = {}
    for argument in (doc.get("jvm") or {}).get(side, []):
        if not argument.startswith("-D") or "=" not in argument:
            raise RuntimeError("A launch's JVM arguments are -Dname=value properties: " + argument)
        name, value = argument[2:].split("=", 1)
        found[name] = value
    return found


def target(name):
    if not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for c in name):
        raise ValueError("Use a lowercase instance name")
    return ROOT / ".local/cubic-standalone" / name


def build():
    env = os.environ.copy()
    java26 = jdks.home(26)  # as tools/build_riven.py: Gradle and the compiler both run on Java 26
    env["JAVA_HOME"] = str(java26)
    subprocess.run(
        [
            str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")),
            "assemble",
            "--console=plain",
            # No daemon left behind: a daemon inherits this process's output handles, so a caller
            # reading them (Workbench's lab.inputs) waits until the daemon idles out, hours later.
            "--no-daemon",
            "--no-watch-fs",
            "-PrivenJavaVersion=26",
            "-D" + jdks.installations_property(),
        ],
        cwd=ROOT,
        env=env,
        check=True,
    )


def native_runtime():
    """This machine's runtime identity: the pinned Cleanroom release (dev/native-runtime.json)
    plus the Java 26 tools/jdks.py finds, with the hashes of its critical files."""
    template = load_json(ROOT / "dev/native-runtime.json")
    java = jdks.java(26).resolve()
    identity = lab_instance.inspect_java26(java)
    reviewed = template["javaRuntime"]["version"]
    if identity["version"] != reviewed:
        print(f"Note: {identity['version']} differs from the reviewed {reviewed}", flush=True)
    path = ROOT / ".local/native-runtime.json"
    lab_files.write_json(path, {**template, "javaRuntime": identity, "java": str(java)})
    return path


def world_height(config):
    """Cubature fixes a new world's height range (and its out-of-world floor) from this config;
    Riven's generation range alone does not, so both come from the packaged height reference."""
    reference = load_json(ROOT / "src/main/resources/riven-terrain-height-reference.json")
    allocation = reference["allocation"]
    text = config.read_text(encoding="utf-8")
    for key, value in [
        # The world floor, below the realms; the crust floor (minY) is a Riven rule only.
        ("defaultMinHeight", allocation["worldMinY"]),
        ("defaultMaxHeight", allocation["maxYExclusive"]),
    ]:
        if f"I:{key}=" not in text:
            raise RuntimeError("Cubature config lacks " + key)
        text = re.sub(rf"I:{key}=-?\d+", f"I:{key}={value}", text)
    config.write_text(text, encoding="utf-8")


def prepare(
    name,
    port,
    selected_artifact=None,
    start=None,
    capture=False,
    cubic_artifact=None,
    spectator=False,
    capture_ticks=400,
    layout_scale=1.0,
    cubature_delivery="prefetch",
    seed=8675309,
    spawn_animals=False,
    player_effects=None,
    preview_threads=None,
    view_profile=None,
    launch=None,
):
    if launch is None:
        raise RuntimeError(
            "prepare takes every mod from a launch file: wb run lab.resolve folder=<this worktree>, then "
            "--launch <worktree>/.local/launch/default.json"
        )
    launch_file, launch_doc, members = launch_members(launch)
    if cubature_delivery not in DELIVERY_MODES:
        raise ValueError("Cubature delivery must be one of " + ", ".join(DELIVERY_MODES))
    if preview_threads is not None and not -1 <= preview_threads <= 16:
        raise ValueError("Cubature preview threads must be -1..16")
    if not 0.5 <= layout_scale <= 8:
        raise ValueError("Riven layout scale must be 0.5..8")
    product = next(m for m in members if m["kind"] == "product")
    cubature = next(m for m in members if m["name"] == "cubature")
    # A frozen candidate Riven or Cubature stands in for the launch's member, recorded with the instance.
    artifact = Path(selected_artifact).resolve() if selected_artifact else product["source"]
    if not artifact.is_file():
        raise RuntimeError("The selected Riven artifact does not exist: " + str(artifact))
    cubic = Path(cubic_artifact).resolve() if cubic_artifact else cubature["source"]
    if not cubic.is_file():
        raise RuntimeError("The selected Cubature artifact does not exist: " + str(cubic))
    stand_ins = {
        name: str(path)
        for name, path in (("riven", selected_artifact), ("cubature", cubic_artifact))
        if path
    }
    standalone.prepare(
        SimpleNamespace(
            workspace=ROOT,
            name=name,
            port=port,
            seed=seed,
            runtime_lab=native_runtime(),
            cubic_jar=cubic,
        )
    )
    directory = target(name)
    manifest = load_json(directory / "standalone.json")
    world_height(Path(manifest["server"]) / "config/cubicchunks.cfg")
    mods = {}
    for side in ["server", "client"]:
        home = Path(manifest[side])
        placed = cubic.name if cubic_artifact else cubature["place"]
        if placed != "cubicchunks.jar":
            (home / "mods/cubicchunks.jar").rename(home / "mods" / placed)
        for member in members:
            if member is product or member is cubature or side not in member["sides"]:
                continue
            shutil.copy2(member["source"], home / "mods" / member["place"])
        shutil.copy2(artifact, home / "mods/riven.jar")
        if side == "client":
            # The lab's fast graphics make leaves opaque cubes, so the faces beneath them are culled
            # and a pack's waving leaves open holes to nothing. Riven instances show what players see.
            settings = home / "options.txt"
            text = settings.read_text()
            if "fancyGraphics:false" not in text:
                raise RuntimeError("Prepared client options have no fancyGraphics setting")
            settings.write_text(text.replace("fancyGraphics:false", "fancyGraphics:true"))
        config = home / "config/cubicchunks.cfg"
        text = config.read_text()
        if text.count("general {") != 1:
            raise RuntimeError("Expected one CubicChunks general config section")
        config.write_text(
            text.replace(
                "general {",
                "general {\n    B:useVanillaChunkWorldGenerators=false"
                + (
                    "\n    I:previewThreads=" + str(preview_threads)
                    if preview_threads is not None
                    else ""
                )
                + "\n    S:deliveryMode="
                + cubature_delivery.upper(),
                1,
            )
        )
        mods[side] = {p.name: sha(p) for p in (home / "mods").glob("*.jar")}
        # Both recommended compatibility mods are present; retain the normal loader check.
        (home / "config/forge_early.cfg").write_text(
            "general {\n    B:DISABLE_PATCH_MOD_CHECK=false\n}\n"
        )
        if side == "client":
            # Forge's splash screen draws from a thread of its own, and with Quadrature loaded it
            # crashes the client natively in LWJGL's SDL3.dll. It only shows loading progress.
            (home / "config/splash.properties").write_text("enabled=false\n")
        if side == "client" and capture:
            with (home / "options.txt").open("a") as options:
                options.write("overrideWidth:2560\noverrideHeight:1440\n")
    if player_effects:
        # Riven writes its shipped defaults only where no copy exists, so this one is read instead.
        effects = Path(manifest["server"]) / "config/riven/player-effects.json"
        effects.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(Path(player_effects).resolve(), effects)
    properties = Path(manifest["server"]) / "server.properties"
    text = properties.read_text().replace("level-type=DEFAULT", "level-type=riven")
    # Riven's layout scale travels in the generator options, which level.dat keeps per world.
    text = (
        "\n".join(line for line in text.splitlines() if not line.startswith("generator-settings="))
        + "\ngenerator-settings="
        + (f"scale={layout_scale:g}" if layout_scale != 1 else "")
        + "\n"
    )
    text = text.replace("motd=CubicChunks only - local performance", "motd=Riven terrain prototype")
    # Animals stay off for terrain captures; --spawn-animals checks Riven's first-animal spawns.
    text += (
        "generate-structures=false\nspawn-monsters=false\n"
        + f"spawn-animals={'true' if spawn_animals else 'false'}\nspawn-npcs=false\n"
    )
    properties.write_text(text)
    with zipfile.ZipFile(artifact) as jar:
        # Every packaged definition as the artifact holds it: its revision and exact bytes.
        definitions = {}
        for name in sorted(jar.namelist()):
            if re.fullmatch(r"riven-[a-z0-9-]+\.json", name):
                data = jar.read(name)
                definitions[name] = {
                    "revision": json.loads(data).get("revision"),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
        reference_bytes = jar.read("riven-terrain-height-reference.json")
        # Riven's classes moved from supersymmetry/riven to cc/orthrus/riven on 2026-10-28.
        names = set(jar.namelist())
        worldgen = "cc/orthrus/riven/worldgen/"
        if not any(n.startswith(worldgen) for n in names):
            worldgen = "supersymmetry/riven/worldgen/"
        has_climate = worldgen + "ClimateMap.class" in names
    height_reference = json.loads(reference_bytes)
    manifest.update(
        purpose="Riven climate and terrain inspection"
        if has_climate
        else "Riven base terrain and directional erosion inspection",
        modHashes=mods,
        runtimeProfile="cleanroom",
        generator={
            "worldType": "riven",
            "layoutScale": layout_scale,
            "heightReference": height_reference["revision"],
            "minimumY": height_reference["allocation"]["worldMinY"],
            "crustFloorY": height_reference["allocation"]["minY"],
            "maximumYExclusive": height_reference["allocation"]["maxYExclusive"],
            "engineSeaLevelY": height_reference["datum"].get("engineSeaLevelY"),
            "highestOceanWaterBlockY": height_reference["datum"].get("highestOceanWaterBlockY"),
            "definitions": definitions,
        },
        inspectionStart=list(start) if start else None,
        captureInitialView=capture,
        inspectionSpectator=spectator,
        captureTicks=max(400, capture_ticks),
        cubatureDelivery=cubature_delivery.upper(),
        launch={
            "path": str(launch_file),
            "sha256": sha(launch_file),
            "project": launch_doc["project"],
            "launch": launch_doc["launch"],
            "commit": launch_doc["commit"],
            "set": launch_doc["set"],
            "members": {m["member"]: m["sha256"] for m in members},
            "standIns": stand_ins,
        },
        sourceArtifact={"path": str(artifact), "sha256": sha(artifact)},
        sourceCubicArtifact={
            "path": str(cubic),
            "sha256": sha(cubic),
            "candidate": cubic_artifact is not None,
        },
        # System properties every play gives the client JVM.
        clientProperties=launch_properties(launch_doc, "client"),
        serverProperties=launch_properties(launch_doc, "server"),
    )
    # A named reduced view profile is chosen here, last, once for the instance's whole life.
    lab_profiles.choose_view_profile(manifest, view_profile)
    lab_files.write_json(directory / "standalone.json", manifest)
    print("Prepared Riven terrain world:", directory, flush=True)


def load(name):
    directory = target(name)
    m = load_json(directory / "standalone.json")
    if m.get("runtimeProfile") != "cleanroom":
        raise RuntimeError(
            "This instance predates the current runtime profile; prepare a fresh instance"
        )
    lab_instance.validate_java26_inputs(m)
    lab_profiles.validate_jvm_inputs(m)
    lab_profiles.validate_view_distance_inputs(m)
    for side in ["server", "client"]:
        actual = {p.name: sha(p) for p in (Path(m[side]) / "mods").glob("*.jar")}
        if actual != m["modHashes"][side]:
            raise RuntimeError("Prepared mod set changed: " + side)
        delivery = m.get("cubatureDelivery")
        if delivery and re.findall(
            r"^\s*S:deliveryMode=(.*?)\s*$",
            (Path(m[side]) / "config/cubicchunks.cfg").read_text(),
            re.MULTILINE,
        ) != [delivery]:
            raise RuntimeError("Prepared Cubature delivery mode changed: " + side)
        if not re.search(
            r"^\s*B:useVanillaChunkWorldGenerators=false\s*$",
            (Path(m[side]) / "config/cubicchunks.cfg").read_text(),
            re.MULTILINE,
        ):
            raise RuntimeError(
                "Riven requires vanilla chunk world generators to remain disabled: " + side
            )
    for relative, digest in m["serverRuntimeHashes"].items():
        if sha(Path(m["server"]) / relative) != digest:
            raise RuntimeError("Server runtime changed: " + relative)
    runtime = m["clientRuntime"]
    for artifact in runtime["libraries"] + [
        {"path": runtime["minecraft"], "sha1": runtime["minecraft_sha1"]}
    ]:
        if sha(Path(artifact["path"]), "sha1") != artifact["sha1"]:
            raise RuntimeError("Client library changed")
    return directory, m


def packaged_definitions(jar):
    """The riven-*.json definitions a Riven artifact packages, by name, as SHA-256 digests."""
    with zipfile.ZipFile(jar) as archive:
        return {
            entry: hashlib.sha256(archive.read(entry)).hexdigest()
            for entry in archive.namelist()
            if re.fullmatch(r"riven-[^/]+\.json", entry)
        }


def adopt_world(name, source):
    """Replaces a freshly prepared instance's empty world with a full copy of another stopped
    instance's world (its region files, level data and Riven's recorded definitions), so a run
    starts on terrain already generated. The source is only read.
    Both Riven artifacts must package the same definitions (every riven-*.json), so the copied
    world's definitions are the ones in use; a code change between them is the run's treatment."""
    directory, manifest = target(name), load_json(target(name) / "standalone.json")
    origin = load_json(target(source) / "standalone.json")
    jars = [Path(m["server"]) / "mods/riven.jar" for m in (origin, manifest)]
    if packaged_definitions(jars[0]) != packaged_definitions(jars[1]):
        raise RuntimeError("The source world was generated with different Riven definitions")
    world, copied = Path(manifest["server"]) / "world", Path(origin["server"]) / "world"
    if not (copied / "level.dat").is_file():
        raise RuntimeError("The source instance has no generated world: " + source)
    if (world / "level.dat").exists() or any(world.glob("region3d/*")):
        raise RuntimeError("The prepared world is not empty")
    if world.exists():
        shutil.rmtree(world)
    shutil.copytree(copied, world, ignore=shutil.ignore_patterns("session.lock"))
    size = sum(p.stat().st_size for p in world.rglob("*") if p.is_file())
    manifest["worldFrom"] = {
        "instance": source,
        "bytes": size,
        "levelSha256": sha(world / "level.dat"),
        "sourceRivenSha256": sha(jars[0]),
        "scope": "A full copy taken before the first launch; the source world is unchanged.",
    }
    lab_files.write_json(directory / "standalone.json", manifest)
    print(f"Copied {size / 1e9:.1f} GB of world from {source}", flush=True)
    return size


TOUR_VIEW_DISTANCE_KEYS = ("horizontal", "vertical", "clientRender")


def read_tour(path):
    """A tour file: a JSON list of views, or {"viewDistance": {...}, "views": [...]}."""
    tour = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(tour, list):
        return tour, {}
    if not isinstance(tour, dict) or not isinstance(tour.get("views"), list):
        raise ValueError("A tour is a list of views or an object with views")
    unknown = set(tour) - {"views", "viewDistance"}
    if unknown:
        raise ValueError("Unknown tour fields: " + ", ".join(sorted(unknown)))
    distances = tour.get("viewDistance", {})
    if not isinstance(distances, dict) or set(distances) - set(TOUR_VIEW_DISTANCE_KEYS):
        raise ValueError("A tour's viewDistance takes " + ", ".join(TOUR_VIEW_DISTANCE_KEYS))
    return tour["views"], distances


def tour_view_distance(path):
    """The view distances a tour asks for, by key; empty for none."""
    return read_tour(path)[1]


def run_view_distance(
    tour_distances=None, horizontal=None, vertical=None, client_render=None, manifest=None
):
    """A run's view distances: an option given to play, else the tour's, else the lab's baseline. An
    instance prepared with a reduced view profile keeps its own, and asking it for any is refused."""
    tour_distances = tour_distances or {}
    name = lab_profiles.view_profile_name(manifest or {})
    if name != lab_profiles.BASELINE_VIEW_PROFILE:
        asked = [v for v in (horizontal, vertical, client_render) if v is not None] + list(
            tour_distances.values()
        )
        if asked:
            raise RuntimeError(
                f"This instance was prepared with the reduced view profile {name}; its view distances are fixed, "
                "so play options and a tour's viewDistance are refused"
            )
        return lab_profiles.reduced_view_profile(name)

    def pick(given, key):
        return given if given is not None else tour_distances.get(key)

    return lab_profiles.view_distance_profile(
        pick(horizontal, "horizontal"),
        pick(vertical, "vertical"),
        pick(client_render, "clientRender"),
    )


def load_tour(path):
    """Viewpoints for a capture tour: {label, x, y, z, yaw, pitch} each (see read_tour)."""
    views = read_tour(path)[0]
    if not views:
        raise ValueError("A tour needs at least one viewpoint")
    for view in views:
        if not str(view.get("label", "")).replace("-", "").replace("_", "").isalnum():
            raise ValueError("Tour labels are letters, digits, - and _: " + repr(view))
        for key in ["x", "y", "z", "yaw", "pitch"]:
            float(view[key])
    return views


def play(name, tour=None, window=None, distances=None):
    directory, m = load(name)
    views = load_tour(tour) if tour else None
    # The lab's baseline unless the run or its tour raises a distance; recorded before the launch.
    distances = distances or {}
    profile = run_view_distance(
        tour_view_distance(tour) if tour else None,
        distances.get("horizontal"),
        distances.get("vertical"),
        distances.get("clientRender"),
        m,
    )
    if lab_profiles.apply_view_distance(m, profile):
        lab_files.write_json(directory / "standalone.json", m)
    lab_profiles.validate_view_distance_inputs(m)
    print(
        "View distance: "
        + lab_profiles.view_distance_label(profile, lab_profiles.view_profile_name(m))
        + " "
        + json.dumps(profile),
        flush=True,
    )
    messages: queue.Queue[str] = queue.Queue()
    client_messages: queue.Queue[str] = queue.Queue()
    with lab.native_measurement_lease(ROOT, "flat-" + name):
        commands = directory / "commands.txt"
        commands.write_text("")
        # A Riven instance carries no lab observer, so the lab's loader flags stay off.
        server_args = [a for a in lab.argv(m, False) if not a.startswith("-Dcubiclab.")]
        client_args = [a for a in lab.argv(m, True) if not a.startswith("-Dcubiclab.")]
        if window:
            # The lab launcher fixes 1280x720; a tour may want larger captures.
            width, height = window.lower().split("x")
            client_args[client_args.index("--width") + 1] = str(int(width))
            client_args[client_args.index("--height") + 1] = str(int(height))
        if m.get("captureInitialView") or views:
            client_args[1:1] = [
                "-Driven.capture=true",
                "-Driven.captureTicks=" + str(m.get("captureTicks", 400)),
            ]
        if views:
            client_args[1:1] = ["-Driven.tour=true"]
        client_args[1:1] = [f"-D{k}={v}" for k, v in m.get("clientProperties", {}).items()]
        server_args[1:1] = [f"-D{k}={v}" for k, v in m.get("serverProperties", {}).items()]
        server = lab.spawn(
            server_args, Path(m["server"]), directory / "server-console.log", messages
        )
        client = None
        receipt = {"controller": os.getpid(), "server": server.pid, "serverCommand": server_args}
        try:
            lab_files.write_json(directory / "processes.json", receipt)
            lab.await_line(messages, "Done (", [server], 600)
            initial = (
                [views[0][k] for k in ["x", "y", "z", "yaw", "pitch"]]
                if views
                else m.get("inspectionStart")
                if not (directory / "inspection-started.json").exists()
                else None
            )
            if initial:
                for command in [
                    "gamerule spawnRadius 0",
                    "gamerule doDaylightCycle false",
                    "time set 6000",
                    "weather clear 1000000",
                    "setworldspawn " + " ".join(str(v) for v in initial[:3]),
                ]:
                    lab.send(server, command)
            client = lab.spawn(
                client_args, Path(m["client"]), directory / "client-console.log", client_messages
            )
            receipt.update(client=client.pid, clientCommand=client_args)
            lab_files.write_json(directory / "processes.json", receipt)
            if initial:
                lab.await_line(messages, "joined the game", [server, client], 120)
                username = client_args[client_args.index("--username") + 1]
                if m.get("inspectionSpectator") or views:
                    lab.send(server, "gamemode spectator " + username)
                lab.send(server, "tp " + username + " " + " ".join(str(v) for v in initial))
                lab_files.write_json(
                    directory / "inspection-started.json",
                    {"positionAndView": initial, "player": username},
                )
            if views:
                tour_views(directory, m, views, server, client, client_messages, username)
                return
            print(
                "Riven terrain world ready; client launched. Close Minecraft to save and stop.",
                flush=True,
            )
            with commands.open(encoding="utf-8") as stream:
                while server.poll() is None and client.poll() is None:
                    for pending in [messages, client_messages]:
                        while not pending.empty():
                            pending.get_nowait()
                    for line in stream:
                        if line.strip():
                            lab.send(server, line.strip())
                    time.sleep(0.5)
        finally:
            if server.poll() is None:
                lab.send(server, "stop")
                try:
                    server.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    print("Save/stop pending; owned server left alive for inspection.", flush=True)
            if views and client and client.poll() is None:
                # A tour owns its client: close it once the server has saved and stopped.
                client.terminate()
                try:
                    client.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    client.kill()
            receipt.update(serverExit=server.poll(), clientExit=client.poll() if client else None)
            lab_files.write_json(directory / "processes.json", receipt)


def descend(server, username, previous, view, step=32, pause=5.0):
    """Moves between viewpoints far apart in height in steps, at the new position, so cubes and
    their lighting load on the way instead of after one long jump. No capture fires on a step:
    the client waits for its full settle time before a shot."""
    dy = float(view["y"]) - float(previous["y"])
    if abs(dy) <= 2 * step:
        return
    y = float(previous["y"])
    while abs(float(view["y"]) - y) > step:
        y += step if dy > 0 else -step
        lab.send(
            server, f"tp {username} {view['x']} {y:g} {view['z']} {view['yaw']} {view['pitch']}"
        )
        time.sleep(pause)


# Seconds a client is given to leave the world before the launcher ends it: past the last of the
# counts its pools make of their threads after they stop (10 s).
LEAVE_SECONDS = 12


def leave_world(server, username, wait=time.sleep):
    """Disconnects the client from the world before the launcher ends it, so what the client writes
    as its world goes (its pools' stop lines, light dumps) is written: ended with the server, it never
    leaves the world."""
    lab.send(server, "kick " + username + " Tour finished")
    wait(LEAVE_SECONDS)


def tour_views(directory, m, views, server, client, client_messages, username):
    """Teleports through the viewpoints, one capture each, then files the shots by label."""
    shots = Path(m["client"]) / "screenshots"
    shots.mkdir(exist_ok=True)
    before = set(shots.iterdir())
    for i, view in enumerate(views):
        # Optional per-view server commands (for example night vision for unlit caves).
        for command in view.get("commands", []):
            lab.send(server, command.replace("{player}", username))
        if i:
            descend(server, username, views[i - 1], view)
            position = " ".join(str(view[k]) for k in ["x", "y", "z", "yaw", "pitch"])
            lab.send(server, "tp " + username + " " + position)
        lab.await_line(client_messages, "Riven view captured", [server, client], 900)
        print(f"Captured view {i + 1}/{len(views)}: {view['label']}", flush=True)
    leave_world(server, username)
    taken = sorted((s for s in set(shots.iterdir()) - before), key=lambda s: s.name)
    out = directory / "tours" / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True)
    for i, (view, shot) in enumerate(zip(views, taken)):
        shutil.move(str(shot), str(out / f"{i + 1:02d}-{view['label']}.png"))
    lab_files.write_json(out / "views.json", views)
    lab_files.write_json(
        out / "view-distance.json",
        {
            "viewDistance": m["viewDistance"],
            "viewProfile": lab_profiles.view_profile_name(m),
            "label": lab_profiles.view_distance_label(
                m["viewDistance"], lab_profiles.view_profile_name(m)
            ),
        },
    )
    print("Tour captures: " + str(out), flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["build", "prepare", "play", "command"])
    p.add_argument(
        "--name", help="prepare, play, command: the instance name under .local/cubic-standalone"
    )
    p.add_argument("--port", type=int, default=25870)
    p.add_argument(
        "--preset",
        choices=sorted(WORLD_PRESETS),
        help="prepare: a named world; canonical is seed 2485331 at scale 4 (32 km)",
    )
    p.add_argument(
        "--seed", type=int, help=f"World seed for prepare (default {DEFAULT_WORLD['seed']})"
    )
    p.add_argument(
        "--player-effects",
        help="prepare: the server's config/riven/player-effects.json (default: Riven writes its shipped one)",
    )
    p.add_argument(
        "--spawn-animals",
        action="store_true",
        help="prepare: allow animals (off by default for terrain captures)",
    )
    p.add_argument(
        "--scale",
        type=float,
        help="Riven layout scale, 0.5-8 (default 1 = 8 km tuning, 0.5 = 4 km, 4 = 32 km worlds)",
    )
    p.add_argument(
        "--start",
        nargs=5,
        type=float,
        metavar=("X", "Y", "Z", "YAW", "PITCH"),
        help="First-join inspection position and view",
    )
    p.add_argument(
        "--capture", action="store_true", help="Save one native view after 400 client ticks"
    )
    p.add_argument(
        "--artifact",
        type=Path,
        help="A frozen Riven artifact standing in for the launch's, recorded with the instance",
    )
    p.add_argument(
        "--cubic-artifact",
        type=Path,
        help="A candidate Cubature artifact standing in for the launch's, recorded with the instance",
    )
    p.add_argument(
        "--spectator",
        action="store_true",
        help="Start the owned first-join inspection in spectator mode",
    )
    p.add_argument(
        "--launch",
        type=Path,
        help="prepare: the launch file every mod comes from (wb run lab.resolve folder=<worktree> writes "
        "<worktree>/.local/launch/default.json)",
    )
    p.add_argument(
        "--capture-ticks",
        type=int,
        default=400,
        help="Client ticks before the optional native screenshot (at least 400)",
    )
    p.add_argument(
        "--cubature-delivery",
        choices=DELIVERY_MODES,
        default="prefetch",
        help="Cubature delivery mode (deliveryMode); prefetch adds continuous saving and ahead preparation",
    )
    p.add_argument(
        "--preview-threads",
        type=int,
        choices=range(-1, 17),
        metavar="-1..16",
        help="Cubature previewThreads, the server's preview pool; unset keeps Cubature's default",
    )
    p.add_argument("--text")
    p.add_argument(
        "--tour",
        help="play: JSON list of {label, x, y, z, yaw, pitch}, or {viewDistance, views}; capture each, "
        "then stop and close",
    )
    p.add_argument(
        "--view-profile",
        choices=sorted(lab_profiles.REDUCED_VIEW_PROFILES),
        help="prepare: a named view profile below the lab's baseline of 12, fixed for the instance; "
        "its plays take no view-distance options",
    )
    p.add_argument(
        "--view-distance",
        type=int,
        help="play: the server's horizontal view distance, raised above the lab's baseline of 12",
    )
    p.add_argument(
        "--vertical-view-distance",
        type=int,
        help="play: the server's and client's vertical view distance, raised above the baseline of 12",
    )
    p.add_argument(
        "--client-render-distance",
        type=int,
        help="play: the client's render distance, raised above the baseline of 12",
    )
    p.add_argument("--window", help="play: client window WIDTHxHEIGHT (default 1280x720)")
    a = p.parse_args()
    if a.command != "build" and not a.name:
        p.error(a.command + " needs --name <instance>")
    world = DEFAULT_WORLD
    if a.preset:
        if a.seed is not None or a.scale is not None:
            p.error(f"--preset {a.preset} fixes the seed and scale")
        world = WORLD_PRESETS[a.preset]
    seed = world["seed"] if a.seed is None else a.seed
    scale = world["scale"] if a.scale is None else a.scale
    if a.command == "build":
        build()
    elif a.command == "prepare":
        prepare(
            a.name,
            a.port,
            a.artifact,
            a.start,
            a.capture,
            a.cubic_artifact,
            a.spectator,
            a.capture_ticks,
            scale,
            a.cubature_delivery,
            seed=seed,
            spawn_animals=a.spawn_animals,
            player_effects=a.player_effects,
            preview_threads=a.preview_threads,
            view_profile=a.view_profile,
            launch=a.launch,
        )
    elif a.command == "play":
        play(
            a.name,
            a.tour,
            a.window,
            {
                "horizontal": a.view_distance,
                "vertical": a.vertical_view_distance,
                "clientRender": a.client_render_distance,
            },
        )
    else:
        if not a.text or "\n" in a.text or "\r" in a.text:
            raise ValueError("One console command required")
        with (target(a.name) / "commands.txt").open("a", encoding="utf-8") as stream:
            stream.write(a.text + "\n")


if __name__ == "__main__":
    main()
