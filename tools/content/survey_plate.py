"""Survey Plate, Riven's block art language (docs/ART-DIRECTION.md): palette, inventory, tests.

Usage:
  survey_plate.py palette [--check]  derive the ramps' steps from docs/art/survey-plate.json into
                                     survey-plate-palette.json and survey-plate.gpl; --check writes
                                     nothing and fails when the committed files differ
  survey_plate.py inventory JAR      list the Minecraft 1.12.2 client jar's block textures, block
                                     entity sheets and colormaps in docs/art/vanilla-blocks.json
  survey_plate.py sheet OUT.png      draw the ramps, the color script and three construction plates
  survey_plate.py check KIND PNG...  run the acceptance tests on native 16 x 16 textures; KIND is
                                     face, framed, overlay, fluid, translucent, standing or hanging
     [--host PNG]                    an overlay's host face, for the contrast test
     [--alternate PNG]               the main face's alternate, for the pairing test

The tests and their limits are the ones docs/ART-DIRECTION.md states; a change to a limit changes
both. Colors are compared in OKLab (Bjorn Ottosson's perceptual space): L is lightness from 0 to
1, chroma the distance from neutral.
"""

import argparse
import io
import json
import math
import re
import sys
import zipfile
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RAMPS = ROOT / "docs/art/survey-plate.json"
PALETTE = ROOT / "docs/art/survey-plate-palette.json"
GPL = ROOT / "docs/art/survey-plate.gpl"
INVENTORY = ROOT / "docs/art/vanilla-blocks.json"

