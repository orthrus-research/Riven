"""Small owned launcher using the pinned CC loader/library resolver; no benchmark mod."""

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
import tomllib
import zipfile
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "dev/CubicChunks/tools"))
import cubic_lab as lab
import cubic_standalone as standalone
import lab_instance
import lab_profiles


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def target(name):
    if not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for c in name):
        raise ValueError("Use a lowercase instance name")
    return ROOT / ".local/cubic-standalone" / name


def build():
    settings = load_json(ROOT / ".local/settings.json")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(Path(settings["java25"]).parent.parent)
    subprocess.run(
        [str(ROOT / "gradlew.bat"), "assemble", "--console=plain"], cwd=ROOT, env=env, check=True
    )


def native_runtime():
    """This machine's runtime identity: the pinned Cleanroom release (dev/native-runtime.json)
    plus the Java 26 named in .local/settings.json, with the hashes of its critical files."""
    template = load_json(ROOT / "dev/native-runtime.json")
    java = Path(load_json(ROOT / ".local/settings.json")["java26"]).resolve()
    identity = lab_instance.inspect_java26(java)
    reviewed = template["javaRuntime"]["version"]
    if identity["version"] != reviewed:
        print(f"Note: {identity['version']} differs from the reviewed {reviewed}", flush=True)
    path = ROOT / ".local/native-runtime.json"
    lab.write_json(path, {**template, "javaRuntime": identity, "java": str(java)})
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


def shader_packs(pins, entries):
    """prepare's --shader-pack entries as (path, record): a pinned pack's name (shaderPacks in
    runtime.lock.json), held to its hash, or a zip or folder such as a /riven shaderprops copy."""
    pinned = {pack["name"]: pack for pack in pins.get("shaderPacks", [])}
    packs = []
    for entry in entries or []:
        if entry in pinned:
            pack = pinned[entry]
            path = ROOT / pack["file"]
            if not path.is_file():
                raise RuntimeError(f"Pinned shader pack missing: {pack['file']} ({pack['source']})")
            if lab.sha(path) != pack["sha256"]:
                raise RuntimeError("Pinned shader pack changed: " + pack["file"])
            record = {"name": entry, "version": pack["version"], "sha256": pack["sha256"]}
        else:
            path = Path(entry).resolve()
            if not path.exists():
                raise RuntimeError(f"Not a pinned pack ({', '.join(pinned)}) or a path: {entry}")
            record = {"path": str(path), "sha256": lab.sha(path) if path.is_file() else None}
        packs.append((path, {**record, "installedAs": path.name}))
    return packs


def enable_shader_pack(home, pack):
    """Demonica reads its active pack from config/shaders.properties; None turns shaders off."""
    (home / "config/shaders.properties").write_text(
        f"enableShaders=true\nshaderPack={pack}\n" if pack else "enableShaders=false\n",
        encoding="utf-8",
    )


