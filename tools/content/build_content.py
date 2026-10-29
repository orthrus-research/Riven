"""Write Riven's content assets (blockstates, models, textures, lang) from riven-content.json.

Usage: build_content.py [--check]. --check writes nothing and fails when the committed assets
differ from what the definitions produce, or when assets/riven holds files they do not produce.
"""

import argparse
import itertools
import json
import struct
import sys
import zlib
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from painters import PAINTERS, Image  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DEFINITIONS = ROOT / "src/main/resources/riven-content.json"
ASSETS = ROOT / "src/main/resources/assets/riven"
DIRECTIONS = ["up", "down"]
THICKNESSES = ["tip_merge", "tip", "frustum", "middle", "base"]


def png(image: Image) -> bytes:
    """A minimal RGBA PNG; byte-identical for identical pixels."""
    height, width = len(image), len(image[0])
    raw = b"".join(b"\x00" + bytes(c for pixel in row for c in pixel) for row in image)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def text(data: Any) -> bytes:
    return (json.dumps(data, indent=2) + "\n").encode("utf-8")


def textures(definitions: dict[str, Any]) -> dict[str, bytes]:
    out = {}
    for name, spec in definitions["textures"].items():
        folder = "items" if name.endswith("_item") else "blocks"
        if "file" in spec:
            # A committed file, copied unchanged.
            out[f"textures/{folder}/{name}.png"] = (
                Path(__file__).parent / spec["file"]
            ).read_bytes()
            continue
        painter = PAINTERS[spec["painter"]]
        axes = spec.get("for", {})
        keys = list(axes)
        for values in itertools.product(*(axes[k] for k in keys)) if keys else [()]:
            arguments = dict(zip(keys, values, strict=True)) | spec.get("args", {})
            out[f"textures/{folder}/{name.format(**arguments)}.png"] = png(painter(**arguments))
    return out


ORE_STATES = 16


def ore_assets(ores: dict[str, Any], blocks: dict[str, Any]) -> dict[str, bytes]:
    """One forge blockstate per mineral (host stone under a tinted overlay) and a shared model.

    Each host state lists its host's face sets, so an ore shows the main or alternate face by
    position as its host does."""
    sides = {"down": "#bottom", "up": "#top"}

    def layer(texture: str | None, tint: bool) -> dict[str, Any]:
        def face(f: str) -> dict[str, Any]:
            spec: dict[str, Any] = {"texture": texture or sides.get(f, "#side"), "cullface": f}
            if tint:
                spec["tintindex"] = 0
            return spec

        return {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {f: face(f) for f in FACES}}

    out = {
        "models/block/ore.json": text(
            {
                "parent": "block/block",
                "textures": {"particle": "#side"},
                "elements": [layer(None, False), layer("#overlay", True)],
            }
        )
    }
    hosts = ores["hosts"]
    if len(hosts) > ORE_STATES:
        raise ValueError("At most 16 ore hosts")
    for mineral, spec in ores["minerals"].items():
        variants = {}
        for i in range(ORE_STATES):
            host = blocks[hosts[min(i, len(hosts) - 1)]]
            variants[f"host={i}"] = [{"textures": face} for face in stone_faces(host)]
        out[f"blockstates/ore_{mineral}.json"] = text(
            {
                "forge_marker": 1,
                "defaults": {
                    "model": "riven:ore",
                    "textures": {"overlay": f"riven:blocks/ore_overlay_{spec['shine']}"},
                },
                "variants": variants,
            }
        )
    return out


FACES = ["down", "up", "north", "south", "west", "east"]