# Vanilla textures by the first matching rule, on the path under assets/minecraft/textures:
# (pattern, group, test kind). Groups follow Requisition 08's requests.
GROUPS = [
    (r"^colormap/", "colormaps", "colormap"),
    (r"^entity/", "block entities", "entity"),
    (r"^blocks/destroy_stage_", "fluids and effects", "overlay"),
    (r"^blocks/(water|lava)_", "fluids and effects", "fluid"),
    (r"^blocks/fire_layer_", "fluids and effects", "standing"),
    (r"^blocks/portal$", "fluids and effects", "translucent"),
    (r"^blocks/(debug|structure_block|(repeating_|chain_)?command_block)", "creative", "framed"),
    (r"^blocks/(quartz_ore|.*_ore)$", "ores and storage", "face"),
    (
        r"^blocks/(iron|gold|diamond|emerald|lapis|redstone|coal)_block$",
        "ores and storage",
        "framed",
    ),
    (r"^blocks/nether_wart_stage_", "nether", "standing"),
    (
        r"^blocks/(netherrack|soul_sand|nether_brick|red_nether_brick|nether_wart_block|magma)$",
        "nether",
        "face",
    ),
    (r"^blocks/glowstone$", "nether", "framed"),
    (r"^blocks/(end_rod|chorus_flower|chorus_flower_dead)$", "end", "standing"),
    (r"^blocks/(end_stone|end_bricks|purpur_.*|chorus_plant|dragon_egg)$", "end", "face"),
    (r"^blocks/endframe_", "end", "framed"),
    (
        r"^blocks/(stone(_granite|_diorite|_andesite)?(_smooth)?|cobblestone(_mossy)?|bedrock|obsidian)$",
        "terrain",
        "face",
    ),
    (
        r"^blocks/(dirt.*|coarse_dirt|grass_.*|mycelium_.*|farmland_.*|grass_path_.*|sand|red_sand|gravel|clay)$",
        "terrain",
        "face",
    ),
    (r"^blocks/(red_)?sandstone_", "terrain", "face"),
    (r"^blocks/(snow|ice|ice_packed|frosted_ice_.*|hardened_clay.*)$", "terrain", "face"),
    (r"^blocks/(log|planks|leaves)_", "wood", "face"),
    (r"^blocks/sapling_", "wood", "standing"),
    (r"^blocks/(vine|waterlily)$", "plants", "overlay"),
    (
        r"^blocks/(flower_(?!pot).*|double_plant_.*|tallgrass|fern|deadbush|reeds|web|mushroom_(brown|red))$",
        "plants",
        "standing",
    ),
    (
        r"^blocks/((wheat|carrots|potatoes|beetroots|cocoa)_stage_.*|(melon|pumpkin)_stem_.*)$",
        "plants",
        "standing",
    ),
    (
        r"^blocks/(cactus_.*|mushroom_block_.*|pumpkin_.*|melon_(side|top)|hay_block_.*)$",
        "plants",
        "face",
    ),
    (r"^blocks/glass_(pane_top_)?(?!pane_top$)[a-z_]+$", "dyed goods", "translucent"),
    (
        r"^blocks/(wool_colored_.*|concrete_.*|glazed_terracotta_.*|shulker_top_.*)$",
        "dyed goods",
        "framed",
    ),
    (
        r"^blocks/(glass|glass_pane_top|iron_bars|door_.*|trapdoor|iron_trapdoor)$",
        "masonry and joinery",
        "framed",
    ),
    (r"^blocks/ladder$", "masonry and joinery", "overlay"),
    (
        r"^blocks/(stonebrick.*|brick|stone_slab_.*|quartz_block_.*|prismarine_.*|bone_block_.*|sponge.*|slime|sea_lantern)$",
        "masonry and joinery",
        "face",
    ),
    (r"^blocks/(redstone_dust_.*|rail_.*)$", "functional", "overlay"),
    (r"^blocks/(redstone_torch_.*|torch_on|lever|trip_wire.*)$", "functional", "standing"),
    (r"^blocks/", "functional", "framed"),
]
TINTED = re.compile(
    r"^blocks/(grass_top|grass_side_overlay|tallgrass|fern|double_plant_(grass|fern)_.*|leaves_.*"
    r"|vine|waterlily|(melon|pumpkin)_stem_.*|reeds|redstone_dust_.*|water_.*)$"
)
EMISSIVE = re.compile(
    r"^blocks/(torch_on|redstone_torch_on|glowstone|sea_lantern|redstone_lamp_on|magma|lava_.*"
    r"|fire_layer_.*|portal|end_rod|pumpkin_face_on|furnace_front_on|beacon)$"
)
# Selective labPBR: where light matters (gems, ores, metals, ice, glass, wet and polished surfaces,
# fluids) and every emitter.
PBR = re.compile(
    r"^blocks/(.*_ore|(iron|gold|diamond|emerald|lapis|redstone)_block|ice|ice_packed"
    r"|frosted_ice_.*|glass.*|water_.*|rail_.*|iron_.*|door_iron_.*|anvil_.*|cauldron_.*"
    r"|hopper_.*|obsidian|slime|sponge_wet|prismarine_.*|quartz_block_.*|end_rod|beacon)$"
)
PLACED = re.compile(
    r"^blocks/(stone|stone_granite|stone_diorite|stone_andesite|gravel|dirt|coarse_dirt"
    r"|grass_(top|side|side_overlay|side_snowed)|sand|snow|clay|(red_)?sandstone_.*|bedrock|ice"
    r"|ice_packed|magma|web|mushroom_(brown|red)|netherrack|lava_.*|water_.*|hardened_clay"
    r"|hardened_clay_stained_(orange|yellow|brown|red|white|gray|black))$"
)

RGB = tuple[int, int, int]
Lab = tuple[float, float, float]

