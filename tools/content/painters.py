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
}