def unstable_assets(unstable: dict[str, Any], blocks: dict[str, Any]) -> dict[str, bytes]:
    """One block per host rock: the host's own faces under the untinted crack overlay."""
    sides = {"down": "#bottom", "up": "#top"}

    def layer(texture: str | None) -> dict[str, Any]:
        return {
            "from": [0, 0, 0],
            "to": [16, 16, 16],
            "faces": {
                f: {"texture": texture or sides.get(f, "#side"), "cullface": f} for f in FACES
            },
        }

    out = {
        "models/block/unstable.json": text(
            {
                "parent": "block/block",
                "textures": {"particle": "#side"},
                "elements": [layer(None), layer("#overlay")],
            }
        )
    }
    for name, host in unstable["hosts"].items():
        id_ = f"unstable_{name}"
        overlay = {"overlay": f"riven:blocks/{unstable['overlay']}"}
        if host["block"].startswith("riven:"):
            # A Riven host's face sets, main or alternate by position as the host shows them.
            sets = stone_faces(blocks[host["block"][6:]])
            for n, faces in enumerate(sets):
                out[f"models/block/{id_}_{n}.json"] = text(
                    {"parent": "riven:block/unstable", "textures": faces | overlay}
                )
            out[f"blockstates/{id_}.json"] = text(
                {"variants": {"normal": [{"model": f"riven:{id_}_{n}"} for n in range(len(sets))]}}
            )
            out[f"models/item/{id_}.json"] = text({"parent": f"riven:block/{id_}_0"})
            continue
        t = host["textures"]
        faces = {k: t.get(k, t.get("all")) for k in ("top", "bottom", "side")}
        out[f"blockstates/{id_}.json"] = text({"variants": {"normal": {"model": f"riven:{id_}"}}})
        out[f"models/block/{id_}.json"] = text(
            {"parent": "riven:block/unstable", "textures": faces | overlay}
        )
        out[f"models/item/{id_}.json"] = text({"parent": f"riven:block/{id_}"})
    return out


def unstable_name(host: dict[str, Any], blocks: dict[str, Any]) -> str:
    if host["block"].startswith("riven:"):
        return f"Unstable {blocks[host['block'][6:]]['name']}"
    return f"Unstable {host['name']}"


def stone_faces(block: dict[str, Any]) -> list[dict[str, str]]:
    """A stone block's face sets (side, top, bottom texture names), main first, then alternates."""
    textures = block["textures"]
    if "faces" not in textures:
        name = f"riven:blocks/{textures['all']}"
        return [{"side": name, "top": name, "bottom": name}]
    sets = []
    for n, face in enumerate(textures["faces"]):
        name = f"riven:blocks/stone/{block['id']}_{n}"
        if "all" in face:
            sets.append({"side": name, "top": name, "bottom": name})
        else:
            sets.append({"side": f"{name}_side", "top": f"{name}_top", "bottom": f"{name}_top"})
    return sets


def stone_assets(block: dict[str, Any]) -> dict[str, bytes]:
    """A stone with Riven Resources faces: each set copied unchanged, one of them by position."""
    id_ = block["id"]
    source = Path(__file__).parent / block["textures"]["source"]
    out: dict[str, bytes] = {}
    for n, face in enumerate(block["textures"]["faces"]):
        for role, file in face.items():
            suffix = "" if role == "all" else f"_{role}"
            out[f"textures/blocks/stone/{id_}_{n}{suffix}.png"] = (
                source / f"{file}.png"
            ).read_bytes()
    for n, face in enumerate(stone_faces(block)):
        out[f"models/block/{id_}_{n}.json"] = text(
            {"parent": "block/cube_column", "textures": {"end": face["top"], "side": face["side"]}}
            if face["top"] != face["side"]
            else {"parent": "block/cube_all", "textures": {"all": face["side"]}}
        )
    count = len(block["textures"]["faces"])
    out[f"blockstates/{id_}.json"] = text(
        {"variants": {"normal": [{"model": f"riven:{id_}_{n}"} for n in range(count)]}}
    )
    out[f"models/item/{id_}.json"] = text({"parent": f"riven:block/{id_}_0"})
    return out