# Acceptance limits (docs/ART-DIRECTION.md, Acceptance tests).
MAX_COLORS = {"framed": 10}
MAX_COLORS_DEFAULT = 8
MAX_RAMPS = {"framed": 3, "fluid": 2, "translucent": 2}
MAX_RAMPS_DEFAULT = 2
MAX_SIGNAL_RAMPS = 2
MAX_SINGLETONS = 0.12
MIN_MEAN_REGION = 4.0
MIN_LARGEST_REGION = 24
INK_SHARE = (0.04, 0.22)
MAX_INK_MASS = 0.10
MAX_INK_EDGE = 0.5
MIN_VALUE_SPAN = 0.22
MAX_QUADRANT_SPREAD = 0.045
MIN_SEAM_CONTINUITY = 0.8
MAX_SWATCH_DISTANCE = 0.08
MIN_OVERLAY_CONTRAST = 0.12
MAX_ALTERNATE_DISTANCE = 0.025
TILING = {"face", "framed", "overlay", "fluid", "translucent"}

# Construction plates for the sheet: ramp, bedding stretch (1 is directionless), forms.
PLATES = [("stone", 1.0, 5), ("sandstone", 2.6, 4), ("basement", 1.8, 4)]


def to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def to_gamma(c: float) -> float:
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def oklab(rgb: RGB) -> Lab:
    r, g, b = (to_linear(v / 255) for v in rgb)
    lms = (
        0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b,
        0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b,
        0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b,
    )
    lc, mc, sc = (math.copysign(abs(v) ** (1 / 3), v) for v in lms)
    return (
        0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
        1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
        0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc,
    )


def linear_from_oklch(lightness: float, chroma: float, hue: float) -> tuple[float, float, float]:
    a, b = chroma * math.cos(math.radians(hue)), chroma * math.sin(math.radians(hue))
    lc = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3
    mc = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3
    sc = (lightness - 0.0894841775 * a - 1.2914855480 * b) ** 3
    return (
        4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
        -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
        -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc,
    )


def in_gamut(lightness: float, chroma: float, hue: float) -> bool:
    return all(-1e-6 <= v <= 1 + 1e-6 for v in linear_from_oklch(lightness, chroma, hue))


def from_oklch(lightness: float, chroma: float, hue: float) -> RGB:
    """sRGB for an OKLCH color, its chroma reduced until the color fits the gamut."""
    if not in_gamut(lightness, chroma, hue):
        low, high = 0.0, chroma
        for _ in range(30):
            mid = (low + high) / 2
            low, high = (mid, high) if in_gamut(lightness, mid, hue) else (low, mid)
        chroma = low
    r, g, b = (
        round(255 * to_gamma(min(1.0, max(0.0, v))))
        for v in linear_from_oklch(lightness, chroma, hue)
    )
    return r, g, b


def turn(hue: float, target: float, degrees: float) -> float:
    """A hue moved toward a target by up to some degrees, never past it."""
    delta = (target - hue + 180) % 360 - 180
    return (hue + max(-degrees, min(degrees, delta))) % 360


def ramp_steps(ramp: dict[str, Any], steps: dict[str, Any]) -> list[RGB]:
    """A ramp's five colors: ink, shadow, body, light, highlight. Steps that would pass the
    lightness limits are compressed into the room left, then held at least a step gap apart."""
    body, hue, chroma = ramp["lightness"], ramp["hue"], ramp["chroma"]
    low, high, gap = steps["minLightness"], steps["maxLightness"], steps["minStepGap"]
    offsets = steps["lightness"]
    down, up = -min(offsets), max(offsets)
    lightness = []
    for offset in offsets:
        if offset < 0:
            lightness.append(body + offset * min(1.0, max(0.0, body - low) / down))
        else:
            lightness.append(body + offset * min(1.0, max(0.0, high - body) / up))
    middle = offsets.index(0.0)
    for i in range(middle + 1, len(lightness)):
        lightness[i] = min(0.985, max(lightness[i], lightness[i - 1] + gap))
    for i in range(middle - 1, -1, -1):
        lightness[i] = max(0.05, min(lightness[i], lightness[i + 1] - gap))
    colors = []
    for value, scale, shift in zip(lightness, steps["chroma"], steps["hueShift"]):
        target = steps["coolHue"] if value < body else steps["warmHue"]
        colors.append(
            from_oklch(value, max(steps["minChroma"], chroma * scale), turn(hue, target, shift))
        )
    return colors


