"""Riven's changes to Cubature's standalone world, run by Workbench's lab once per instance template.

lab.build runs this script (``[launch.default] configure``) after the launch's members are placed and its base
(``cubature:standalone``) is written, and only on a machine whose ``[lab] trusted`` names Riven. It makes Riven's
own changes to the base: the world's height range from the height reference packaged in
riven.jar, Riven's level type and layout scale, Cubature's delivery settings, fancy graphics, no splash screen, and
Forge's normal compatibility check.

``configure(instance, launch, params, placed)``: ``instance`` is the template's folder (``server/`` and ``client/``),
``launch`` the launch file's document, ``params`` the launch's params with the request's over them (typed by
project.toml: ``delivery``, ``preview_threads``, ``scale``, ``spawn_animals``, ``capture``), and ``placed`` each
member's placed file by side. What it returns is kept in instance.json under ``configure.record``.
"""

from __future__ import annotations

import hashlib
import json
import re
import zipfile
from pathlib import Path
from typing import Any

DELIVERY_MODES = ("BASELINE", "MAINTENANCE", "SAVE", "PREFETCH")


def _product(launch: dict[str, Any], placed: dict[str, dict[str, Path]]) -> Path:
    products = [m["member"] for m in launch["members"] if m.get("kind") == "product"]
    if len(products) != 1:
        raise RuntimeError("the launch must name its product once")
    return placed[products[0]]["server"]


def _world_height(config: Path, allocation: dict[str, Any]) -> None:
    """Cubature fixes a new world's height range from its config; Riven's allocation decides it."""
    text = config.read_text()
    for key, value in (
        ("defaultMinHeight", allocation["worldMinY"]),
        ("defaultMaxHeight", allocation["maxYExclusive"]),
    ):
        if f"I:{key}=" not in text:
            raise RuntimeError("Cubature config lacks " + key)
        text = re.sub(rf"I:{key}=-?\d+", f"I:{key}={value}", text)
    config.write_text(text)


def configure(
    instance: Path,
    launch: dict[str, Any],
    params: dict[str, Any],
    placed: dict[str, dict[str, Path]],
) -> dict[str, Any]:
    delivery = str(params.get("delivery", "PREFETCH")).upper()
    if delivery not in DELIVERY_MODES:
        raise ValueError("delivery must be one of " + ", ".join(DELIVERY_MODES))
    preview_threads = params.get("preview_threads")
    if preview_threads is not None and not -1 <= int(preview_threads) <= 16:
        raise ValueError("preview_threads must be -1..16")
    scale = float(params.get("scale", 1.0))
    if not 0.5 <= scale <= 8:
        raise ValueError("scale must be 0.5..8")
    spawn_animals = bool(params.get("spawn_animals", False))
    capture = bool(params.get("capture", False))

    with zipfile.ZipFile(_product(launch, placed)) as jar:
        reference = json.loads(jar.read("riven-terrain-height-reference.json"))
        definitions: dict[str, dict[str, Any]] = {}
        for name in sorted(jar.namelist()):
            if re.fullmatch(r"riven-[a-z0-9-]+\.json", name):
                data = jar.read(name)
                definitions[name] = {
                    "revision": json.loads(data).get("revision"),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }

    server, client = instance / "server", instance / "client"
    _world_height(server / "config/cubicchunks.cfg", reference["allocation"])
    for home in (server, client):
        if home is client:
            # Fast graphics make leaves opaque cubes and a pack's waving leaves open holes: show
            # what players see.
            options = home / "options.txt"
            text = options.read_text()
            if "fancyGraphics:false" not in text:
                raise RuntimeError("the client options have no fancyGraphics setting")
            options.write_text(text.replace("fancyGraphics:false", "fancyGraphics:true"))
        config = home / "config/cubicchunks.cfg"
        text = config.read_text()
        if text.count("general {") != 1:
            raise RuntimeError("expected one CubicChunks general config section")
        config.write_text(
            text.replace(
                "general {",
                "general {\n    B:useVanillaChunkWorldGenerators=false"
                + (
                    f"\n    I:previewThreads={preview_threads}"
                    if preview_threads is not None
                    else ""
                )
                + "\n    S:deliveryMode="
                + delivery,
                1,
            )
        )
        # Both recommended compatibility mods are present: keep the loader's normal check.
        (home / "config/forge_early.cfg").write_text(
            "general {\n    B:DISABLE_PATCH_MOD_CHECK=false\n}\n"
        )
        if home is client:
            # Forge's splash screen draws from its own thread and crashes natively with Quadrature
            # loaded.
            (home / "config/splash.properties").write_text("enabled=false\n")
            if capture:
                with (home / "options.txt").open("a") as options_file:
                    options_file.write("overrideWidth:2560\noverrideHeight:1440\n")
    properties = server / "server.properties"
    text = properties.read_text().replace("level-type=DEFAULT", "level-type=riven")
    # The layout scale travels in the generator options, which level.dat keeps per world.
    text = (
        "\n".join(line for line in text.splitlines() if not line.startswith("generator-settings="))
        + "\ngenerator-settings="
        + (f"scale={scale:g}" if scale != 1 else "")
        + "\n"
    )
    text = text.replace("motd=CubicChunks only - local performance", "motd=Riven terrain prototype")
    text += (
        "generate-structures=false\nspawn-monsters=false\n"
        + f"spawn-animals={'true' if spawn_animals else 'false'}\nspawn-npcs=false\n"
    )
    properties.write_text(text)
    allocation, datum = reference["allocation"], reference["datum"]
    return {
        "worldType": "riven",
        "layoutScale": scale,
        "heightReference": reference["revision"],
        "minimumY": allocation["worldMinY"],
        "crustFloorY": allocation["minY"],
        "maximumYExclusive": allocation["maxYExclusive"],
        "engineSeaLevelY": datum.get("engineSeaLevelY"),
        "highestOceanWaterBlockY": datum.get("highestOceanWaterBlockY"),
        "definitions": definitions,
        "cubatureDelivery": delivery,
        "captureInitialView": capture,
    }
