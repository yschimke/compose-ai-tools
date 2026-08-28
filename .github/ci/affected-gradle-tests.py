#!/usr/bin/env python3
"""Resolve the affected Gradle verification tasks for a pull request.

Prints `full`, `none`, or a space-separated task list. Any ambiguity fails
open to `full`; non-PR callers should bypass this script and run the full suite.

Emits `:<project>:test` and, where the project has one, `:<project>:checkKotlinAbi`.

The ABI half is not decoration. Every module with a published surface wires
`checkKotlinAbi` into `check` and says in a comment that a gate is only worth having
if it runs — but this resolver named `test`, and `test` does not imply `check`, so the
ABI gate never ran for the module whose sources changed. Three stale dumps reached
main in a single day that way (#4673, #4685, #4716), each one red for every branch
that merged it afterwards. A gate wired into a task nobody invokes is the thing those
comments were trying to prevent.

`checkKotlinAbi` is emitted per project rather than for all of them because naming a
task a project does not have fails the entire invocation ("Cannot locate tasks that
match ..."), not just that task — the same trap ci.yml documents for the iOS targets.
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
SCOPE_SCRIPT = REPO / ".github" / "actions" / "apply" / "compute-scope.py"
SPEC = importlib.util.spec_from_file_location("preview_compute_scope", SCOPE_SCRIPT)
scope = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scope)


def resolve(files: list[str], config: dict, projects: list[dict], workspace: Path) -> str:
    ignored = [scope.glob_to_regex(str(p)) for p in config.get("ignorePaths", [])]
    global_paths = [scope.glob_to_regex(str(p)) for p in config.get("globalPaths", [])]
    if not files:
        return "full"
    relevant = []
    for path in files:
        if any(pattern.match(path) for pattern in global_paths):
            return "full"
        if not any(pattern.match(path) for pattern in ignored):
            relevant.append(path)
    if not relevant:
        return "none"

    ws = workspace.resolve()
    owners: list[tuple[str, str]] = []
    for project in projects:
        try:
            rel = Path(project["dir"]).resolve().relative_to(ws).as_posix()
        except (KeyError, ValueError):
            continue
        if rel != ".":
            owners.append((rel, project["path"]))
    owners.sort(key=lambda item: item[0].count("/"), reverse=True)
    changed: set[str] = set()
    for path in relevant:
        owner = next(
            (project for directory, project in owners if path == directory or path.startswith(directory + "/")),
            None,
        )
        if owner is None:
            return "full"
        changed.add(owner)

    affected = scope.reverse_closure(changed, projects)
    excluded = set(config.get("excludeProjects", []))
    selected = [
        project
        for project in projects
        if project.get("path") in affected and project.get("path") not in excluded
    ]

    def task(path: str, name: str) -> str:
        return f"{path}:{name}" if path != ":" else f":{name}"

    tasks = sorted(
        [task(p["path"], "test") for p in selected if p.get("hasTestTask")]
        + [task(p["path"], "checkKotlinAbi") for p in selected if p.get("hasAbiCheck")]
    )
    return " ".join(tasks) if tasks else "none"


def main() -> int:
    workspace = Path(os.environ.get("GITHUB_WORKSPACE") or os.getcwd())
    try:
        config = json.loads((workspace / ".github/ci/gradle-test-scope.json").read_text())
        base = os.environ["BASE_SHA"]
        head = os.environ["HEAD_SHA"]
        files = scope.changed_files(workspace, base, head)
        projects = scope.run_gradle_graph(
            workspace,
            workspace / ".github/actions/apply/scope-project-graph.init.gradle",
            300,
        )
        print(resolve(files, config, projects, workspace))
    except Exception as error:  # noqa: BLE001 - fail-open is the contract
        print(f"affected-gradle-tests: {error}; running full suite", file=sys.stderr)
        print("full")
    return 0


if __name__ == "__main__":
    sys.exit(main())
