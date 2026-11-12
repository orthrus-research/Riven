"""Keep generated content on platform-independent math: Math calls in Riven's Java must be exact.

`java.lang.Math` may use CPU- and JDK-specific intrinsics for transcendental functions (sin, cos,
exp, pow, atan2, tanh, cbrt, log...), which the specification lets differ from `StrictMath` by an
ulp. HotSpot keeps one JVM consistent with itself, so `--verify` cannot see the difference, but
another CPU or JDK build can generate a different world. `StrictMath` is bit-identical everywhere.

Only `Math` methods whose result the specification fixes exactly (min, floor, sqrt, ...) are
allowed. Existing calls are recorded per file in tools/strict-math-baseline.json, a ratchet: a
file may not add calls beyond its baseline. Converting a recorded call to `StrictMath` can change
generated terrain, so it belongs in a commit that updates the golden digests.

  python tools/strict_math.py            # check against the baseline
  python tools/strict_math.py --update   # rewrite the baseline after removing calls
"""

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "src/main/java"
BASELINE = ROOT / "tools/strict-math-baseline.json"

# Math methods whose results the Java specification defines exactly, so every platform agrees.
EXACT = frozenset(
    """abs absExact addExact ceil ceilDiv ceilDivExact ceilMod clamp copySign decrementExact
    floor floorDiv floorDivExact floorMod fma getExponent incrementExact max min multiplyExact
    multiplyFull multiplyHigh negateExact nextAfter nextDown nextUp rint round scalb signum sqrt
    subtractExact toDegrees toIntExact toRadians ulp unsignedMultiplyHigh""".split()
)
# Fields such as Math.PI and Math.E are constants and always allowed.
CALL = re.compile(r"(?<![\w.])(?:java\s*\.\s*lang\s*\.\s*)?Math\s*(?:\.\s*(\w+)\s*\(|::\s*(\w+))")
STATIC_IMPORT = re.compile(r"^[ \t]*import\s+static\s+java\.lang\.Math\.", re.M)
COMMENT_OR_STRING = re.compile(
    r'//[^\n]*|/\*.*?\*/|"""(?:\\.|[^\\])*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S
)


def strip(text: str) -> str:
    """Code with comments and literals blanked, so mentions in them are not calls."""
    return COMMENT_OR_STRING.sub(lambda m: re.sub(r"[^\n]", " ", m.group()), text)


def inexact_calls(text: str) -> tuple[Counter[str], list[int]]:
    """Inexact Math methods called in Java source text, and lines that import Math statically."""
    code = strip(text)
    calls: Counter[str] = Counter()
    for match in CALL.finditer(code):
        name = match.group(1) or match.group(2)
        if name not in EXACT:
            calls[name] += 1
    imports = [code.count("\n", 0, m.start()) + 1 for m in STATIC_IMPORT.finditer(code)]
    return calls, imports


def scan(sources: Path = SOURCES) -> tuple[dict[str, dict[str, int]], list[str]]:
    """Inexact calls by repository-relative file, and static imports of Math to report."""
    found: dict[str, dict[str, int]] = {}
    imports = []
    for path in sorted(sources.rglob("*.java")):
        relative = path.relative_to(ROOT).as_posix()
        calls, lines = inexact_calls(path.read_text(encoding="utf-8"))
        if calls:
            found[relative] = dict(sorted(calls.items()))
        imports += [f"{relative}:{line}" for line in lines]
    return found, imports


def compare(
    found: dict[str, dict[str, int]], baseline: dict[str, dict[str, int]]
) -> tuple[list[str], list[str]]:
    """(calls beyond the baseline, baseline entries no longer used)."""
    added, spare = [], []
    for file in sorted(set(found) | set(baseline)):
        now, allowed = found.get(file, {}), baseline.get(file, {})
        for name in sorted(set(now) | set(allowed)):
            n, a = now.get(name, 0), allowed.get(name, 0)
            if n > a:
                added.append(f"{file}: Math.{name} x{n - a} (use StrictMath.{name})")
            elif n < a:
                spare.append(f"{file}: Math.{name} {a} -> {n}")
    return added, spare


def load_baseline() -> dict[str, dict[str, int]]:
    if not BASELINE.is_file():
        return {}
    return json.loads(BASELINE.read_text(encoding="utf-8"))["files"]


def write_baseline(found: dict[str, dict[str, int]]) -> None:
    document = {
        "comment": "Inexact java.lang.Math calls that predate tools/strict_math.py, by file. "
        "Converting one to StrictMath can change generated terrain; shrink with --update.",
        "files": found,
    }
    BASELINE.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--update", action="store_true", help="record the current calls")
    args = parser.parse_args()
    found, imports = scan()
    if imports:
        sys.exit(
            "Static imports of java.lang.Math hide which methods are called; use Math. or "
            "StrictMath. explicitly:\n  " + "\n  ".join(imports)
        )
    if args.update:
        added, _ = compare(found, load_baseline())
        # The first baseline records what exists; later updates may only shrink it.
        if added and BASELINE.is_file():
            sys.exit(
                "--update only shrinks the baseline; new inexact calls must use StrictMath:\n  "
                + "\n  ".join(added)
            )
        write_baseline(found)
        total = sum(sum(c.values()) for c in found.values())
        print(f"strict math: baseline holds {total} call(s) in {len(found)} file(s)")
        return
    added, spare = compare(found, load_baseline())
    if added:
        sys.exit(
            "Inexact java.lang.Math calls can differ by CPU and JDK and so change the world on "
            "another machine; use StrictMath:\n  " + "\n  ".join(added)
        )
    if spare:
        print(
            "strict math: fewer recorded calls remain; run tools/strict_math.py --update:\n  "
            + "\n  ".join(spare)
        )
    print("strict math: no new inexact Math calls")


if __name__ == "__main__":
    main()
