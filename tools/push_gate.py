"""Refuse a push whose commits have not passed `ci`: the pre-push hook of Riven and Cubature.

  python tools/push_gate.py install [--force]   write the hook into Riven and dev/CubicChunks
  python tools/push_gate.py check riven|cubature [remote url]
                                                the hook's check; git lists the refs on stdin

A pushed Riven commit needs every ci job passed on its tree together with the tree of the
Cubature commit its dev/cc.lock.json pins; a pushed Cubature commit needs every ci job passed on
its tree with one Riven tree. Passes are the ones tools/actions.py records with each run. Only
the pushed tips are checked. The hook stays local (git never commits .git/hooks), and
`git push --no-verify` skips it for a push that deliberately goes without.
"""

import argparse
import json
import subprocess
import sys
from collections.abc import Iterable
from pathlib import Path

import actions

MARKER = "tools/push_gate.py"
HOOK = """#!/bin/sh
# Installed by tools/push_gate.py install: refuses a push whose commits have not passed ci.
exec "{python}" "{script}" check {repository} "$@"
"""


def refusal(
    repository: str,
    commit: str,
    passed: dict[tuple[str, str], set[str]],
    ci: list[str],
    riven: Path = actions.ROOT,
    cubature: Path = actions.CUBATURE,
) -> str | None:
    """Why commit may not be pushed, or None when every ci job passed on its trees."""
    if repository == "riven":
        tree = actions.git(riven, "rev-parse", f"{commit}^{{tree}}")
        try:
            pin = json.loads(actions.git(riven, "show", f"{commit}:dev/cc.lock.json"))["commit"]
        except subprocess.CalledProcessError, ValueError, KeyError:
            return "its dev/cc.lock.json names no Cubature commit"
        try:
            pinned = actions.git(cubature, "rev-parse", "--verify", f"{pin}^{{tree}}")
        except subprocess.CalledProcessError:
            return f"its Cubature pin {pin[:8]} is not in dev/CubicChunks"
        done = passed.get((tree, pinned), set())
        trees = f"Riven tree {tree[:8]} with Cubature {pin[:8]}"
    else:
        tree = actions.git(cubature, "rev-parse", f"{commit}^{{tree}}")
        done = max(
            (jobs for (_, cubature_tree), jobs in passed.items() if cubature_tree == tree),
            key=lambda jobs: len(jobs & set(ci)),
            default=set(),
        )
        trees = f"Cubature tree {tree[:8]}"
    missing = [job for job in ci if job not in done]
    return f"{trees} has not passed {', '.join(missing)}" if missing else None


def check(repository: str, lines: Iterable[str]) -> int:
    """git's pre-push lines: <local ref> <local commit> <remote ref> <remote commit>."""
    ci = actions.load()["workflows"]["ci"]
    passed = actions.passed_jobs()
    refused = 0
    for line in lines:
        fields = line.split()
        if len(fields) != 4 or set(fields[1]) == {"0"}:  # a deleted ref has nothing to check
            continue
        reason = refusal(repository, fields[1], passed, ci)
        if reason:
            refused += 1
            print(f"push_gate: refusing {fields[0]} ({fields[1][:8]}): {reason}.", file=sys.stderr)
    if refused:
        print(
            "Check out the pushed commits in Riven and dev/CubicChunks with nothing uncommitted, run"
            " `python tools/actions.py ci --if-needed`, and push again.",
            file=sys.stderr,
        )
    return 1 if refused else 0


def install(force: bool) -> int:
    python, script = Path(sys.executable).as_posix(), Path(__file__).resolve().as_posix()
    failed = 0
    for repository, repo in actions.REPOSITORIES:
        hook = repo / actions.git(repo, "rev-parse", "--git-path", "hooks") / "pre-push"
        if hook.exists() and not force and MARKER not in hook.read_text(errors="replace"):
            print(f"{hook} is another hook; --force replaces it", file=sys.stderr)
            failed += 1
            continue
        hook.parent.mkdir(parents=True, exist_ok=True)
        text = HOOK.format(python=python, script=script, repository=repository)
        hook.write_text(text, encoding="utf-8", newline="\n")
        hook.chmod(0o755)
        print(f"installed {hook}")
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)
    install_command = commands.add_parser("install", help="write the pre-push hooks")
    install_command.add_argument("--force", action="store_true", help="replace another hook")
    check_command = commands.add_parser("check", help="the hook's check (refs on stdin)")
    check_command.add_argument("repository", choices=[name for name, _ in actions.REPOSITORIES])
    check_command.add_argument("remote", nargs="*", help="git's remote name and URL (unused)")
    args = parser.parse_args()
    if args.command == "install":
        sys.exit(install(args.force))
    sys.exit(check(args.repository, sys.stdin))


if __name__ == "__main__":
    main()