def load_ramps() -> dict[str, Any]:
    return json.loads(RAMPS.read_text(encoding="utf-8"))


def palette(definitions: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        name: {
            "tier": ramp["tier"],
            "steps": ["#%02X%02X%02X" % c for c in ramp_steps(ramp, definitions["steps"])],
        }
        for name, ramp in definitions["ramps"].items()
    }


def palette_files(definitions: dict[str, Any]) -> dict[Path, str]:
    ramps = palette(definitions)
    names = definitions["steps"]["names"]
    gpl = ["GIMP Palette", "Name: Riven Survey Plate", "Columns: 5", "#"]
    for name, ramp in ramps.items():
        for step, color in zip(names, ramp["steps"]):
            r, g, b = (int(color[i : i + 2], 16) for i in (1, 3, 5))
            gpl.append(f"{r:3d} {g:3d} {b:3d}\t{name} {step}")
    document = {
        "about": "Generated by tools/content/survey_plate.py palette from survey-plate.json.",
        "steps": names,
        "ramps": ramps,
    }
    return {
        PALETTE: json.dumps(document, indent=2) + "\n",
        GPL: "\n".join(gpl) + "\n",
    }


def lookup(definitions: dict[str, Any]) -> dict[RGB, tuple[str, int, str]]:
    """Each palette color's ramp, step index and tier."""
    table: dict[RGB, tuple[str, int, str]] = {}
    for name, ramp in definitions["ramps"].items():
        for index, color in enumerate(ramp_steps(ramp, definitions["steps"])):
            table.setdefault(color, (name, index, ramp["tier"]))
    return table


def plate(
    definitions: dict[str, Any], ramp: str, stretch: float, seeds: int, salt: int
) -> Image.Image:
    """A construction plate: toroidal cells as forms, ink on each form's lower-right rim, light on
    its upper-left lip, a few ink breaks. An illustration of the rules, not production art."""
    colors = ramp_steps(definitions["ramps"][ramp], definitions["steps"])
    points = []
    for i in range(seeds):
        h = (i * 0x9E3779B1 + salt * 0x85EBCA77) & 0xFFFFFFFF
        points.append(((h & 0xFFFF) / 0xFFFF * 16, (h >> 16) / 0xFFFF * 16))

    def cell(x: int, y: int) -> int:
        best, index = 1e9, 0
        for i, (px, py) in enumerate(points):
            dx = min(abs(x + 0.5 - px), 16 - abs(x + 0.5 - px)) / stretch
            dy = min(abs(y + 0.5 - py), 16 - abs(y + 0.5 - py)) * stretch
            if dx * dx + dy * dy < best:
                best, index = dx * dx + dy * dy, i
        return index

    cells = [[cell(x, y) for x in range(16)] for y in range(16)]
    image = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = cells[y][x]
            right, down = cells[y][(x + 1) % 16], cells[(y + 1) % 16][x]
            left, up = cells[y][(x - 1) % 16], cells[(y - 1) % 16][x]
            body = 1 if c % 4 == 1 else 2
            broken = 0 < x < 15 and 0 < y < 15 and (x * 7 + y * 13 + salt) % 10 < 3
            if (right != c or down != c) and not broken:
                step = 0
            elif right != c or down != c:
                step = body - 1 if body > 1 else 1
            elif left != c or up != c:
                step = body + 1
            else:
                step = body
            image.putpixel((x, y), colors[step] + (255,))
    return image


def passing_plate(
    definitions: dict[str, Any], ramp: str, stretch: float, seeds: int
) -> Image.Image:
    """The first construction plate, by salt, that passes the face tests."""
    for salt in range(256):
        face = plate(definitions, ramp, stretch, seeds, salt)
        if not check(face, "face", definitions, None, None):
            return face
    raise SystemExit(f"no {ramp} construction plate passes the face tests")