def block_assets(block: dict[str, Any]) -> dict[str, bytes]:
    id_ = block["id"]
    out: dict[str, bytes] = {}
    if block["kind"] == "stone" and "faces" in block["textures"]:
        out.update(stone_assets(block))
    elif block["kind"] == "stone":
        out[f"blockstates/{id_}.json"] = text({"variants": {"normal": {"model": f"riven:{id_}"}}})
        out[f"models/block/{id_}.json"] = text(
            {
                "parent": "block/cube_all",
                "textures": {"all": f"riven:blocks/{block['textures']['all']}"},
            }
        )
        out[f"models/item/{id_}.json"] = text({"parent": f"riven:block/{id_}"})
    elif block["kind"] == "pointed_dripstone":
        variants = {}
        for direction in DIRECTIONS:
            for thickness in THICKNESSES:
                model = f"{id_}_{direction}_{thickness}"
                variants[f"direction={direction},thickness={thickness}"] = {
                    "model": f"riven:{model}"
                }
                out[f"models/block/{model}.json"] = text(
                    {"parent": "block/cross", "textures": {"cross": f"riven:blocks/{model}"}}
                )
        out[f"blockstates/{id_}.json"] = text({"variants": variants})
        out[f"models/item/{id_}.json"] = text(
            {
                "parent": "item/generated",
                "textures": {"layer0": f"riven:items/{block['textures']['item']}"},
            }
        )
    else:
        raise ValueError(f"Unknown kind {block['kind']} for {id_}")
    return out


WOOD_PARTS = ("bark", "end", "leaves-1", "leaves-2", "sapling", "litter")


def wood_assets(woods: dict[str, Any]) -> tuple[dict[str, bytes], dict[str, str]]:
    """Each species' log, leaves, sapling, litter and planks: textures copied unchanged."""
    out: dict[str, bytes] = {}
    lang: dict[str, str] = {}
    here = Path(__file__).parent
    planks_source = here / woods["planksSource"]
    for id_, species in woods["species"].items():
        source = here / species.get("source", woods["source"])
        for part in WOOD_PARTS:
            out[f"textures/blocks/wood/{id_}_{part.replace('-', '_')}.png"] = (
                source / f"canopy-{id_}-{part}.png"
            ).read_bytes()
        out[f"textures/blocks/wood/{id_}_planks.png"] = (
            planks_source / f"canopy-{id_}-planks.png"
        ).read_bytes()
        tex = f"riven:blocks/wood/{id_}"
        log, leaves, sapling, litter = (
            f"{k}_{id_}" for k in ("log", "leaves", "sapling", "litter")
        )
        out[f"blockstates/{log}.json"] = text(
            {
                "variants": {
                    "axis=y": {"model": f"riven:{log}"},
                    "axis=z": {"model": f"riven:{log}", "x": 90},
                    "axis=x": {"model": f"riven:{log}", "x": 90, "y": 90},
                    "axis=none": {"model": f"riven:{log}_bark"},
                }
            }
        )
        out[f"models/block/{log}.json"] = text(
            {
                "parent": "block/cube_column",
                "textures": {"side": f"{tex}_bark", "end": f"{tex}_end"},
            }
        )
        out[f"models/block/{log}_bark.json"] = text(
            {"parent": "block/cube_all", "textures": {"all": f"{tex}_bark"}}
        )
        out[f"models/item/{log}.json"] = text({"parent": f"riven:block/{log}"})
        # Two foliage textures at random in every decay state; fixed colors, so no biome tint.
        both = [{"model": f"riven:{leaves}_1"}, {"model": f"riven:{leaves}_2"}]
        out[f"blockstates/{leaves}.json"] = text(
            {
                "variants": {
                    f"check_decay={c},decayable={d}": both
                    for c in ("false", "true")
                    for d in ("false", "true")
                }
            }
        )
        for n in (1, 2):
            out[f"models/block/{leaves}_{n}.json"] = text(
                {"parent": "block/cube_all", "textures": {"all": f"{tex}_leaves_{n}"}}
            )
        out[f"models/item/{leaves}.json"] = text({"parent": f"riven:block/{leaves}_1"})
        out[f"blockstates/{sapling}.json"] = text(
            {"variants": {f"stage={s}": {"model": f"riven:{sapling}"} for s in (0, 1)}}
        )
        out[f"models/block/{sapling}.json"] = text(
            {"parent": "block/cross", "textures": {"cross": f"{tex}_sapling"}}
        )
        out[f"models/item/{sapling}.json"] = text(
            {"parent": "item/generated", "textures": {"layer0": f"{tex}_sapling"}}
        )
        out[f"blockstates/{litter}.json"] = text(
            {"variants": {"normal": {"model": f"riven:{litter}"}}}
        )
        # A plane just above the block floor, seen from above and below.
        face = {"uv": [0, 0, 16, 16], "texture": "#surface"}
        out[f"models/block/{litter}.json"] = text(
            {
                "ambientocclusion": False,
                "textures": {"particle": f"{tex}_litter", "surface": f"{tex}_litter"},
                "elements": [
                    {"from": [0, 0.1, 0], "to": [16, 0.1, 16], "faces": {"up": face, "down": face}}
                ],
            }
        )
        out[f"models/item/{litter}.json"] = text(
            {"parent": "item/generated", "textures": {"layer0": f"{tex}_litter"}}
        )
        planks = f"planks_{id_}"
        out[f"blockstates/{planks}.json"] = text(
            {"variants": {"normal": {"model": f"riven:{planks}"}}}
        )
        out[f"models/block/{planks}.json"] = text(
            {"parent": "block/cube_all", "textures": {"all": f"{tex}_planks"}}
        )
        out[f"models/item/{planks}.json"] = text({"parent": f"riven:block/{planks}"})
        lang[f"tile.riven.{planks}.name"] = f"{species['name']} Planks"
        name = species["name"]
        for block, suffix in (
            (log, "Log"),
            (leaves, "Leaves"),
            (sapling, "Sapling"),
            (litter, "Litter"),
        ):
            lang[f"tile.riven.{block}.name"] = f"{name} {suffix}"
    return out, lang


