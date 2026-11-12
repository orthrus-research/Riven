"""Check or update golden terrain-atlas digests for a few fixed windows.

Each window in `tools/atlas-golden.json` is sampled from the working tree and its SHA-256 layer
digest is compared with the stored value. A mismatch means generated content changed: either an
unintended regression, or an intended change whose new digests belong in the same commit
(`update`). Run with the atlas venv interpreter.

All windows sample in one sampler JVM, `--jobs` at a time in the file's order, and windows of one
seed and scale share their regional plans, which dominate the cost of these small windows. A
window's raw samples are reused when the compiled sources and window are unchanged, as
`terrain_atlas.py render` does.

`--verify` then samples every window again in a second JVM: the list reversed, each window's tiles
in reverse order, on another thread count. Its digests must equal the first pass's, which proves
a window's samples depend neither on threads and order nor on which windows ran before it. Verify
never reuses samples.

`--profile` resamples everything and times a second pass of each window over its built plans, so
the gap estimates plan building; with or without it, timing.json beside the renders records each
pass, window and paint.

  .local/atlas-venv/Scripts/python tools/atlas_golden.py check --verify
  .local/atlas-venv/Scripts/python tools/atlas_golden.py update
"""

import argparse
import concurrent.futures
import json
import os
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from atlas.build import Source, prepare, run_batch  # noqa: E402
from atlas.paint import Atlas, paint  # noqa: E402
from atlas.viewer import write_viewer  # noqa: E402
from terrain_atlas import paint_index_link  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "tools/atlas-golden.json"
OUT = ROOT / ".local/terrain-atlas/golden"
THRESHOLD = 1.0
# More threads than physical cores slowed sampling (31: 18 s, 16: 8.5 s on 8675309).
FORWARD_THREADS = max(1, (os.cpu_count() or 2) // 2)


def options(window: dict, threads: int) -> dict:
    """The sampler arguments `terrain_atlas.py render` would pass for this window."""
    pixels = window["size"] // window["step"]
    scale = window.get("scale", 1)
    return {
        "seed": window["seed"],
        "cx": window["center"][0],
        "cz": window["center"][1],
        "width": pixels,
        "height": pixels,
        "step": window["step"],
        "footprint": window.get("footprint", 1),
        "threads": threads,
        "sites": "true",
        "scale": scale if scale != 1 else None,
    }


def spec(classes: Path, opts: dict) -> dict:
    """What raw samples depend on; threads and timing passes are proven not to matter."""
    key = {k: v for k, v in opts.items() if k not in ("threads", "repeat", "order")}
    return {"classes": classes.name, "sets": [], "options": key}


def digest_of(raw: Path) -> str:
    return json.loads((raw / "atlas.json").read_text(encoding="utf-8"))["digest"]


def seconds_of(raw: Path) -> dict:
    return json.loads((raw / "atlas.json").read_text(encoding="utf-8")).get("seconds", {})


def verify_threads() -> int:
    threads = max(2, (os.cpu_count() or 4) // 4)
    return threads + 1 if threads == FORWARD_THREADS else threads


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("action", choices=("check", "update"))
    parser.add_argument(
        "--verify", action="store_true", help="also prove thread/order independence"
    )
    parser.add_argument("--jobs", type=int, default=3, help="windows sampled at once (3)")
    parser.add_argument("--resample", action="store_true", help="ignore reusable raw samples")
    parser.add_argument(
        "--profile", action="store_true", help="resample and time a second pass per window"
    )
    args = parser.parse_args()
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
    windows = golden["windows"]
    run_started = time.monotonic()
    started = time.monotonic()
    classes = prepare(Source())[1]
    timing: dict = {"compile": round(time.monotonic() - started, 1), "windows": {}}

    forward = []
    reused = []
    for window in windows:
        out = OUT / window["name"]
        opts = options(window, FORWARD_THREADS) | ({"repeat": "true"} if args.profile else {})
        spec_file = out / "raw/spec.json"
        if (
            not (args.resample or args.profile)
            and spec_file.exists()
            and json.loads(spec_file.read_text(encoding="utf-8")) == spec(classes, opts)
        ):
            reused.append(window["name"])
            continue
        if out.exists():
            shutil.rmtree(out)
        forward.append((window, out, opts))
    if forward:
        seconds = run_batch(
            classes,
            [(out / "raw", opts) for _, out, opts in forward],
            args.jobs,
            OUT / "forward.log",
        )
        for _, out, opts in forward:
            (out / "raw/spec.json").write_text(json.dumps(spec(classes, opts)), encoding="utf-8")
        timing["forward"] = {"seconds": round(seconds, 1), "windows": len(forward)}
    timing["reused"] = reused
    digests = [digest_of(OUT / w["name"] / "raw") for w in windows]

    if args.verify:
        threads = verify_threads()
        checks = [
            (OUT / w["name"] / "verify", options(w, threads) | {"order": "reverse"})
            for w in reversed(windows)
        ]
        for out, _ in checks:
            if out.exists():
                shutil.rmtree(out)
        seconds = run_batch(classes, checks, args.jobs, OUT / "verify.log")
        timing["verify"] = {"seconds": round(seconds, 1), "windows": len(checks)}
        failures = []
        for window, digest in zip(windows, digests):
            check = OUT / window["name"] / "verify"
            other = digest_of(check)
            timing["windows"].setdefault(window["name"], {})["verify"] = seconds_of(check)
            record = {"digest": digest, "verifyDigest": other, "threads": threads}
            record |= {"order": "reverse", "windowOrder": "reverse"}
            (OUT / window["name"] / "determinism.json").write_text(
                json.dumps(record, indent=2), encoding="utf-8"
            )
            shutil.rmtree(check)
            if other != digest:
                failures.append(f"{window['name']}: {digest} != {other}")
        if failures:
            sys.exit(
                f"DETERMINISM FAILURE (threads {threads}, reverse tile and window order):\n  "
                + "\n  ".join(failures)
            )
        print(
            f"determinism verified: {len(windows)} windows in reverse window and tile order on "
            f"{threads} threads give the same digests"
        )

    def paint_window(window):
        out = OUT / window["name"]
        began = time.monotonic()
        atlas = Atlas(out / "raw")
        paint(atlas, out, THRESHOLD)
        write_viewer(atlas, out, THRESHOLD)
        paint_index_link(out)
        return time.monotonic() - began

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        painted = list(pool.map(paint_window, windows))
    timing["paint"] = round(time.monotonic() - started, 1)
    for window, seconds in zip(windows, painted):
        entry = timing["windows"].setdefault(window["name"], {})
        entry["forward"] = seconds_of(OUT / window["name"] / "raw")
        entry["paint"] = round(seconds, 1)
    timing["seconds"] = round(time.monotonic() - run_started, 1)
    (OUT / "timing.json").write_text(json.dumps(timing, indent=2), encoding="utf-8")
    # tools/actions.py keeps "timing " lines in the run summary for tools/ci_timing.py.
    print("timing " + json.dumps({"atlasGolden": timing}))

    changed = []
    for window, digest in zip(windows, digests):
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
