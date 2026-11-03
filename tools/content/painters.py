"""Original Riven textures painted from code: deterministic 16 x 16 RGBA pixel art."""

from collections.abc import Callable

Pixel = tuple[int, int, int, int]
Image = list[list[Pixel]]

SIZE = 16
CLEAR: Pixel = (0, 0, 0, 0)

# Dripstone: warm grey-brown calcite stained by iron.
DRIPSTONE = [
    (0x4E, 0x3F, 0x35),
    (0x6E, 0x5A, 0x4B),
    (0x8B, 0x74, 0x61),
    (0xA7, 0x8F, 0x79),
    (0xC1, 0xA9, 0x91),
]

# Formation materials by setting, dark to light: calcite (the dripstone above), ice from frozen
# seepage, halite in salt caves, basalt drips under lava.
PALETTES = {
    "dripstone": DRIPSTONE,
    "ice": [
        (0x5B, 0x7F, 0xA6),
        (0x7F, 0xA4, 0xC9),
        (0xA3, 0xC4, 0xE2),
        (0xC6, 0xDD, 0xF0),
        (0xE6, 0xF2, 0xFB),
    ],
    "salt": [
        (0x9C, 0x8E, 0x8C),
        (0xBD, 0xAF, 0xAB),
        (0xD6, 0xCB, 0xC6),
        (0xE8, 0xE0, 0xDB),
        (0xF6, 0xF1, 0xEC),
    ],
    "basalt": [
        (0x1E, 0x1C, 0x21),
        (0x2F, 0x2C, 0x33),
        (0x42, 0x3E, 0x46),
        (0x57, 0x51, 0x5A),
        (0x6E, 0x66, 0x70),
    ],
    # Placeholders until the art pack fills their slots, ramped on the delivered faces' tones:
    # tidal mud (Requisition 04), coral white sand (04, paled toward salt) and volcanic ash (04).
    "mud": [
        (0x3F, 0x38, 0x2F),
        (0x4D, 0x45, 0x38),
        (0x5B, 0x50, 0x40),
        (0x6A, 0x5D, 0x49),
        (0x7A, 0x6C, 0x55),
    ],
    "salt_sand": [
        (0xA6, 0x9E, 0x8A),
        (0xB8, 0xB0, 0x9B),
        (0xC8, 0xC1, 0xAD),
        (0xD6, 0xD0, 0xBE),
        (0xE4, 0xDF, 0xCF),
    ],
    "volcanic_ash": [
        (0x65, 0x5D, 0x52),
        (0x76, 0x6C, 0x5F),
        (0x84, 0x79, 0x6B),
        (0x97, 0x8B, 0x7A),
        (0xA8, 0x9C, 0x8A),
    ],
}

# Cave fungi placeholders, dark to light, on Requisition 07's tones: (stem ramp, cap ramp, cap
# shape). A dome is broader than tall; a bell is tall and narrow with an inky rim (ink cap).
FUNGI = {
    "glowing_mushroom": (
        [(0x6E, 0x89, 0x70), (0x8B, 0xA6, 0x89), (0xAC, 0xC5, 0xA4)],
        [(0x6E, 0x89, 0x70), (0x8B, 0xA6, 0x89), (0xAC, 0xC5, 0xA4), (0xC9, 0xDA, 0xBD)],
        "dome",
    ),
    "pale_cave_mushroom": (
        [(0x8A, 0x82, 0x79), (0xAA, 0xA2, 0x96), (0xC5, 0xBD, 0xB0)],
        [(0x62, 0x5C, 0x58), (0xAA, 0xA2, 0x96), (0xC5, 0xBD, 0xB0), (0xDD, 0xD5, 0xC5)],
        "dome",
    ),
    "ink_cap": (
        [(0x70, 0x6A, 0x67), (0x96, 0x8C, 0x82), (0xB8, 0xAA, 0x9B)],
        [(0x4D, 0x48, 0x48), (0x70, 0x6A, 0x67), (0x96, 0x8C, 0x82), (0xB8, 0xAA, 0x9B)],
        "bell",
    ),
}

# Sediment faces: how much grains vary, how often a grain stands out, and whether the face is
# smeared into flat bands (wet mud) rather than speckled.
SEDIMENTS = {
    "mud": (0.16, 0.05, True),
    "salt_sand": (0.18, 0.06, False),
    "volcanic_ash": (0.20, 0.06, False),
}