def prepare(
    name,
    port,
    selected_artifact=None,
    start=None,
    capture=False,
    cubic_artifact=None,
    spectator=False,
    distant_generation=True,
    capture_ticks=400,
    dh_bandwidth_kbps=2000,
    dh_request_limit=40,
    dh_workers=8,
    dh_pause_when_moving=False,
    layout_scale=1.0,
    dh_ignore_server_health=False,
    dh_adaptive_transfer=False,
    dh_progress_log=False,
    dh_native_feed="all",
    cubature_delivery="prefetch",
    seed=8675309,
    spawn_animals=False,
    shaders=False,
    shader_pack=None,
    player_effects=None,
    complementary_cave_fog=False,
):
    if not 1 <= dh_bandwidth_kbps <= 1000000 or not 1 <= dh_request_limit <= 100:
        raise ValueError("DH bandwidth must be 1..1000000 KB/s and request limit 1..100")
    if not 1 <= dh_workers <= 16:
        raise ValueError("DH workers must be 1..16 per JVM")
    if cubature_delivery not in ("baseline", "maintenance", "save", "prefetch"):
        raise ValueError("Cubature delivery must be baseline, maintenance, save or prefetch")
    if dh_native_feed not in ("all", "edits"):
        raise ValueError("DH native feed must be all or edits")
    if not 0.5 <= layout_scale <= 8:
        raise ValueError("Riven layout scale must be 0.5..8")
    pins = load_json(ROOT / "runtime.lock.json")
    if pins["profile"] != "cleanroom":
        raise RuntimeError("Expected the Cleanroom runtime profile")
    for mod in pins["mods"]:
        if lab.sha(ROOT / mod["file"]) != mod["sha256"]:
            raise RuntimeError("Pinned mod changed: " + mod["file"])
    packs = shader_packs(pins, shader_pack)
    if packs and not shaders:
        raise ValueError("--shader-pack needs --shaders")
    # The patch only matters under Demonica; local instances turn it on, nothing distributed does.
    complementary_cave_fog = complementary_cave_fog and shaders
    artifact = (
        Path(selected_artifact).resolve()
        if selected_artifact
        else ROOT / "build/libs/riven-0.0.1-cleanroom.jar"
    )
    if not artifact.is_file():
        raise RuntimeError("Build Riven first")
    cubic = Path(cubic_artifact).resolve() if cubic_artifact else ROOT / pins["mods"][0]["file"]
    distant_mod = next(
        mod for mod in pins["mods"] if mod["file"].startswith("dev/pins/DistantHorizons-")
    )
    if distant_mod.get("distribution") != "unmodified-upstream":
        raise RuntimeError("The runtime requires a pinned official upstream DH artifact")
    distant_version = distant_mod["version"]
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
        if cubic.name != "cubicchunks.jar":
            (home / "mods/cubicchunks.jar").rename(home / "mods" / cubic.name)
        for mod in pins["mods"][1:]:
            # Optional pins (the shader stack) join only when prepare asks for them.
            if mod.get("optional") == "shaders" and not shaders:
                continue
            if (
                side == "server"
                and not distant_generation
                and mod["file"].startswith("dev/pins/DistantHorizons-")
            ):
                continue
            if side in mod.get("sides", ["server", "client"]) or (
                distant_generation and mod["file"].startswith("dev/pins/DistantHorizons-")
            ):
                selected = ROOT / mod["file"]
                shutil.copy2(selected, home / "mods" / selected.name)
        shutil.copy2(artifact, home / "mods/riven.jar")
        if side == "client" and packs:
            (home / "shaderpacks").mkdir(exist_ok=True)
            for pack, _ in packs:
                if pack.is_dir():
                    shutil.copytree(pack, home / "shaderpacks" / pack.name)
                else:
                    shutil.copy2(pack, home / "shaderpacks" / pack.name)
            # The first pack is active; play --shader-pack switches between the others.
            enable_shader_pack(home, packs[0][0].name)
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
                # Only a non-default feed is written; older Cubature does not know the key.
                + ("\n    S:distantHorizonsNativeFeed=EDITS" if dh_native_feed == "edits" else "")
                + (
                    "\n    B:complementaryCaveFog=true"
                    if complementary_cave_fog and side == "client"
                    else ""
                )
                + "\n    S:deliveryMode="
                + cubature_delivery.upper(),
                1,
            )
        )
        mods[side] = {p.name: lab.sha(p) for p in (home / "mods").glob("*.jar")}
        # Both recommended compatibility mods are present; retain the normal loader check.
        (home / "config/forge_early.cfg").write_text(
            "general {\n    B:DISABLE_PATCH_MOD_CHECK=false\n}\n"
        )
        if side == "client" or distant_generation:
            # The optional CC adapter supplies seed-only SURFACE data on DH's executor.
            # The legacy visited-only lane remains available for comparison.
            (home / "config/DistantHorizons.toml").write_text(
                '[common.worldGenerator]\ngeneratorPlan = "'
                + ("CHUNKS_ONLY" if distant_generation else "DISABLED")
                + '"\n'
                "pauseIfMovingQuickly = "
                + str(dh_pause_when_moving).lower()
                + "\nlimitIfServerUnhealthy = "
                + str(not dh_ignore_server_health).lower()
                + "\n"
                + ('showGenerationProgress = "LOG"\n' if dh_progress_log else "")
                + "[client.advanced.autoUpdater]\nenableAutoUpdater = false\nenableSilentUpdates = false\n"
                "[common.multiThreading]\nnumberOfThreads = " + str(dh_workers) + "\n"
                "[client.advanced.graphics.quality]\nlodChunkRenderDistanceRadius = "
                + str(256 if distant_generation else 64)
                + "\n"
                "[client.advanced.graphics.genericRendering]\nenableCloudRendering = false\n"
                '[client.advanced.graphics.fog]\nfarFogStart = "0.75"\nfarFogEnd = "1.0"\nfarFogFalloff = "LINEAR"\n'
                # DH negotiates the lower client/server limits, so set both sides.
                + (
                    "[server]\nmaxGenerationRequestDistance = 256\n"
                    "generationRequestRateLimit = " + str(dh_request_limit) + "\n"
                    "playerBandwidthLimit = " + str(dh_bandwidth_kbps) + "\n"
                    "enableAdaptiveTransferSpeed = " + str(dh_adaptive_transfer).lower() + "\n"
                    if distant_generation
                    else ""
                )
            )
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
        reference_bytes = jar.read("riven-terrain-height-reference.json")
        climate_bytes = (
            jar.read("riven-climate.json") if "riven-climate.json" in jar.namelist() else None
        )
        # Riven's classes moved from supersymmetry/riven to cc/orthrus/riven on 2026-10-28.
        names = set(jar.namelist())
        worldgen = "cc/orthrus/riven/worldgen/"
        if not any(n.startswith(worldgen) for n in names):
            worldgen = "supersymmetry/riven/worldgen/"
        has_climate = worldgen + "ClimateMap.class" in names
        has_outline = worldgen + "ContinentalOutlineMap.class" in names
        has_climate_structure = worldgen + "ClimateStructureMap.class" in names
        forcing_bytes = (
            jar.read("riven-climate-forcing.json")
            if "riven-climate-forcing.json" in jar.namelist()
            else None
        )
        pass_bytes = (
            jar.read("riven-terrain-passes.json")
            if "riven-terrain-passes.json" in jar.namelist()
            else None
        )
        landmark_bytes = (
            jar.read("riven-landmarks.json") if "riven-landmarks.json" in jar.namelist() else None
        )
        carver_bytes = (
            jar.read("riven-surface-carvers.json")
            if "riven-surface-carvers.json" in jar.namelist()
            else None
        )
        surface_bytes = (
            jar.read("riven-surface-rules.json")
            if "riven-surface-rules.json" in jar.namelist()
            else None
        )
        continent_bytes = (
            jar.read("riven-continents.json") if "riven-continents.json" in jar.namelist() else None
        )
        terrain_bytes = (
            jar.read("riven-terrain.json") if "riven-terrain.json" in jar.namelist() else None
        )
        habitat_bytes = (
            jar.read("riven-habitats.json") if "riven-habitats.json" in jar.namelist() else None
        )
        hydro_bytes = (
            jar.read("riven-hydrology.json") if "riven-hydrology.json" in jar.namelist() else None
        )
        hydro_layers = hydro_bytes is not None and "layers" in json.loads(hydro_bytes)
        shore_bytes = (
            jar.read("riven-shores.json") if "riven-shores.json" in jar.namelist() else None
        )
        biome_bytes = (
            jar.read("riven-biomes.json") if "riven-biomes.json" in jar.namelist() else None
        )
        ocean_bytes = (
            jar.read("riven-ocean-floors.json")
            if "riven-ocean-floors.json" in jar.namelist()
            else None
        )
        lake_bytes = jar.read("riven-lakes.json") if "riven-lakes.json" in jar.namelist() else None
        river_bytes = (
            jar.read("riven-rivers.json") if "riven-rivers.json" in jar.namelist() else None
        )
        estuary_bytes = (
            jar.read("riven-estuaries.json") if "riven-estuaries.json" in jar.namelist() else None
        )
        sediment_bytes = (
            jar.read("riven-sedimentary-coasts.json")
            if "riven-sedimentary-coasts.json" in jar.namelist()
            else None
        )
        rocky_bytes = (
            jar.read("riven-rocky-coasts.json")
            if "riven-rocky-coasts.json" in jar.namelist()
            else None
        )
        lowland_bytes = (
            jar.read("riven-lowlands.json") if "riven-lowlands.json" in jar.namelist() else None
        )
        hill_bytes = jar.read("riven-hills.json") if "riven-hills.json" in jar.namelist() else None
        mountain_bytes = (
            jar.read("riven-mountains.json") if "riven-mountains.json" in jar.namelist() else None
        )
        plateau_bytes = (
            jar.read("riven-plateaus.json") if "riven-plateaus.json" in jar.namelist() else None
        )
        dryland_bytes = (
            jar.read("riven-drylands.json") if "riven-drylands.json" in jar.namelist() else None
        )
        substrate_bytes = (
            jar.read("riven-substrate.json") if "riven-substrate.json" in jar.namelist() else None
        )
        base_terrain_bytes = (
            jar.read("riven-base-terrain.json")
            if "riven-base-terrain.json" in jar.namelist()
            else None
        )
        region_bytes = (
            jar.read("riven-terrain-regions.json")
            if "riven-terrain-regions.json" in jar.namelist()
            else None
        )
        elevation_bytes = (
            jar.read("riven-base-elevation.json")
            if "riven-base-elevation.json" in jar.namelist()
            else None
        )
        fabric_bytes = (
            jar.read("riven-regional-fabric.json")
            if "riven-regional-fabric.json" in jar.namelist()
            else None
        )
        cold_bytes = (
            jar.read("riven-cold-regions.json")
            if "riven-cold-regions.json" in jar.namelist()
            else None
        )
        volcanic_bytes = (
            jar.read("riven-volcanic.json") if "riven-volcanic.json" in jar.namelist() else None
        )
        general_cut_bytes = (
            jar.read("riven-general-surface-cuts.json")
            if "riven-general-surface-cuts.json" in jar.namelist()
            else None
        )
    height_reference = json.loads(reference_bytes)
    manifest.update(
        purpose="Riven climate and terrain inspection"
        if has_climate
        else "Riven base terrain and directional erosion inspection",
        modHashes=mods,
        runtimeProfile=pins["profile"],
        generator={
            "worldType": "riven",
            "layoutScale": layout_scale,
            "heightReference": height_reference["revision"],
            "heightReferenceSha256": hashlib.sha256(reference_bytes).hexdigest(),
            "minimumY": height_reference["allocation"]["worldMinY"],
            "crustFloorY": height_reference["allocation"]["minY"],
            "maximumYExclusive": height_reference["allocation"]["maxYExclusive"],
            "maps": ["temperature-v1"]
            + (["climate-v1"] if has_climate else [])
            + [
                "plates-v2",
                "terrain-v10"
                if shore_bytes
                else "terrain-v9"
                if hydro_layers
                else "terrain-v8"
                if hydro_bytes
                else "terrain-v7"
                if terrain_bytes
                else "terrain-v6"
                if continent_bytes
                else "terrain-v5"
                if carver_bytes
                else "terrain-v4"
                if landmark_bytes
                else "terrain-v3"
                if pass_bytes
                else "terrain-v2",
                "erosion-v1",
            ]
            + (["regional-passes-v1"] if pass_bytes else [])
            + ([("landmarks-v2" if terrain_bytes else "landmarks-v1")] if landmark_bytes else [])
            + (
                [("surface-carvers-v2" if terrain_bytes else "surface-carvers-v1")]
                if carver_bytes
                else []
            )
            + (["surface-rules-v1"] if surface_bytes else [])
            + (["continents-v2" if shore_bytes else "continents-v1"] if continent_bytes else [])
            + (
                ["terrain-types-v2", "habitats-v4"]
                if shore_bytes
                else ["terrain-types-v1", "habitats-v1"]
                if terrain_bytes
                else []
            )
            + (["shores-v1"] if shore_bytes else [])
            + (
                [
                    "hydro-v3" if shore_bytes else "hydro-v2" if hydro_layers else "hydro-v1",
                    "groundwater-potential-v1",
                ]
                if hydro_bytes
                else []
            ),
            "oceanFloorsSha256": hashlib.sha256(ocean_bytes).hexdigest() if ocean_bytes else None,
            "lakesSha256": hashlib.sha256(lake_bytes).hexdigest() if lake_bytes else None,
            "riversSha256": hashlib.sha256(river_bytes).hexdigest() if river_bytes else None,
            "estuariesSha256": hashlib.sha256(estuary_bytes).hexdigest() if estuary_bytes else None,
            "sedimentaryCoastsSha256": hashlib.sha256(sediment_bytes).hexdigest()
            if sediment_bytes
            else None,
            "rockyCoastsSha256": hashlib.sha256(rocky_bytes).hexdigest() if rocky_bytes else None,
            "lowlandsSha256": hashlib.sha256(lowland_bytes).hexdigest() if lowland_bytes else None,
            "hillsSha256": hashlib.sha256(hill_bytes).hexdigest() if hill_bytes else None,
            "skewHillHeadingPolicy": json.loads(hill_bytes).get("skewHeadingPolicy", "LEGACY")
            if hill_bytes
            else None,
            "regionalFabricSha256": hashlib.sha256(fabric_bytes).hexdigest()
            if fabric_bytes
            else None,
            "mountainsSha256": hashlib.sha256(mountain_bytes).hexdigest()
            if mountain_bytes
            else None,
            "plateausSha256": hashlib.sha256(plateau_bytes).hexdigest() if plateau_bytes else None,
            "drylandsSha256": hashlib.sha256(dryland_bytes).hexdigest() if dryland_bytes else None,
            "substrateSha256": hashlib.sha256(substrate_bytes).hexdigest()
            if substrate_bytes
            else None,
            "baseTerrainSha256": hashlib.sha256(base_terrain_bytes).hexdigest()
            if base_terrain_bytes
            else None,
            "baseTerrainPolicy": json.loads(base_terrain_bytes)["policy"]
            if base_terrain_bytes
            else None,
            "terrainRegionsSha256": hashlib.sha256(region_bytes).hexdigest()
            if region_bytes
            else None,
            "baseElevationSha256": hashlib.sha256(elevation_bytes).hexdigest()
            if elevation_bytes
            else None,
            "climateForcingSha256": hashlib.sha256(forcing_bytes).hexdigest()
            if forcing_bytes
            else None,
            "climateForcingPolicy": json.loads(forcing_bytes)["policy"]
            if forcing_bytes
            else "LEGACY",
            "coldRegionsSha256": hashlib.sha256(cold_bytes).hexdigest() if cold_bytes else None,
            "volcanicSha256": hashlib.sha256(volcanic_bytes).hexdigest()
            if volcanic_bytes
            else None,
            "generalSurfaceCutsSha256": hashlib.sha256(general_cut_bytes).hexdigest()
            if general_cut_bytes
            else None,
            "hydrologySha256": hashlib.sha256(hydro_bytes).hexdigest() if hydro_bytes else None,
            "shoresSha256": hashlib.sha256(shore_bytes).hexdigest() if shore_bytes else None,
            "terrainTypesSha256": hashlib.sha256(terrain_bytes).hexdigest()
            if terrain_bytes
            else None,
            "habitatsSha256": hashlib.sha256(habitat_bytes).hexdigest() if habitat_bytes else None,
            "terrainPassesSha256": hashlib.sha256(pass_bytes).hexdigest() if pass_bytes else None,
            "landmarksSha256": hashlib.sha256(landmark_bytes).hexdigest()
            if landmark_bytes
            else None,
            "surfaceCarversSha256": hashlib.sha256(carver_bytes).hexdigest()
            if carver_bytes
            else None,
            "surfaceRulesSha256": hashlib.sha256(surface_bytes).hexdigest()
            if surface_bytes
            else None,
            "continentsSha256": hashlib.sha256(continent_bytes).hexdigest()
            if continent_bytes
            else None,
            "engineSeaLevelY": height_reference["datum"].get("engineSeaLevelY"),
            "highestOceanWaterBlockY": height_reference["datum"].get("highestOceanWaterBlockY"),
            "surfaceCarverRevision": json.loads(carver_bytes)["revision"] if carver_bytes else None,
            "terrainHorizontalScale": 0.5,
            "climateHorizontalScale": 1.0,
            "biomeSelection": "water-over-shore-over-terrain-qualified-climate-palette"
            if shore_bytes
            else "water-over-terrain-qualified-climate-palette"
            if hydro_bytes
            else "terrain-qualified-climate-palette"
            if habitat_bytes
            else "marine-over-temperature-moisture-region-palette"
            if continent_bytes
            else "temperature-moisture-region-palette"
            if has_climate
            else "temperature-only",
        },
        inspectionStart=list(start) if start else None,
        captureInitialView=capture,
        inspectionSpectator=spectator,
        captureTicks=max(400, capture_ticks),
        cubatureDelivery=cubature_delivery.upper(),
        distantHorizons={
            "version": distant_version,
            "side": "both" if distant_generation else "client",
            "generatorPlan": "CHUNKS_ONLY" if distant_generation else "DISABLED",
            "distribution": distant_mod["distribution"],
            "effectiveCubicPlan": "SURFACE_ONLY" if distant_generation else "DISABLED",
            "workers": dh_workers,
            "pauseIfMovingQuickly": dh_pause_when_moving,
            "limitIfServerUnhealthy": not dh_ignore_server_health,
            **({"showGenerationProgress": "LOG"} if dh_progress_log else {}),
            "lodRadiusChunks": 256 if distant_generation else 64,
            "deliveryBandwidthKBps": dh_bandwidth_kbps if distant_generation else None,
            "generationRequestRateLimit": dh_request_limit if distant_generation else None,
            "adaptiveTransferSpeed": dh_adaptive_transfer if distant_generation else None,
            **({"cubatureNativeFeed": "EDITS"} if dh_native_feed == "edits" else {}),
            "qualification": "Explicit candidate CC; see native receipt"
            if cubic_artifact
            else "Pinned unmodified upstream DH; see dev/dh.lock.json and the instance native receipt",
        },
        sourceArtifact={"path": str(artifact), "sha256": lab.sha(artifact)},
        sourceCubicArtifact={
            "path": str(cubic),
            "sha256": lab.sha(cubic),
            "candidate": cubic_artifact is not None,
        },
        sourceDhArtifact={
            "path": str(ROOT / distant_mod["file"]),
            "sha256": distant_mod["sha256"],
            "candidate": False,
        },
        shaderPacks=[record for _, record in packs],
        complementaryCaveFog=complementary_cave_fog,
    )
    if ocean_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v11" if item == "terrain-v10" else item
            for item in manifest["generator"]["maps"]
        ] + ["ocean-floors-v1"]
    if lake_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v12" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["lakes-v1"]
    if lowland_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v13" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["lowlands-v1"]
    if hill_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v14" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["hills-v1"]
    if mountain_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v15" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["mountains-v1"]
    if plateau_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v16"
            if item.startswith("terrain-v")
            else "surface-rules-v2"
            if item == "surface-rules-v1"
            else item
            for item in manifest["generator"]["maps"]
        ] + ["plateaus-v1"]
    if dryland_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v17"
            if item.startswith("terrain-v")
            else "surface-rules-v3"
            if item.startswith("surface-rules-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["drylands-v1"]
    if cold_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v18"
            if item.startswith("terrain-v")
            else "surface-rules-v4"
            if item.startswith("surface-rules-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["cold-regions-v1"]
    if volcanic_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v19"
            if item.startswith("terrain-v")
            else "surface-rules-v5"
            if item.startswith("surface-rules-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["volcanic-v1"]
    if general_cut_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v20"
            if item.startswith("terrain-v")
            else "surface-rules-v6"
            if item.startswith("surface-rules-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["general-surface-cuts-v1"]
    if lake_bytes and json.loads(lake_bytes).get("revision", 1) >= 2:
        manifest["generator"]["maps"] = [
            "terrain-v21"
            if item.startswith("terrain-v")
            else "lakes-v2"
            if item == "lakes-v1"
            else item
            for item in manifest["generator"]["maps"]
        ]
    if river_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v22" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["rivers-v1"]
    if estuary_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v23" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["estuaries-v1"]
    if sediment_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v24"
            if item.startswith("terrain-v")
            else "surface-rules-v7"
            if item.startswith("surface-rules-v")
            else "habitats-v5"
            if item.startswith("habitats-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["sedimentary-coasts-v1"]
    if rocky_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v25"
            if item.startswith("terrain-v")
            else "surface-rules-v8"
            if item.startswith("surface-rules-v")
            else "habitats-v6"
            if item.startswith("habitats-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["rocky-coasts-v1"]
    if continent_bytes and json.loads(continent_bytes).get("revision", 1) >= 3:
        continent_revision = json.loads(continent_bytes)["revision"]
        terrain_version = 29 if continent_revision >= 5 else 28 if continent_revision >= 4 else 26
        manifest["generator"]["maps"] = [
            f"terrain-v{terrain_version}"
            if item.startswith("terrain-v")
            else f"continents-v{continent_revision}"
            if item.startswith("continents-v")
            else "habitats-v7"
            if item.startswith("habitats-v")
            else "hydro-v4"
            if item.startswith("hydro-v")
            else "shores-v2"
            if item.startswith("shores-v")
            else "ocean-floors-v3"
            if item.startswith("ocean-floors-v")
            and ocean_bytes
            and json.loads(ocean_bytes).get("revision", 1) >= 3
            else "ocean-floors-v2"
            if item.startswith("ocean-floors-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["marine-supply-v1"]
    if substrate_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v30"
            if item.startswith("terrain-v")
            else "drylands-v2"
            if item.startswith("drylands-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["substrate-v1"]
    if base_terrain_bytes and region_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v31" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["base-terrain-v1", "terrain-regions-v1"]
    if elevation_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v32"
            if item.startswith("terrain-v")
            else "base-terrain-v2"
            if item == "base-terrain-v1"
            else item
            for item in manifest["generator"]["maps"]
        ] + ["base-elevation-v1"]
    if fabric_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v33"
            if item.startswith("terrain-v")
            else "hills-v2"
            if item == "hills-v1"
            else item
            for item in manifest["generator"]["maps"]
        ] + ["regional-fabric-v1"]
    if has_outline:
        manifest["generator"]["maps"] = [
            "terrain-v34" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["continental-outline-v1"]
    if has_climate_structure:
        manifest["generator"]["maps"] = [
            "terrain-v35" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["climate-structure-v1"]
    if forcing_bytes:
        manifest["generator"]["maps"] = [
            "terrain-v36"
            if item.startswith("terrain-v")
            else "climate-v2"
            if item == "climate-v1"
            else item
            for item in manifest["generator"]["maps"]
        ] + ["climate-forcing-v1"]
    if mountain_bytes and json.loads(mountain_bytes).get("revision", 1) >= 2:
        manifest["generator"]["maps"] = [
            "terrain-v37"
            if item.startswith("terrain-v")
            else "mountains-v2"
            if item == "mountains-v1"
            else "volcanic-v2"
            if item == "volcanic-v1"
            else item
            for item in manifest["generator"]["maps"]
        ]
    continent_revision_now = (
        json.loads(continent_bytes).get("revision", 1) if continent_bytes else 1
    )
    if continent_revision_now >= 6:
        manifest["generator"]["maps"] = [
            ("terrain-v39" if continent_revision_now >= 7 else "terrain-v38")
            if item.startswith("terrain-v")
            else item
            for item in manifest["generator"]["maps"]
            if item != "marine-supply-v1"
        ]
    if shore_bytes and json.loads(shore_bytes).get("revision", 1) >= 3:
        manifest["generator"]["maps"] = [
            "terrain-v40"
            if item.startswith("terrain-v")
            else "shores-v3"
            if item.startswith("shores-v")
            else "hills-v3"
            if item.startswith("hills-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if terrain_bytes and json.loads(terrain_bytes).get("revision", 1) >= 11:
        manifest["generator"]["maps"] = [
            "terrain-types-v3" if item.startswith("terrain-types-v") else item
            for item in manifest["generator"]["maps"]
        ]
    if habitat_bytes and json.loads(habitat_bytes).get("revision", 1) >= 8:
        manifest["generator"]["maps"] = [
            (
                "terrain-v42"
                if terrain_bytes and json.loads(terrain_bytes).get("revision", 1) >= 11
                else "terrain-v41"
            )
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else "habitats-v8"
            if item.startswith("habitats-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if continent_revision_now >= 8:
        manifest["generator"]["maps"] = [
            "terrain-v43" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ]
    if habitat_bytes and json.loads(habitat_bytes).get("revision", 1) >= 9:
        manifest["generator"]["maps"] = [
            "terrain-v44"
            if item.startswith("terrain-v")
            else "habitats-v9"
            if item.startswith("habitats-v")
            else item
            for item in manifest["generator"]["maps"]
        ] + ["biomes-v1"]
    if climate_bytes and json.loads(climate_bytes).get("revision", 1) >= 2:
        manifest["generator"]["maps"] = [
            "terrain-v45" if item.startswith("terrain-v") else item
            for item in manifest["generator"]["maps"]
        ] + ["climate-tuning-v2"]
    if hydro_bytes and "meander" in json.loads(hydro_bytes):
        manifest["generator"]["maps"] = [
            "terrain-v46"
            if item.startswith("terrain-v")
            else "hydro-v5"
            if item.startswith("hydro-v")
            else "biomes-v2"
            if item == "biomes-v1"
            else item
            for item in manifest["generator"]["maps"]
        ]
    if continent_revision_now >= 9:
        manifest["generator"]["maps"] = [
            ("terrain-v48" if continent_revision_now >= 10 else "terrain-v47")
            if item.startswith("terrain-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if biome_bytes and json.loads(biome_bytes).get("revision", 1) >= 3:
        manifest["generator"]["maps"] = [
            "terrain-v49"
            if item.startswith("terrain-v")
            else "biomes-v3"
            if item.startswith("biomes-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if shore_bytes and json.loads(shore_bytes).get("revision", 1) >= 4:
        manifest["generator"]["maps"] = [
            "terrain-v50"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else "shores-v4"
            if item.startswith("shores-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if hydro_bytes and json.loads(hydro_bytes).get("revision") == "bounded-water-layers-v6":
        manifest["generator"]["maps"] = [
            "terrain-v51"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else "hydro-v6"
            if item.startswith("hydro-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if mountain_bytes and json.loads(mountain_bytes).get("revision", 1) >= 3:
        manifest["generator"]["maps"] = [
            "terrain-v52"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else "mountains-v3"
            if item.startswith("mountains-v")
            else "volcanic-v3"
            if item.startswith("volcanic-v")
            else "cold-regions-v2"
            if item.startswith("cold-regions-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if forcing_bytes and json.loads(forcing_bytes).get("revision", 1) >= 2:
        manifest["generator"]["maps"] = [
            "terrain-v53"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else "climate-forcing-v2"
            if item.startswith("climate-forcing-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if terrain_bytes and "laneDensity" in json.loads(terrain_bytes).get("sites", {}):
        manifest["generator"]["maps"] = [
            "terrain-v54"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if volcanic_bytes and json.loads(volcanic_bytes).get("revision", 1) >= 4:
        manifest["generator"]["maps"] = [
            "terrain-v55"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else "volcanic-v4"
            if item.startswith("volcanic-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    if terrain_bytes and "certificateLatticeBlocks" in json.loads(terrain_bytes).get("sites", {}):
        manifest["generator"]["maps"] = [
            "terrain-v56"
            if item.startswith("terrain-v") and not item.startswith("terrain-types-v")
            else item
            for item in manifest["generator"]["maps"]
        ]
    lab.write_json(directory / "standalone.json", manifest)
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
        actual = {p.name: lab.sha(p) for p in (Path(m[side]) / "mods").glob("*.jar")}
        if actual != m["modHashes"][side]:
            raise RuntimeError("Prepared mod set changed: " + side)
        feed = m.get("distantHorizons", {}).get("cubatureNativeFeed")
        if feed and re.findall(
            r"^\s*S:distantHorizonsNativeFeed=(.*?)\s*$",
            (Path(m[side]) / "config/cubicchunks.cfg").read_text(),
            re.MULTILINE,
        ) != [feed]:
            raise RuntimeError("Prepared Cubature DH native feed changed: " + side)
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
        dh = m.get("distantHorizons", {})
        if dh and (side == "client" or dh.get("side") == "both"):
            config = tomllib.loads(
                (Path(m[side]) / "config/DistantHorizons.toml").read_text(encoding="utf-8-sig")
            )
            common = config.get("common", {})
            if common.get("multiThreading", {}).get("numberOfThreads") != dh["workers"]:
                raise RuntimeError("Prepared DH worker budget changed: " + side)
            generator = common.get("worldGenerator", {})
            for key in [
                "generatorPlan",
                "pauseIfMovingQuickly",
                "limitIfServerUnhealthy",
                "showGenerationProgress",
            ]:
                if key in dh and generator.get(key) != dh[key]:
                    raise RuntimeError("Prepared DH generation policy changed: " + side + " " + key)
            if "lodRadiusChunks" in dh:
                quality = (
                    config.get("client", {})
                    .get("advanced", {})
                    .get("graphics", {})
                    .get("quality", {})
                )
                if quality.get("lodChunkRenderDistanceRadius") != dh["lodRadiusChunks"]:
                    raise RuntimeError("Prepared DH render radius changed: " + side)
                if (
                    dh.get("side") == "both"
                    and config.get("server", {}).get("maxGenerationRequestDistance")
                    != dh["lodRadiusChunks"]
                ):
                    raise RuntimeError("Prepared DH generation radius changed: " + side)
            if dh.get("deliveryBandwidthKBps") is not None:
                expected = {
                    "playerBandwidthLimit": dh["deliveryBandwidthKBps"],
                    "generationRequestRateLimit": dh["generationRequestRateLimit"],
                    "enableAdaptiveTransferSpeed": dh["adaptiveTransferSpeed"],
                }
                if any(
                    config.get("server", {}).get(key) != value for key, value in expected.items()
                ):
                    raise RuntimeError("Prepared DH delivery settings changed: " + side)
    for relative, digest in m["serverRuntimeHashes"].items():
        if lab.sha(Path(m["server"]) / relative) != digest:
            raise RuntimeError("Server runtime changed: " + relative)
    runtime = m["clientRuntime"]
    for artifact in runtime["libraries"] + [
        {"path": runtime["minecraft"], "sha1": runtime["minecraft_sha1"]}
    ]:
        if lab.sha(Path(artifact["path"]), "sha1") != artifact["sha1"]:
            raise RuntimeError("Client library changed")
    return directory, m


def load_tour(path):
    """Viewpoints for a capture tour: a JSON list of {label, x, y, z, yaw, pitch}."""
    views = json.loads(Path(path).read_text(encoding="utf-8"))
    if not views:
        raise ValueError("A tour needs at least one viewpoint")
    for view in views:
        if not str(view.get("label", "")).replace("-", "").replace("_", "").isalnum():
            raise ValueError("Tour labels are letters, digits, - and _: " + repr(view))
        for key in ["x", "y", "z", "yaw", "pitch"]:
            float(view[key])
    return views


def play(name, tour=None, window=None, shader_pack=None):
    directory, m = load(name)
    views = load_tour(tour) if tour else None
    if shader_pack:
        installed = {
            r.get("name", r["installedAs"]): r["installedAs"] for r in m.get("shaderPacks", [])
        }
        if shader_pack != "off" and shader_pack not in installed:
            raise ValueError(f"Prepared shader packs: {', '.join(installed) or 'none'} (or off)")
        enable_shader_pack(Path(m["client"]), installed.get(shader_pack))
    messages: queue.Queue[str] = queue.Queue()
    client_messages: queue.Queue[str] = queue.Queue()
    with lab.native_measurement_lease(ROOT, "flat-" + name):
        commands = directory / "commands.txt"
        commands.write_text("")
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
        if m.get("dhDetailTrace"):
            client_args[1:1] = ["-Ddistanthorizons.traceDetail=true"]
        server = lab.spawn(
            server_args, Path(m["server"]), directory / "server-console.log", messages
        )
        client = None
        receipt = {"controller": os.getpid(), "server": server.pid, "serverCommand": server_args}
        try:
            lab.write_json(directory / "processes.json", receipt)
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
            lab.write_json(directory / "processes.json", receipt)
            if initial:
                lab.await_line(messages, "joined the game", [server, client], 120)
                username = client_args[client_args.index("--username") + 1]
                if m.get("inspectionSpectator") or views:
                    lab.send(server, "gamemode spectator " + username)
                lab.send(server, "tp " + username + " " + " ".join(str(v) for v in initial))
                lab.write_json(
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
            lab.write_json(directory / "processes.json", receipt)


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
    taken = sorted((s for s in set(shots.iterdir()) - before), key=lambda s: s.name)
    out = directory / "tours" / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True)
    for i, (view, shot) in enumerate(zip(views, taken)):
        shutil.move(str(shot), str(out / f"{i + 1:02d}-{view['label']}.png"))
    lab.write_json(out / "views.json", views)
    print("Tour captures: " + str(out), flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["build", "prepare", "play", "command"])
    p.add_argument("--name", required=True, help="instance name under .local/cubic-standalone")
    p.add_argument("--port", type=int, default=25870)
    p.add_argument("--seed", type=int, default=8675309, help="World seed for prepare")
    p.add_argument(
        "--shaders",
        action="store_true",
        help="prepare: add the optional shader stack (Demonica) to the client",
    )
    p.add_argument(
        "--shader-pack",
        nargs="+",
        help="prepare: pinned shader packs (shaderPacks in runtime.lock.json) or zips/folders to place in "
        "the client's shaderpacks, the first enabled (needs --shaders); play: the prepared pack to enable, or off",
    )
    p.add_argument(
        "--complementary-cave-fog",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="prepare: with --shaders, turn on Cubature's opt-in Complementary cave fog patch on the client "
        "(default: on for local instances only; Cubature ships it off, since Complementary's license grants "
        "no change to its code)",
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
        default=1.0,
        help="Riven layout scale, 0.5-8 (1 = 8 km tuning, 0.5 = 4 km, 4 = 32 km worlds)",
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
        help="Explicit frozen Riven artifact for a fresh diagnostic instance",
    )
    p.add_argument(
        "--cubic-artifact",
        type=Path,
        help="Explicit candidate CC artifact; the accepted pin stays unchanged",
    )
    p.add_argument(
        "--spectator",
        action="store_true",
        help="Start the owned first-join inspection in spectator mode",
    )
    p.add_argument(
        "--distant-generation",
        action="store_true",
        help="Install DH on both sides and request CC surface previews to 256 chunks",
    )
    p.add_argument(
        "--visited-only",
        action="store_false",
        dest="distant_generation",
        help="Legacy client-only capture with background generation disabled",
    )
    p.set_defaults(distant_generation=True)
    p.add_argument(
        "--capture-ticks",
        type=int,
        default=400,
        help="Delay the optional native screenshot for distant generation",
    )
    p.add_argument(
        "--dh-bandwidth-kbps",
        type=int,
        default=2000,
        help="Fresh-instance per-player DH transfer ceiling on both sides (decimal KB/s)",
    )
    p.add_argument(
        "--dh-request-limit",
        type=int,
        default=40,
        help="Fresh-instance DH requests/sec and outstanding request limit on both sides",
    )
    p.add_argument(
        "--dh-workers",
        type=int,
        default=8,
        help="Shared DH worker budget per JVM in a fresh instance (1..16)",
    )
    p.add_argument(
        "--cubature-delivery",
        choices=["baseline", "maintenance", "save", "prefetch"],
        default="prefetch",
        help="Cubature delivery mode (deliveryMode); prefetch adds continuous saving and ahead preparation",
    )
    p.add_argument(
        "--dh-pause-when-moving",
        action="store_true",
        help="Restore DH's movement pause in a fresh instance",
    )
    p.add_argument(
        "--dh-ignore-server-health",
        action="store_true",
        help="Fresh instance with DH's limitIfServerUnhealthy off (experiment)",
    )
    p.add_argument(
        "--dh-adaptive-transfer",
        action="store_true",
        help="Fresh instance with DH's enableAdaptiveTransferSpeed on (experiment)",
    )
    p.add_argument(
        "--dh-progress-log",
        action="store_true",
        help="Fresh instance logging DH generation progress (observation)",
    )
    p.add_argument(
        "--dh-native-feed",
        choices=["all", "edits"],
        default="all",
        help="Cubature: hand DH every loaded column (all) or only edited ones (edits)",
    )
    p.add_argument("--text")
    p.add_argument(
        "--tour",
        help="play: JSON list of {label, x, y, z, yaw, pitch}; capture each, then stop and close",
    )
    p.add_argument("--window", help="play: client window WIDTHxHEIGHT (default 1280x720)")
    a = p.parse_args()
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
            a.distant_generation,
            a.capture_ticks,
            a.dh_bandwidth_kbps,
            a.dh_request_limit,
            a.dh_workers,
            a.dh_pause_when_moving,
            a.scale,
            a.dh_ignore_server_health,
            a.dh_adaptive_transfer,
            a.dh_progress_log,
            a.dh_native_feed,
            a.cubature_delivery,
            seed=a.seed,
            spawn_animals=a.spawn_animals,
            shaders=a.shaders,
            shader_pack=a.shader_pack,
            player_effects=a.player_effects,
            complementary_cave_fog=a.complementary_cave_fog,
        )
    elif a.command == "play":
        if a.shader_pack and len(a.shader_pack) != 1:
            raise ValueError("play takes one --shader-pack")
        play(a.name, a.tour, a.window, a.shader_pack[0] if a.shader_pack else None)
    else:
        if not a.text or "\n" in a.text or "\r" in a.text:
            raise ValueError("One console command required")
        with (target(a.name) / "commands.txt").open("a", encoding="utf-8") as stream:
            stream.write(a.text + "\n")


if __name__ == "__main__":
    main()