# Kinds built from pieces: the block state property naming a piece, its values and their pieces.
PIECES = {
    "tall": ("half", {"lower": "bottom", "upper": "top"}),
    "cane": ("top", {"false": "stem", "true": "top"}),
}


def decoration_assets(decorations: dict[str, Any]) -> tuple[dict[str, bytes], dict[str, str]]:
    """Each ground decoration's block: textures copied unchanged, four forms by position."""
    out: dict[str, bytes] = {}
    lang: dict[str, str] = {}
    here = Path(__file__).parent
    for id_, subject in decorations["subjects"].items():
        source = here / subject["source"]
        kind = subject["kind"]
        block = f"{kind}_{id_}"
        tex = f"riven:blocks/decor/{id_}"
        if kind in PIECES:
            # A two-block plant's bottom and top, or a cane's stem and top, in each form.
            prop, pieces = PIECES[kind]
            for n, files in enumerate(subject["textures"]):
                for part, stem in zip(pieces.values(), files, strict=True):
                    out[f"textures/blocks/decor/{id_}_{n}_{part}.png"] = (
                        source / f"{stem}.png"
                    ).read_bytes()
                    out[f"models/block/{block}_{n}_{part}.json"] = text(
                        {"parent": "block/cross", "textures": {"cross": f"{tex}_{n}_{part}"}}
                    )
            out[f"blockstates/{block}.json"] = text(
                {
                    "variants": {
                        f"form={n},{prop}={value}": {"model": f"riven:{block}_{n}_{part}"}
                        for n in range(len(subject["textures"]))
                        for value, part in pieces.items()
                    }
                }
            )
            out[f"models/item/{block}.json"] = text(
                {"parent": "item/generated", "textures": {"layer0": f"{tex}_0_top"}}
            )
        else:
            for n, stem in enumerate(subject["textures"]):
                out[f"textures/blocks/decor/{id_}_{n}.png"] = (source / f"{stem}.png").read_bytes()
                if kind == "plant":
                    model: dict[str, Any] = {
                        "parent": "block/cross",
                        "textures": {"cross": f"{tex}_{n}"},
                    }
                else:
                    # A plane just above the block floor, seen from above and below.
                    face = {"uv": [0, 0, 16, 16], "texture": "#surface"}
                    model = {
                        "ambientocclusion": False,
                        "textures": {"particle": f"{tex}_{n}", "surface": f"{tex}_{n}"},
                        "elements": [
                            {
                                "from": [0, 0.1, 0],
                                "to": [16, 0.1, 16],
                                "faces": {"up": face, "down": face},
                            }
                        ],
                    }
                out[f"models/block/{block}_{n}.json"] = text(model)
            # One of the four forms by position, as vanilla picks among a variant's models.
            out[f"blockstates/{block}.json"] = text(
                {
                    "variants": {
                        "normal": [
                            {"model": f"riven:{block}_{n}"} for n in range(len(subject["textures"]))
                        ]
                    }
                }
            )
            out[f"models/item/{block}.json"] = text(
                {"parent": "item/generated", "textures": {"layer0": f"{tex}_0"}}
            )
        lang[f"tile.riven.{block}.name"] = subject["name"]
    return out, lang