def noise(x: int, y: int, salt: int) -> float:
    """A [0, 1) hash of a pixel; the same on every machine."""
    h = (x * 0x9E3779B1 + y * 0x85EBCA77 + salt * 0xC2B2AE3D) & 0xFFFFFFFF
    h ^= h >> 15
    h = (h * 0x2C1B3C6D) & 0xFFFFFFFF
    h ^= h >> 12
    h = (h * 0x297A2D39) & 0xFFFFFFFF
    h ^= h >> 15
    return h / 2**32


def shade(level: float, material: str = "dripstone") -> Pixel:
    """A formation material's colour for a level in [0, 1]."""
    palette = PALETTES[material]
    i = max(0, min(len(palette) - 1, int(level * len(palette))))
    r, g, b = palette[i]
    return (r, g, b, 255)


def blank() -> Image:
    return [[CLEAR] * SIZE for _ in range(SIZE)]


def dripstone_block() -> Image:
    """Flowstone: vertical drip streaks over speckled calcite."""
    image = blank()
    streak = [noise(x, 0, 11) for x in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            band = 0.18 * ((y + int(streak[x] * 7)) % 5 == 0)
            level = 0.25 + 0.45 * streak[x] + 0.25 * noise(x, y, 12) - band
            image[y][x] = shade(level)
    return image


def sediment(material: str) -> Image:
    """A loose ground face: speckled grains, or flat wet smears for mud; tiles on every side."""
    image = blank()
    spread, grains, smeared = SEDIMENTS[material]
    salt = 50 + len(material)
    for y in range(SIZE):
        # A smear shifts a whole row a little, so wet mud reads as laid down in sheets.
        row = 0.12 * (noise(0, y // 2, salt) - 0.5) if smeared else 0.0
        for x in range(SIZE):
            level = 0.5 + row + spread * (noise(x, y, salt + 1) - 0.5)
            if noise(x, y, salt + 2) < grains:
                level += 0.3 if noise(x, y, salt + 3) < 0.5 else -0.3
            image[y][x] = shade(level, material)
    return image


def fungus(species: str, form: int) -> Image:
    """A crossed-plane cluster of one to three mushrooms, lit from the left; one image per form."""
    image = blank()
    stems, caps, shape = FUNGI[species]
    salt = 70 + len(species) + 13 * form
    count = 1 + int(noise(form, 0, salt) * 3)
    # Each mushroom keeps its own slot across the face, so no cap grows through another's stem.
    slots = {1: [7], 2: [4, 10], 3: [2, 7, 11]}[count]
    shrooms = []
    for k in range(count):
        cx = slots[k] + int(noise(k, 1, salt) * 3) - 1
        cap = 5 if shape == "bell" else 2 + int(noise(k, 4, salt) * 2)
        height = 5 + int(noise(k, 2, salt) * (8 if shape == "bell" else 6)) - 2 * (k > 0)
        height = max(height, cap + 2)
        half = (1.9 if shape == "bell" else 1.6) + noise(k, 3, salt) * (1.0 if count > 2 else 1.6)
        shrooms.append((cx, SIZE - 1 - height, half, cap))
    for cx, top, _, cap in shrooms:
        for y in range(top + cap, SIZE):
            for x in (cx, cx + 1):
                r, g, b = stems[2 if x == cx else 0]
                image[y][x] = (r, g, b, 255)
    # Taller caps first, so the shorter ones in front overlap them.
    for cx, top, half, cap in sorted(shrooms, key=lambda m: m[1]):
        for j in range(cap):
            y = top + j
            # A dome widens toward its rim; a bell is near its full width from the top, and its
            # lower edge runs with ink.
            t = (j + 1) / cap
            w = half * (0.55 + 0.45 * t) if shape == "dome" else half * (0.75 + 0.25 * t)
            for x in range(SIZE):
                d = x + 0.5 - (cx + 1)
                if abs(d) > w:
                    continue
                level = 2 + (d < 0) - (abs(d) > w - 0.9)
                if shape == "bell" and j >= cap - 2 and (abs(d) > w - 0.9 or j == cap - 1):
                    level = 0
                r, g, b = caps[max(0, min(len(caps) - 1, level))]
                image[y][x] = (r, g, b, 255)
    return image


# Placeholders for hanging roots (Requisition 05's root threads), green biofilm (Requisition 07)
# and the stonebell, which has no requisitioned art yet: dark to light.
ROOTS = [(0x55, 0x4B, 0x3B), (0x70, 0x62, 0x4B), (0x8A, 0x79, 0x5D)]
BIOFILM = [(0x42, 0x5A, 0x4A), (0x58, 0x73, 0x5B), (0x72, 0x8D, 0x6B), (0x91, 0xA9, 0x83)]
STONEBELL_STEM = [(0x5E, 0x6B, 0x58), (0x7A, 0x8A, 0x6F)]
STONEBELL_BELL = [(0x7C, 0x7F, 0xA8), (0xA3, 0xA6, 0xC9), (0xC9, 0xCB, 0xE4)]


def opaque(rgb: tuple[int, int, int]) -> Pixel:
    return (rgb[0], rgb[1], rgb[2], 255)


def roots(form: int) -> Image:
    """Root strands hanging from the top edge, wandering a pixel at a time, lit from the left."""
    image = blank()
    salt = 90 + 7 * form
    for k in range(3 + int(noise(form, 0, salt) * 3)):
        x = 2 + int(noise(k, 1, salt) * 12)
        length = 6 + int(noise(k, 2, salt) * 9)
        for y in range(length):
            if noise(k, y, salt + 1) < 0.25:
                x = max(1, min(SIZE - 2, x + (1 if noise(k, y, salt + 2) < 0.5 else -1)))
            image[y][x] = opaque(ROOTS[2 if y < 2 else 1])
            image[y][x + 1] = opaque(ROOTS[0]) if y < length - 2 else CLEAR
    return image


def biofilm(form: int) -> Image:
    """A slick of green film over a wet floor, in soft patches; clear between them."""
    image = blank()
    salt = 110 + 5 * form
    for y in range(SIZE):
        for x in range(SIZE):
            # Smooth coarse noise (interpolated between 4-pixel cells, wrapping so the face tiles)
            # blended with fine grain, so patches have ragged but connected edges.
            gx, gy, fx, fy = x // 4, y // 4, (x % 4) / 4, (y % 4) / 4

            def cell(i: int, j: int) -> float:
                return noise(i % 4, j % 4, salt)

            top = cell(gx, gy) * (1 - fx) + cell(gx + 1, gy) * fx
            bottom = cell(gx, gy + 1) * (1 - fx) + cell(gx + 1, gy + 1) * fx
            coarse = top * (1 - fy) + bottom * fy
            fine = noise(x, y, salt + 1)
            film = 0.7 * coarse + 0.3 * fine
            if film > 0.55:
                level = min(len(BIOFILM) - 1, int((film - 0.55) / 0.45 * len(BIOFILM) * 1.4))
                image[y][x] = opaque(BIOFILM[level])
    return image


def stonebell(form: int) -> Image:
    """One to three thin stems, each nodding under a small pale bell."""
    image = blank()
    salt = 130 + 11 * form
    count = 1 + int(noise(form, 0, salt) * 3)
    slots = {1: [7], 2: [4, 10], 3: [3, 7, 12]}[count]
    for k in range(count):
        x = slots[k] + int(noise(k, 1, salt) * 3) - 1
        top = 5 + int(noise(k, 2, salt) * 6)
        for y in range(top - 1, SIZE):
            image[y][x] = opaque(STONEBELL_STEM[1 if y < SIZE - 3 else 0])
        # The stem bends over in a crook and the bell hangs under its end, flaring at the mouth.
        side = 1 if noise(k, 3, salt) < 0.5 else -1
        bx = max(2, min(SIZE - 3, x + 2 * side))
        image[top - 1][x + side] = opaque(STONEBELL_STEM[1])
        image[top - 1][bx] = opaque(STONEBELL_STEM[1])
        for dy, (left, right) in enumerate(((0, 0), (0, 1), (-1, 1))):
            for dx in range(left, right + 1):
                level = 2 if dx <= 0 and dy < 2 else 1 if dy < 2 else 0
                image[top + dy][bx + dx] = opaque(STONEBELL_BELL[level])
    return image


# Half-widths (pixels) of a spike at its pointed end and its broad end, by thickness.
WIDTHS = {
    "tip": (0.4, 2.2),
    "tip_merge": (0.9, 1.4),
    "frustum": (2.2, 3.4),
    "middle": (3.4, 4.6),
    "base": (4.6, 6.2),
}


def spike(
    image: Image, top: float, bottom: float, start: int, salt: int, material: str = "dripstone"
) -> None:
    """Paint a tapering column whose half-width runs from top (row 0) to bottom (row 15)."""
    for y in range(start, SIZE):
        t = (y - start) / max(1, SIZE - 1 - start)
        half = top + (bottom - top) * t + 0.35 * (noise(0, y, salt) - 0.5)
        for x in range(SIZE):
            d = abs(x + 0.5 - SIZE / 2)
            if d > half:
                continue
            # Lit from the left: the left flank is pale, the right flank and rim dark.
            side = (SIZE / 2 - (x + 0.5)) / max(half, 0.5)
            rim = 0.25 if half - d < 0.8 else 0.0
            level = 0.45 + 0.3 * side + 0.25 * (noise(x, y, salt) - 0.5) - rim
            image[y][x] = shade(level, material)


def pointed_dripstone(direction: str, thickness: str) -> Image:
    """A pointed dripstone piece, drawn upright and flipped for stalactites."""
    image = blank()
    top, bottom = WIDTHS[thickness]
    start = 3 if thickness == "tip" else 0
    spike(image, top, bottom, start, salt=len(thickness) * 7 + 1)
    return image[::-1] if direction == "down" else image


def pointed_dripstone_item() -> Image:
    """Inventory icon: a whole stalagmite from base to point."""
    image = blank()
    spike(image, 0.4, 4.4, 1, salt=29)
    return image


def pointed(material: str, direction: str, thickness: str) -> Image:
    """A pointed formation piece of another material (pointed_dripstone's shape)."""
    image = blank()
    top, bottom = WIDTHS[thickness]
    start = 3 if thickness == "tip" else 0
    spike(image, top, bottom, start, salt=len(thickness) * 7 + len(material), material=material)
    return image[::-1] if direction == "down" else image


def pointed_item(material: str) -> Image:
    """Inventory icon of a pointed formation material."""
    image = blank()
    spike(image, 0.4, 4.4, 1, salt=29 + len(material), material=material)
    return image


def ore_overlay(shine: str) -> Image:
    """Grey mineral grains for a tinted overlay: clustered specks, with highlights when shiny."""
    image = blank()
    for k in range(7):
        cx, cy = int(noise(k, 0, 41) * 14) + 1, int(noise(k, 1, 41) * 14) + 1
        size = 2 + int(noise(k, 2, 41) * 3)
        for i in range(size + 2):
            x = cx + int(noise(k, i, 42) * 3) - 1
            y = cy + int(noise(k, i, 43) * 3) - 1
            if not (0 <= x < SIZE and 0 <= y < SIZE):
                continue
            level = 150 + int(70 * noise(x, y, 44))
            if shine == "shiny" and noise(x, y, 45) > 0.7:
                level = 255
            image[y][x] = (level, level, level, 255)
    return image


# Unstable stone: a crack network that leaves each edge where it enters the opposite one, so a
# floor of blocks reads as one crazed surface. Polylines of (x, y) points; each crack is dark with
# a mid-tone lip below (or beside) it, which lights it on dark hosts and shades it on pale ones.
CRACKS = [
    [(0, 6), (3, 5), (6, 6), (8, 8), (11, 8), (13, 6), (15, 6)],
    [(9, 0), (9, 2), (8, 4), (8, 8)],
    [(8, 8), (6, 11), (7, 13), (9, 15)],
    [(11, 8), (12, 11), (14, 12)],
    [(3, 5), (2, 2)],
]
CRACK: Pixel = (28, 25, 23, 255)
CRACK_LIP: Pixel = (138, 129, 118, 255)


def line(a: tuple[int, int], b: tuple[int, int]) -> list[tuple[int, int]]:
    """The pixels of a straight segment from a to b (Bresenham)."""
    (x0, y0), (x1, y1) = a, b
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x1 > x0 else -1), (1 if y1 > y0 else -1)
    err, out = dx + dy, []
    while True:
        out.append((x0, y0))
        if (x0, y0) == (x1, y1):
            return out
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def unstable_overlay() -> Image:
    """Cracks over any host rock, untinted; opaque pixels only, for a cutout layer."""
    image = blank()
    segments = [
        (line(a, b), abs(b[0] - a[0]) >= abs(b[1] - a[1]))
        for crack in CRACKS
        for a, b in zip(crack, crack[1:])
    ]
    for points, _ in segments:
        for x, y in points:
            image[y][x] = CRACK
    for points, across in segments:
        for x, y in points:
            lx, ly = (x, y + 1) if across else (x + 1, y)
            if 0 <= lx < SIZE and 0 <= ly < SIZE and image[ly][lx] == CLEAR:
                image[ly][lx] = CRACK_LIP
    return image


PAINTERS: dict[str, Callable[..., Image]] = {
    "ore_overlay": ore_overlay,
    "unstable_overlay": unstable_overlay,
    "dripstone_block": dripstone_block,
    "pointed_dripstone": pointed_dripstone,
    "pointed_dripstone_item": pointed_dripstone_item,
    "pointed": pointed,
    "pointed_item": pointed_item,
    "sediment": sediment,
    "fungus": fungus,
    "roots": roots,
    "biofilm": biofilm,
    "stonebell": stonebell,
}
