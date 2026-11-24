"""Where Riven's tools find Cubature's lab tools: the tools/ folder of one Cubature commit.

- In a driver session (wb play --driver <name>, which exports WB_SESSION and WB_LAUNCH), the tools are exported from
  Cubature's repository at the commit the session's launch names for Cubature, into the session folder, so a driver
  runs the lab at the bytes its instance was built with. The repository is CUBATURE_REPOSITORY, else the Cubature
  clone beside Riven's main checkout; only its objects are read, never a working tree.
- In a check, Workbench lays them beside the clone at the commit the set names (project.toml's
  tools = ["cubature:tools/**", ...]), where a Cubature checkout beside Riven would be.
- CUBATURE_TOOLS names another folder.

Importing this module puts the folder on sys.path after Riven's own tools, so a Cubature module of the same name
never stands in for Riven's. The lab observer a measurement adds comes from the Store, built at the same commit.
"""

import io
import json
import os
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPORT = "cubature-tools"
COMMIT_FILE = ".commit"
OBSERVER = "lab/build/*/libs/cubicchunks-lab-*-cleanroom.jar"


def launch_commit(launch):
    """The Cubature commit a launch file names: its Cubature mod member's."""
    found = [m for m in launch.get("members") or [] if m.get("name") == "cubature"]
    commits = {m.get("commit") for m in found if m.get("commit")}
    if len(commits) != 1:
        raise RuntimeError("The launch names no single Cubature commit")
    return commits.pop()


def main_checkout(root=ROOT):
    """The main checkout of root's repository (root itself unless root is a linked worktree)."""
    common = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return Path(common).parent


def repository(environ=os.environ):
    named = environ.get("CUBATURE_REPOSITORY")
    return Path(named) if named else (main_checkout() / ".." / "Cubature").resolve()


def export(commit, target, repo):
    """Cubature's tools/ at commit, from repo's objects, into target (kept: a second export is a no-op)."""
    marker = target / COMMIT_FILE
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == commit:
        return target / "tools"
    if target.exists():
        raise RuntimeError(f"{target} holds another Cubature export; use a new session")
    done = subprocess.run(
        ["git", "-C", str(repo), "archive", "--format=tar", commit, "--", "tools"],
        capture_output=True,
        check=False,
    )
    if done.returncode != 0:
        raise RuntimeError(
            f"Cubature {commit[:12]} has no tools in {repo}: "
            + done.stderr.decode(errors="replace").strip()[-300:]
        )
    target.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(done.stdout)) as archive:
        archive.extractall(target, filter="data")
    marker.write_text(commit + "\n", encoding="utf-8")
    return target / "tools"


def find(environ=os.environ):
    """(tools folder, the Cubature commit it is at or None when unknown)."""
    if environ.get("CUBATURE_TOOLS"):
        return Path(environ["CUBATURE_TOOLS"]).resolve(), None
    session = environ.get("WB_SESSION")
    if session:
        launch_path = Path(environ.get("WB_LAUNCH") or Path(session) / "launch.json")
        commit = launch_commit(json.loads(launch_path.read_text(encoding="utf-8")))
        return export(commit, Path(session) / EXPORT, repository(environ)), commit
    return (ROOT.parent / "Cubature" / "tools").resolve(), None


def lab_observer(launch):
    """The Store's lab observer (cubature:lab-observer) built at the launch's Cubature commit."""
    import fnmatch

    commit, store = launch_commit(launch), Path(launch["store"])
    for index in sorted((store / "index" / "cubature").glob("*.json")):
        value = json.loads(index.read_text(encoding="utf-8"))
        if commit not in (value.get("commits") or []):
            continue
        for name, item in (value.get("artifacts") or {}).items():
            if fnmatch.fnmatch(str(item.get("from") or ""), OBSERVER):
                path = store / "files" / item["sha256"] / name
                if path.is_file():
                    return path
    raise RuntimeError(
        f"The Store holds no lab observer built at Cubature {commit[:12]}: run wb check in Cubature at that "
        "commit, or give --lab-artifact"
    )


CUBATURE_TOOLS, COMMIT = find()
if str(CUBATURE_TOOLS) not in sys.path:
    sys.path.append(str(CUBATURE_TOOLS))