def sheet(definitions: dict[str, Any], out: Path) -> None:
    """Ramps by tier, the color script, and the construction plates as single faces and walls."""
    scale, swatch = 6, 14
    ramps = definitions["ramps"]
    tiers = list(definitions["tiers"])
    columns = 6
    rows_by_tier = {t: [n for n, r in ramps.items() if r["tier"] == t] for t in tiers}
    height = 40 + sum(
        22 + math.ceil(len(n) / columns) * (swatch + 16) for n in rows_by_tier.values()
    )
    width = columns * (5 * swatch + 70) + 40
    script_height = 2 * (len(definitions["descent"]) * 24 + 30)
    image = Image.new("RGB", (width, height + script_height + 16 * 4 * scale + 80), (24, 24, 28))
    draw = ImageDraw.Draw(image)
    y = 20
    for tier in tiers:
        draw.text((20, y), tier.upper(), fill=(220, 220, 210))
        y += 22
        for i, name in enumerate(rows_by_tier[tier]):
            x = 20 + (i % columns) * (5 * swatch + 70)
            row_y = y + (i // columns) * (swatch + 16)
            for s, color in enumerate(ramp_steps(ramps[name], definitions["steps"])):
                draw.rectangle(
                    (x + s * swatch, row_y, x + (s + 1) * swatch - 1, row_y + swatch - 1), color
                )
            draw.text((x, row_y + swatch + 1), name, fill=(170, 170, 165))
        y += math.ceil(len(rows_by_tier[tier]) / columns) * (swatch + 16)
    for title, key, label in (("DESCENT", "descent", "realm"), ("SURFACE", "surface", "climate")):
        draw.text((20, y + 8), title, fill=(220, 220, 210))
        y += 30
        for entry in definitions[key]:
            x = 20
            for name in entry["ramps"]:
                for color in ramp_steps(ramps[name], definitions["steps"]):
                    draw.rectangle((x, y, x + 15, y + 19), color)
                    x += 16
                x += 6
            draw.text((x + 8, y + 4), entry[label], fill=(170, 170, 165))
            y += 24
    y += 10
    x = 20
    for ramp, stretch, seeds in PLATES:
        face = passing_plate(definitions, ramp, stretch, seeds)
        image.paste(face.resize((16 * scale, 16 * scale), Image.Resampling.NEAREST), (x, y))
        wall = Image.new("RGBA", (64, 64))
        for i in range(16):
            wall.paste(face, ((i % 4) * 16, (i // 4) * 16))
        image.paste(wall.resize((128, 128), Image.Resampling.NEAREST), (x + 16 * scale + 8, y))
        draw.text((x, y + 16 * scale + 4), f"{ramp} (construction)", fill=(170, 170, 165))
        x += 16 * scale + 128 + 30
    image.crop((0, 0, width, y + 140)).save(out)


def regions(pixels: dict[tuple[int, int], RGB]) -> list[int]:
    seen: set[tuple[int, int]] = set()
    sizes = []
    for start in pixels:
        if start in seen:
            continue
        color, stack, size = pixels[start], [start], 0
        seen.add(start)
        while stack:
            x, y = stack.pop()
            size += 1
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = ((x + dx) % 16, (y + dy) % 16)
                if q not in seen and pixels.get(q) == color:
                    seen.add(q)
                    stack.append(q)
        sizes.append(size)
    return sizes


def inventory(jar: Path) -> str:
    """The client jar's block textures, block entity sheets and colormaps, one line each."""
    archive = zipfile.ZipFile(jar)
    prefix = "assets/minecraft/textures/"
    names = set(archive.namelist())
    entities = re.compile(
        r"^entity/(chest|bed|shulker)/|^entity/(banner|banner_base|sign|enchanting_table_book"
        r"|beacon_beam|end_gateway_beam|end_portal)[./]"
    )
    entries: list[dict[str, Any]] = []
    for name in sorted(names):
        if not name.startswith(prefix) or not name.endswith(".png"):
            continue
        path = name[len(prefix) : -len(".png")]
        if not (path.startswith(("blocks/", "colormap/")) or entities.match(path + ".")):
            continue
        group, kind = next((g, k) for pattern, g, k in GROUPS if re.match(pattern, path))
        width, height = Image.open(io.BytesIO(archive.read(name))).size
        entries.append(
            {
                "path": path,
                "group": group,
                "kind": kind,
                "size": [width, height],
                "animated": name + ".mcmeta" in names,
                "tinted": bool(TINTED.match(path)),
                "emissive": bool(EMISSIVE.match(path)),
                "pbr": bool(PBR.match(path) or EMISSIVE.match(path)),
                "placedByRiven": bool(PLACED.match(path)),
            }
        )
    groups: dict[str, int] = {}
    for entry in entries:
        groups[entry["group"]] = groups.get(entry["group"], 0) + 1
    head = {
        "about": "Vanilla Minecraft 1.12.2 textures Survey Plate replaces, from the client jar "
        "(tools/content/survey_plate.py inventory). Paths are under assets/minecraft/textures.",
        "count": len(entries),
        "groups": groups,
    }
    lines = ",\n".join("    " + json.dumps(e) for e in entries)
    return json.dumps(head, indent=2)[:-2] + ',\n  "textures": [\n' + lines + "\n  ]\n}\n"


def load(path: Path) -> Image.Image:
    """A native texture's first 16 x 16 frame."""
    image = Image.open(path).convert("RGBA")
    if image.size[0] != 16 or image.size[1] % 16:
        raise SystemExit(
            f"{path}: {image.size[0]} x {image.size[1]} is not a 16 x 16 face or strip"
        )
    return image.crop((0, 0, 16, 16))


def read(image: Image.Image) -> tuple[dict[tuple[int, int], RGB], list[int]]:
    """Opaque pixels by position, and every pixel's alpha."""
    data = [image.getpixel((i % 16, i // 16)) for i in range(256)]
    pixels = {(i % 16, i // 16): p[:3] for i, p in enumerate(data) if p[3] == 255}
    return pixels, [p[3] for p in data]


def check(
    image: Image.Image,
    kind: str,
    definitions: dict[str, Any],
    host: Image.Image | None,
    alternate: Image.Image | None,
) -> list[str]:
    """Failed tests for one texture, as 'test: measured (limit)'."""
    table = lookup(definitions)
    pixels, alphas = read(image)
    failures: list[str] = []
    if not pixels:
        return ["opaque: no opaque pixels"]
    off = [p for p, c in pixels.items() if c not in table]
    if off:
        failures.append(f"palette: {len(off)} pixels off the palette, first at {off[0]}")
    partial = sum(1 for a in alphas if 0 < a < 255)
    if partial and kind != "translucent":
        failures.append(f"alpha: {partial} partly transparent pixels (only translucent may)")
    known = {p: table[c] for p, c in pixels.items() if c in table}
    ramps = {r for r, _, t in known.values() if t != "signal"}
    signal = {r for r, _, t in known.values() if t == "signal"}
    if len(ramps) > MAX_RAMPS.get(kind, MAX_RAMPS_DEFAULT):
        failures.append(
            f"ramps: {len(ramps)} {sorted(ramps)} ({MAX_RAMPS.get(kind, MAX_RAMPS_DEFAULT)})"
        )
    if len(signal) > MAX_SIGNAL_RAMPS:
        failures.append(f"signal ramps: {len(signal)} ({MAX_SIGNAL_RAMPS})")
    colors = len(set(pixels.values()))
    if colors > MAX_COLORS.get(kind, MAX_COLORS_DEFAULT):
        failures.append(f"colors: {colors} ({MAX_COLORS.get(kind, MAX_COLORS_DEFAULT)})")
    quiet = {p: c for p, c in pixels.items() if known.get(p, ("", 0, ""))[2] != "signal"}
    lone = sum(
        1
        for (x, y), c in quiet.items()
        if all(
            pixels.get(((x + dx) % 16, (y + dy) % 16)) != c
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
        )
    )
    if quiet and lone / len(quiet) > MAX_SINGLETONS:
        failures.append(f"noise: {lone / len(quiet):.2f} of pixels stand alone ({MAX_SINGLETONS})")
    sizes = regions(pixels)
    if kind in ("face", "framed"):
        if len(pixels) / len(sizes) < MIN_MEAN_REGION:
            failures.append(
                f"forms: mean region {len(pixels) / len(sizes):.1f} px ({MIN_MEAN_REGION})"
            )
        if max(sizes) < MIN_LARGEST_REGION:
            failures.append(f"forms: largest region {max(sizes)} px ({MIN_LARGEST_REGION})")
    ink = {p for p, (_, step, t) in known.items() if step == 0 and t != "signal"}
    if kind == "fluid":
        if ink:
            failures.append(f"ink: {len(ink)} ink pixels in a fluid (none)")
    elif kind in ("face", "framed"):
        share = len(ink) / len(pixels)
        if not INK_SHARE[0] <= share <= INK_SHARE[1]:
            failures.append(f"ink: share {share:.2f} {INK_SHARE}")
    if ink:
        mass = sum(
            1
            for x, y in ink
            if sum(
                ((x + dx) % 16, (y + dy) % 16) in ink
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
            )
            >= 3
        )
        if mass / len(ink) > MAX_INK_MASS:
            failures.append(
                f"ink: {mass / len(ink):.2f} of ink in masses, not lines ({MAX_INK_MASS})"
            )
        if kind != "framed":
            for name, line in (
                ("top", [(x, 0) for x in range(16)]),
                ("bottom", [(x, 15) for x in range(16)]),
                ("left", [(0, y) for y in range(16)]),
                ("right", [(15, y) for y in range(16)]),
            ):
                if sum(p in ink for p in line) / 16 >= MAX_INK_EDGE:
                    failures.append(f"ink: {name} edge is a frame ({MAX_INK_EDGE})")
        if kind in TILING:
            edge = [(x, y) for x, y in ink if x in (0, 15) or y in (0, 15)]
            joined = 0
            for x, y in edge:
                if x in (0, 15):
                    ox = 15 - x
                    joined += any((ox, (y + d) % 16) in ink for d in (-1, 0, 1))
                else:
                    oy = 15 - y
                    joined += any(((x + d) % 16, oy) in ink for d in (-1, 0, 1))
            if edge and joined / len(edge) < MIN_SEAM_CONTINUITY:
                failures.append(
                    f"seams: {joined / len(edge):.2f} of edge ink continues ({MIN_SEAM_CONTINUITY})"
                )
    labs = {p: oklab(c) for p, c in pixels.items()}
    if kind not in ("fluid", "translucent", "overlay"):
        span = max(v[0] for v in labs.values()) - min(v[0] for v in labs.values())
        if span < MIN_VALUE_SPAN:
            failures.append(f"value: span {span:.2f} ({MIN_VALUE_SPAN})")
    if kind in ("face", "framed", "fluid"):
        means = []
        for qx in (0, 8):
            for qy in (0, 8):
                q = [labs[p][0] for p in quiet if qx <= p[0] < qx + 8 and qy <= p[1] < qy + 8]
                if q:
                    means.append(sum(q) / len(q))
        if len(means) == 4 and max(means) - min(means) > MAX_QUADRANT_SPREAD:
            failures.append(
                f"balance: quadrant spread {max(means) - min(means):.3f} ({MAX_QUADRANT_SPREAD})"
            )
        counts: dict[str, int] = {}
        for r, _, t in known.values():
            if t != "signal":
                counts[r] = counts.get(r, 0) + 1
        if counts:
            primary = max(counts, key=lambda r: counts[r])
            body = oklab(ramp_steps(definitions["ramps"][primary], definitions["steps"])[2])
            mean = tuple(sum(v[i] for v in labs.values()) / len(labs) for i in range(3))
            if math.dist(mean, body) > MAX_SWATCH_DISTANCE:
                failures.append(
                    f"swatch: mean is {math.dist(mean, body):.3f} from {primary} body ({MAX_SWATCH_DISTANCE})"
                )
    if kind == "standing" and not any(y == 15 for _, y in pixels):
        failures.append("attachment: a standing subject must reach the bottom row")
    if kind == "hanging" and not any(y == 0 for _, y in pixels):
        failures.append("attachment: a hanging subject must reach the top row")
    if host is not None:
        under, _ = read(host)
        deltas = sorted(abs(labs[p][0] - oklab(under[p])[0]) for p in pixels if p in under)
        if deltas and deltas[len(deltas) // 2] < MIN_OVERLAY_CONTRAST:
            failures.append(
                f"contrast: median lightness step {deltas[len(deltas) // 2]:.2f} over the host ({MIN_OVERLAY_CONTRAST})"
            )
    if alternate is not None:
        other, _ = read(alternate)
        a = [sum(oklab(c)[i] for c in pixels.values()) / len(pixels) for i in range(3)]
        b = [sum(oklab(c)[i] for c in other.values()) / len(other) for i in range(3)]
        if math.dist(a, b) > MAX_ALTERNATE_DISTANCE:
            failures.append(
                f"alternate: means {math.dist(a, b):.3f} apart ({MAX_ALTERNATE_DISTANCE})"
            )
        other_ramps = {table[c][0] for c in other.values() if c in table}
        if other_ramps != {r for r, _, _ in known.values()}:
            failures.append("alternate: uses other ramps than the main face")
    return failures


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)
    palette_command = commands.add_parser("palette")
    palette_command.add_argument("--check", action="store_true")
    inventory_command = commands.add_parser("inventory")
    inventory_command.add_argument("jar", type=Path)
    sheet_command = commands.add_parser("sheet")
    sheet_command.add_argument("out", type=Path)
    check_command = commands.add_parser("check")
    check_command.add_argument(
        "kind", choices=["face", "framed", "overlay", "fluid", "translucent", "standing", "hanging"]
    )
    check_command.add_argument("textures", type=Path, nargs="+")
    check_command.add_argument("--host", type=Path)
    check_command.add_argument("--alternate", type=Path)
    args = parser.parse_args()
    definitions = load_ramps()
    if args.command == "palette":
        files = palette_files(definitions)
        if args.check:
            stale = [
                p
                for p, text in files.items()
                if not p.exists() or p.read_text(encoding="utf-8") != text
            ]
            if stale:
                sys.exit("stale: " + ", ".join(str(p.relative_to(ROOT)) for p in stale))
            return
        for path, text in files.items():
            path.write_text(text, encoding="utf-8", newline="\n")
    elif args.command == "inventory":
        INVENTORY.write_text(inventory(args.jar), encoding="utf-8", newline="\n")
    elif args.command == "sheet":
        sheet(definitions, args.out)
    else:
        failed = 0
        for texture in args.textures:
            host = load(args.host) if args.host else None
            alternate = load(args.alternate) if args.alternate else None
            failures = check(load(texture), args.kind, definitions, host, alternate)
            failed += bool(failures)
            print(("FAIL " if failures else "pass ") + str(texture))
            for failure in failures:
                print("     " + failure)
        if failed:
            sys.exit(f"{failed} of {len(args.textures)} textures failed")


if __name__ == "__main__":
    main()
