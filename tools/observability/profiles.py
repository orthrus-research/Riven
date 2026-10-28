"""Validate packaged trace targets and produce a bounded diagnostic JFR profile."""

import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    path = Path(path)
    if path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("JSON exceeds the 64 MiB evidence limit: " + str(path))
    return json.loads(path.read_text(encoding="utf-8"))


def class_members(data):
    """Read names from a class file without loading or executing mod classes."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a JVM class")
    offset = 8

    def number(size):
        nonlocal offset
        if offset + size > len(data):
            raise ValueError("Truncated class file")
        value = int.from_bytes(data[offset : offset + size], "big")
        offset += size
        return value

    def skip(count):
        nonlocal offset
        if count < 0 or offset + count > len(data):
            raise ValueError("Truncated class file")
        offset += count

    constants, count, index = {}, number(2), 1
    while index < count:
        tag = number(1)
        if tag == 1:
            length = number(2)
            # Method names use ASCII; replacement only affects unrelated string literals.
            constants[index] = data[offset : offset + length].decode("utf-8", errors="replace")
            skip(length)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            skip(4)
        elif tag in (5, 6):
            skip(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            skip(2)
        elif tag == 15:
            skip(3)
        else:
            raise ValueError("Unsupported constant-pool tag: " + str(tag))
        index += 1
    skip(6)
    skip(2 * number(2))

    def members():
        result = set()
        for _ in range(number(2)):
            number(2)
            name = number(2)
            number(2)
            result.add(constants.get(name))
            for _ in range(number(2)):
                number(2)
                skip(number(4))
        return result

    fields = members()
    return fields, members()


def class_methods(data):
    return class_members(data)[1]


def packet_policy_capabilities(cubature):
    path = Path(cubature)
    with zipfile.ZipFile(path) as jar:
        fields, _ = class_members(
            jar.read("io/github/opencubicchunks/cubicchunks/core/CubicChunksConfig.class")
        )
    configurable = "maxCubePacketCubes" in fields
    # This exact accepted source/artifact uses a fixed 1024-cube loop. Unknown binaries
    # do not inherit that fact merely because their configurable field is absent.
    fixed = (
        1024
        if not configurable
        and sha(path) == "06992e55d2e947b16f1afdc14623ecad845616ac156123447d6f8fa9a6043283"
        else None
    )
    return {"configurableCubeCount": configurable, "knownFixedCubeCap": fixed}


def configuration():
    value = read_json(ROOT / "dev/observability.json")
    if value["schema"] != 1 or not 1 <= len(value["targets"]) <= 64:
        raise ValueError("Unsupported observability configuration")
    return value


def doctor(riven, cubature=None, observer=None, check_java=True):
    pins = read_json(ROOT / "runtime.lock.json")
    cc_pin = read_json(ROOT / "dev/cc.lock.json")
    settings = read_json(ROOT / ".local/settings.json")
    sources = {
        "riven": Path(riven).resolve(),
        "cubature": Path(cubature).resolve() if cubature else ROOT / cc_pin["artifact"],
        "dh": ROOT / next(m["file"] for m in pins["mods"] if "DistantHorizons-" in m["file"]),
    }
    observer = (
        Path(observer).resolve()
        if observer
        else ROOT / "dev/CubicChunks/lab/build/cleanroom/libs/cubicchunks-lab-0.1.0-cleanroom.jar"
    )
    checks, artifacts = [], {}
    for mod in pins["mods"]:
        path = ROOT / mod["file"]
        checks.append(
            {
                "check": "accepted-pin",
                "file": mod["file"],
                "passed": path.is_file() and sha(path) == mod["sha256"],
            }
        )
    for name, path in {**sources, "observer": observer}.items():
        artifacts[name] = {"file": str(path), "sha256": sha(path) if path.is_file() else None}
        checks.append({"check": "selected-artifact", "name": name, "passed": path.is_file()})
    for owner, path in sources.items():
        if not path.is_file():
            continue
        with zipfile.ZipFile(path) as jar:
            for target in configuration()["targets"]:
                if target["owner"] != owner:
                    continue
                member = target["class"].replace(".", "/") + ".class"
                names = class_methods(jar.read(member)) if member in jar.namelist() else set()
                checks.append(
                    {
                        "check": "trace-target",
                        "target": target["class"] + "::" + target["method"],
                        "stage": target["stage"],
                        "passed": target["method"] in names,
                    }
                )
    if observer.is_file():
        with zipfile.ZipFile(observer) as jar:
            for name in [
                "LabThreadCosts",
                "LabCorePlugin",
                "LabRenderDiagnostics",
                "LabFullViewRenderReady",
            ]:
                checks.append(
                    {
                        "check": "observer-capability",
                        "name": name,
                        "passed": "io/github/opencubicchunks/cubicchunks/lab/" + name + ".class"
                        in jar.namelist(),
                    }
                )
            if sources["cubature"].is_file():
                policy = packet_policy_capabilities(sources["cubature"])
                checks.append(
                    {
                        "check": "observer-packet-policy-compatibility",
                        "passed": policy["configurableCubeCount"]
                        or "io/github/opencubicchunks/cubicchunks/lab/LabPacketPolicy.class"
                        in jar.namelist(),
                    }
                )
    java_version = None
    if check_java:
        process = subprocess.run(
            [settings["java26"], "-version"], capture_output=True, text=True, timeout=20
        )
        java_version = (process.stdout + process.stderr).strip()
        checks.append(
            {
                "check": "java26",
                "passed": process.returncode == 0 and 'version "26.' in java_version,
            }
        )
    return {
        "schema": 1,
        "passed": all(c["passed"] for c in checks),
        "checks": checks,
        "artifacts": artifacts,
        "java": settings["java26"],
        "javaVersion": java_version,
        "limits": [
            "Packaged method presence does not prove runtime transformation or observed activity.",
            "No native performance or visual qualification is implied by doctor success.",
        ],
    }


def write_profile(path):
    config = configuration()
    root = ET.Element(
        "configuration",
        version="2.0",
        label="Riven pipeline diagnostics",
        description="Optional Java 26 method and sample diagnostics; timings include observer overhead.",
        provider="Riven",
    )
    settings = {
        "jdk.MethodTrace": {
            "threshold": config["methodThreshold"],
            "stackTrace": "true",
            "filter": ";".join(t["class"] + "::" + t["method"] for t in config["targets"]),
        },
        "jdk.ExecutionSample": {"period": "10 ms"},
        "jdk.NativeMethodSample": {"period": "10 ms"},
        "jdk.ObjectAllocationSample": {"throttle": "150/s", "stackTrace": "true"},
        "jdk.GCPhasePause": {},
        "jdk.GarbageCollection": {},
        "jdk.DataLoss": {},
        "jdk.ZAllocationStall": {"stackTrace": "true"},
    }
    for name in [
        "ThreadPark",
        "ThreadSleep",
        "JavaMonitorEnter",
        "JavaMonitorWait",
        "FileRead",
        "FileWrite",
        "FileForce",
        "SafepointBegin",
        "ExecuteVMOperation",
    ]:
        settings["jdk." + name] = {"threshold": "1 ms", "stackTrace": "true"}
    for name, values in settings.items():
        event = ET.SubElement(root, "event", name=name)
        for key, value in {"enabled": "true", **values}.items():
            ET.SubElement(event, "setting", name=key).text = value
    ET.indent(root)
    with Path(path).open("xb") as stream:
        stream.write(ET.tostring(root, encoding="utf-8", xml_declaration=True))