def rock_assets(rocks: dict[str, Any]) -> tuple[dict[str, bytes], dict[str, str]]:
    """Each realm rock's block: main and alternate faces unchanged, one of them by position."""
    out: dict[str, bytes] = {}
    lang: dict[str, str] = {}
    source = Path(__file__).parent / rocks["source"]
    for id_, rock in rocks["subjects"].items():
        for n, face in enumerate(rock["faces"]):
            out[f"textures/blocks/rock/{id_}_{n}.png"] = (source / f"{face}.png").read_bytes()
            if rock.get("animated"):
                # Assets are LF (.gitattributes); a CRLF checkout of the source must not differ.
                out[f"textures/blocks/rock/{id_}_{n}.png.mcmeta"] = (
                    (source / f"{face}.png.mcmeta").read_bytes().replace(b"\r\n", b"\n")
                )
            out[f"models/block/{id_}_{n}.json"] = text(
                {"parent": "block/cube_all", "textures": {"all": f"riven:blocks/rock/{id_}_{n}"}}
            )
        out[f"blockstates/{id_}.json"] = text(
            {
                "variants": {
                    "normal": [{"model": f"riven:{id_}_{n}"} for n in range(len(rock["faces"]))]
                }
            }
        )
        out[f"models/item/{id_}.json"] = text({"parent": f"riven:block/{id_}_0"})
        lang[f"tile.riven.{id_}.name"] = rock["name"]
    return out, lang


def assets() -> dict[str, bytes]:
    definitions = json.loads(DEFINITIONS.read_text(encoding="utf-8"))
    out = textures(definitions)
    lang = dict(definitions.get("lang", {}))
    for block in definitions["blocks"]:
        out.update(block_assets(block))
        lang[f"tile.riven.{block['id']}.name"] = block["name"]
    if "ores" in definitions:
        out.update(ore_assets(definitions["ores"], {b["id"]: b for b in definitions["blocks"]}))
        for mineral, spec in definitions["ores"]["minerals"].items():
            lang[f"tile.riven.ore_{mineral}.name"] = f"{spec['name']} Ore"
    if "unstable" in definitions:
        blocks = {b["id"]: b for b in definitions["blocks"]}
        out.update(unstable_assets(definitions["unstable"], blocks))
        for name, host in definitions["unstable"]["hosts"].items():
            lang[f"tile.riven.unstable_{name}.name"] = unstable_name(host, blocks)
    if "woods" in definitions:
        files, names = wood_assets(definitions["woods"])
        out.update(files)
        lang.update(names)
    if "decorations" in definitions:
        files, names = decoration_assets(definitions["decorations"])
        out.update(files)
        lang.update(names)
    if "rocks" in definitions:
        files, names = rock_assets(definitions["rocks"])
        out.update(files)
        lang.update(names)
    out["lang/en_us.lang"] = "".join(f"{k}={v}\n" for k, v in sorted(lang.items())).encode("utf-8")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify instead of writing")
    check = parser.parse_args().check
    wanted = assets()
    present = {p.relative_to(ASSETS).as_posix() for p in ASSETS.rglob("*") if p.is_file()}
    differ = sorted(
        k for k, v in wanted.items() if not (ASSETS / k).is_file() or (ASSETS / k).read_bytes() != v
    )
    stale = sorted(present - wanted.keys())
    if check:
        for path in differ:
            print(f"out of date: assets/riven/{path}")
        for path in stale:
            print(f"not produced by riven-content.json: assets/riven/{path}")
        if differ or stale:
            print("Run tools/content/build_content.py to regenerate.")
            return 1
        print(f"content assets match riven-content.json ({len(wanted)} files)")
        return 0
    for path in differ:
        (ASSETS / path).parent.mkdir(parents=True, exist_ok=True)
        (ASSETS / path).write_bytes(wanted[path])
    for path in stale:
        (ASSETS / path).unlink()
    print(f"wrote {len(differ)} and removed {len(stale)} of {len(wanted)} content assets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
