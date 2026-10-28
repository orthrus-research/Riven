"""Check or update golden terrain-atlas digests for a few fixed windows.

Each window in `tools/atlas-golden.json` is rendered from the working tree through
`terrain_atlas.py render` and its SHA-256 layer digest is compared with the stored value. A
mismatch means generated content changed: either an unintended regression, or an intended change
whose new digests belong in the same commit (`update`). Run with the atlas venv interpreter.

  .local/atlas-venv/Scripts/python tools/atlas_golden.py check
  .local/atlas-venv/Scripts/python tools/atlas_golden.py update
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "tools/atlas-golden.json"
OUT = ROOT / ".local/terrain-atlas/golden"


def render(window: dict, verify: bool) -> str:
    out = OUT / window["name"]
    command = [
        sys.executable,
        str(ROOT / "tools/terrain_atlas.py"),
        "render",
        "--seed",
        str(window["seed"]),
        "--center",
        str(window["center"][0]),
        str(window["center"][1]),
        "--size",
        str(window["size"]),
        "--step",
        str(window["step"]),
        "--footprint",
        str(window.get("footprint", 1)),
        "--scale",
        str(window.get("scale", 1)),
        "--out",
        str(out),
    ] + (["--verify"] if verify else [])
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"render failed for {window['name']}:\n{result.stdout}{result.stderr}")
    return json.loads((out / "raw/atlas.json").read_text(encoding="utf-8"))["digest"]


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("action", choices=("check", "update"))
    parser.add_argument(
        "--verify", action="store_true", help="also prove thread/order independence"
    )
    args = parser.parse_args()
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
    changed = []
    for window in golden["windows"]:
        digest = render(window, args.verify)
        expected = window.get("digest")
        status = "ok" if digest == expected else ("new" if expected is None else "CHANGED")
        print(
            f"{window['name']:<24} {status:<8} {digest[:16]}"
            + (f"  (golden {expected[:16]})" if status == "CHANGED" else "")
        )
        if digest != expected:
            changed.append(window["name"])
            window["digest"] = digest
    if args.action == "update":
        GOLDEN.write_text(json.dumps(golden, indent=2) + "\n", encoding="utf-8")
        print(f"updated {len(changed)} digest(s) in {GOLDEN.relative_to(ROOT)}")
        return
    if changed:
        sys.exit(
            "Generated terrain changed in: " + ", ".join(changed) + ". If intended, run "
            "`atlas_golden.py update` and commit the new digests with the change; compare "
            "first with `terrain_atlas.py compare --ref HEAD`."
        )
    print("Golden atlas digests match.")


if __name__ == "__main__":
    main()
