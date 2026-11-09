"""Where Riven finds the Cubature checkout it builds and tests against.

dev/cc.lock.json's "checkout" names it relative to the main Riven checkout (a sibling clone,
../Cubature). A linked worktree, wherever it sits, resolves it from the main checkout its .git
file names, so every worktree finds the same Cubature without a link of its own. Importing this
module also exports RIVEN_WORKSPACE, so Cubature's tools, whether imported here or run as child
processes, find this Riven root instead of guessing from their own location.
"""

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main_checkout(root: Path = ROOT) -> Path:
    """The main checkout of root's repository: root itself, or the one a linked worktree names."""
    marker = root / ".git"
    if not marker.is_file():
        return root
    gitdir = Path(marker.read_text(encoding="utf-8").split("gitdir:", 1)[1].strip())
    gitdir = gitdir if gitdir.is_absolute() else (root / gitdir).resolve()
    common = gitdir / "commondir"
    shared = (
        (gitdir / common.read_text(encoding="utf-8").strip()).resolve()
        if common.is_file()
        else gitdir
    )
    return shared.parent


def cubature_checkout(root: Path = ROOT) -> Path:
    """The Cubature checkout that root's dev/cc.lock.json names."""
    lock = json.loads((root / "dev/cc.lock.json").read_text(encoding="utf-8"))
    return (main_checkout(root) / lock["checkout"]).resolve()


CUBATURE = cubature_checkout()
CUBATURE_TOOLS = CUBATURE / "tools"
LAB_OBSERVER = CUBATURE / "lab/build/cleanroom/libs/cubicchunks-lab-0.1.0-cleanroom.jar"

os.environ.setdefault("RIVEN_WORKSPACE", str(ROOT))
