"""Game overlays for the map site: spawn, tour views and recorded flight paths.

`map --overlay SOURCE` reads one source into the render's overlay.json:
  - a tour file (tools/tours/*.json): each view's label and position, a "tour" point;
  - a capture's result.json, or an instance directory holding runs/*/result.json: the client's
    recorded poses (each scenario's spectatorFlight observations, about one a second), a "flight"
    path carrying the capture's workload seed;
  - a world's level.dat, or a world or instance directory holding one: the world spawn, a "spawn"
    point carrying the world's seed;
  - an overlay file of this module's own shape ({"points": [...], "paths": [...]}).
Items from one source replace that source's earlier items. The page draws an item only when it
has no seed or the render's seed.
"""

import gzip
import json
import struct
from pathlib import Path

KINDS = ("spawn", "tour", "flight")


def read_source(path: Path) -> dict:
    """The overlay items one source gives, as {"points": [...], "paths": [...]}."""
    if path.is_dir():
        found: dict[str, list[dict]] = {"points": [], "paths": []}
        for level in level_files(path):
            found["points"] += spawn_points(level)
        for result in sorted(path.glob("runs/*/result.json")):
            found["paths"] += flight_paths(result)
        if not found["points"] and not found["paths"]:
            raise ValueError(f"{path}: no level.dat or runs/*/result.json inside")
        return found
    if path.name == "level.dat":
        return {"points": spawn_points(path), "paths": []}
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict) and "scenarios" in data:
        return {"points": [], "paths": flight_paths(path, data)}
    if isinstance(data, list) or (isinstance(data, dict) and "views" in data):
        return {"points": tour_points(path, data), "paths": []}
    if isinstance(data, dict) and ("points" in data or "paths" in data):
        return checked(data, path)
    raise ValueError(f"{path}: not a tour, capture result, level.dat or overlay file")


def level_files(directory: Path) -> list[Path]:
    """level.dat of a world directory, or of the server world inside an instance directory."""
    candidates = [directory / "level.dat", directory / "world/level.dat"]
    candidates.append(directory / "server/world/level.dat")
    return [c for c in candidates if c.is_file()][:1]


def tour_points(path: Path, data) -> list[dict]:
    views = data["views"] if isinstance(data, dict) else data
    source = f"tour {path.stem}"
    # Tour files are named <name>-<seed>.json.
    tail = path.stem.rsplit("-", 1)[-1]
    seed = {"seed": int(tail)} if tail.lstrip("-").isdigit() and "-" in path.stem else {}
    return [
        seed
        | {
            "kind": "tour",
            "x": round(float(v["x"]), 1),
            "z": round(float(v["z"]), 1),
            "y": round(float(v["y"]), 1),
            "yaw": float(v.get("yaw", 0)),
            "label": str(v.get("label") or f"view {i + 1}"),
            "source": source,
        }
        for i, v in enumerate(views)
    ]


def flight_paths(path: Path, data=None) -> list[dict]:
    """The client's recorded poses across a capture's scenarios, in order, repeats dropped."""
    data = data if data is not None else json.loads(path.read_text(encoding="utf-8"))
    points: list[list[float]] = []
    for scenario in data.get("scenarios", []):
        flight = (scenario.get("client") or {}).get("spectatorFlight") or {}
        for observation in flight.get("observations", []):
            x, y, z = observation["pose"][:3]
            point = [round(float(x), 1), round(float(z), 1), round(float(y), 1)]
            if not points or points[-1] != point:
                points.append(point)
    if len(points) < 2:
        return []
    workload = data.get("benchmarkWorkload") or {}
    # result.json sits in <instance>/runs/<phase>/.
    instance = path.parent.parent.parent.name if path.parent.parent.name == "runs" else path.stem
    item: dict = {
        "kind": "flight",
        "label": f"{workload.get('id', 'flight')} ({instance})",
        "points": points,
        "source": f"capture {instance}",
    }
    if "seed" in workload:
        item["seed"] = int(workload["seed"])
    return [item]


def spawn_points(path: Path) -> list[dict]:
    data = read_nbt(path).get("Data", {})
    if not all(k in data for k in ("SpawnX", "SpawnY", "SpawnZ")):
        raise ValueError(f"{path}: no SpawnX/SpawnY/SpawnZ")
    world = path.parent
    name = world.parent.parent.name if world.parent.name == "server" else world.name
    point = {
        "kind": "spawn",
        "x": int(data["SpawnX"]),
        "z": int(data["SpawnZ"]),
        "y": int(data["SpawnY"]),
        "label": "world spawn",
        "source": f"world {name}",
    }
    if "RandomSeed" in data:
        point["seed"] = int(data["RandomSeed"])
    return [point]


def checked(data: dict, path: Path) -> dict:
    """An overlay file's own items, with the fields the page needs."""
    points, paths = data.get("points", []), data.get("paths", [])
    for p in points:
        if p.get("kind") not in KINDS or not {"x", "z", "label"} <= set(p):
            raise ValueError(f"{path}: a point needs kind (one of {KINDS}), x, z and label")
    for p in paths:
        if p.get("kind") not in KINDS or "label" not in p or len(p.get("points", [])) < 2:
            raise ValueError(f"{path}: a path needs kind, label and two or more points")
    source = f"file {path.stem}"
    return {
        "points": [{"source": source} | p for p in points],
        "paths": [{"source": source} | p for p in paths],
    }


def merge(current: dict, added: dict) -> dict:
    """Items of the added sources replace those sources' earlier items."""
    sources = {i["source"] for i in added["points"] + added["paths"]}
    return {
        key: [i for i in current.get(key, []) if i["source"] not in sources] + added[key]
        for key in ("points", "paths")
    }


# A minimal reader for Minecraft's gzipped NBT: enough for level.dat's Data compound.
def read_nbt(path: Path) -> dict:
    data = gzip.decompress(path.read_bytes())
    tag, offset = data[0], 1
    if tag != 10:
        raise ValueError(f"{path}: NBT root is not a compound")
    _, offset = string(data, offset)
    value, _ = payload(data, offset, 10)
    return value


def string(data: bytes, offset: int) -> tuple[str, int]:
    (n,) = struct.unpack_from(">H", data, offset)
    return data[offset + 2 : offset + 2 + n].decode("utf-8", "replace"), offset + 2 + n


FIXED = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
ARRAYS = {7: ">b", 11: ">i", 12: ">q"}


def payload(data: bytes, offset: int, tag: int):
    if tag in FIXED:
        (value,) = struct.unpack_from(FIXED[tag], data, offset)
        return value, offset + struct.calcsize(FIXED[tag])
    if tag in ARRAYS:
        (n,) = struct.unpack_from(">i", data, offset)
        size = struct.calcsize(ARRAYS[tag])
        offset += 4
        return list(struct.unpack_from(f">{n}{ARRAYS[tag][1]}", data, offset)), offset + n * size
    if tag == 8:
        return string(data, offset)
    if tag == 9:
        inner, (n,) = data[offset], struct.unpack_from(">i", data, offset + 1)
        offset += 5
        items = []
        for _ in range(n):
            item, offset = payload(data, offset, inner)
            items.append(item)
        return items, offset
    if tag == 10:
        compound = {}
        while data[offset] != 0:
            inner = data[offset]
            name, offset = string(data, offset + 1)
            compound[name], offset = payload(data, offset, inner)
        return compound, offset + 1
    raise ValueError(f"unknown NBT tag {tag}")
