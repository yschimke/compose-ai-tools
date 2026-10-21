#!/usr/bin/env python3
"""Resolve the affected Gradle verification tasks for a pull request.

Prints `full`, `none`, or a space-separated task list. Any ambiguity fails
open to `full`; non-PR callers should bypass this script and run the full suite.

Emits each project's headless JVM test tasks (`test` and/or `jvmTest`) and, where the
project has one, `:<project>:checkKotlinAbi`. A task is named only where the project
actually has it: naming one it does not have fails the entire invocation.

Naming only `test` was a single-platform assumption. A Kotlin Multiplatform module calls
its JVM tests `jvmTest` and registers no `test` task, so the graph reported "no tests"
and this resolver emitted nothing for it. `:rc-player-compose` (25 test files),
`:rc-player-runtime` (12), `:rc-player-protocol`, `:rc-player-trace` and
`:slot-preview-runtime` had therefore never run in CI — `Module Unit Tests` skipped on a
PR touching only them, while their paths sat in `ci-paths.json` making them look covered
(#4819).

The ABI half is not decoration. Every module with a published surface wires
`checkKotlinAbi` into `check` and says in a comment that a gate is only worth having
if it runs — but this resolver named `test`, and `test` does not imply `check`, so the
ABI gate never ran for the module whose sources changed. Three stale dumps reached
main in a single day that way (#4673, #4685, #4716), each one red for every branch
that merged it afterwards. A gate wired into a task nobody invokes is the thing those
comments were trying to prevent.

`checkPomDependencyVersions` rides along the same way, for every affected project that
publishes to Maven Central. It reads the POMs a release would upload and fails on a
dependency with no version, which Central refuses — for the whole release, not just the
module. #5953 added one to `bundle-producer`; nothing read its POM until the 2.59.0 upload,
and neither 2.59.0 nor 2.60.0 reached Central.

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


def resolve(
    files: list[str],
    config: dict,
    projects: list[dict],
    workspace: Path,
    why: list[str] | None = None,
) -> str:
    """`why`, when given, collects one line per decision so the job summary can say why
    a test did or did not run without anyone re-deriving it from this script."""
    note = why.append if why is not None else (lambda _line: None)
    ignored = [scope.glob_to_regex(str(p)) for p in config.get("ignorePaths", [])]
    global_paths = [scope.glob_to_regex(str(p)) for p in config.get("globalPaths", [])]
    if not files:
        note("No changed files could be listed: running the full suite.")
        return "full"
    relevant = []
    for path in files:
        if any(pattern.match(path) for pattern in global_paths):
            note(f"`{path}` is a global build input: running the full suite.")
            return "full"
        if not any(pattern.match(path) for pattern in ignored):
            relevant.append(path)
    if not relevant:
        note(f"All {len(files)} changed file(s) match `ignorePaths`: no module tests.")
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
            note(f"`{path}` belongs to no Gradle project: running the full suite.")
            return "full"
        changed.add(owner)

    affected = scope.reverse_closure(changed, projects)
    excluded = set(config.get("excludeProjects", []))
    note(f"Changed projects: {', '.join(f'`{p}`' for p in sorted(changed))}.")
    dependents = sorted(affected - changed - excluded)
    if dependents:
        note(f"Their dependents: {', '.join(f'`{p}`' for p in dependents)}.")
    skipped = sorted(affected & excluded)
    if skipped:
        note(f"Excluded by `excludeProjects`: {', '.join(f'`{p}`' for p in skipped)}.")
    selected = [
        project
        for project in projects
        if project.get("path") in affected and project.get("path") not in excluded
    ]

    def task(path: str, name: str) -> str:
        return f"{path}:{name}" if path != ":" else f":{name}"

    # `jvmTestTasks` is the current shape; `hasTestTask` is read as a fallback so a graph
    # produced before that field existed still resolves `test` rather than silently
    # resolving nothing — this script fails open everywhere else for the same reason.
    def test_tasks(project: dict) -> list[str]:
        names = project.get("jvmTestTasks")
        if names is None:
            names = ["test"] if project.get("hasTestTask") else []
        return [task(project["path"], name) for name in names]

    tasks = sorted(
        [t for p in selected for t in test_tasks(p)]
        + [task(p["path"], "checkKotlinAbi") for p in selected if p.get("hasAbiCheck")]
        + [task(p["path"], "checkPomDependencyVersions") for p in selected if p.get("hasPomCheck")]
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
        why: list[str] = []
        result = resolve(files, config, projects, workspace, why)
    except Exception as error:  # noqa: BLE001 - fail-open is the contract
        print(f"affected-gradle-tests: {error}; running full suite", file=sys.stderr)
        why = [f"The resolver failed (`{error}`): running the full suite."]
        result = "full"
    write_summary(result, why)
    print(result)
    return 0


def write_summary(result: str, why: list[str]) -> None:
    """Best-effort: a summary that cannot be written must never change the task list."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    lines = ["### Module Unit Tests scope", ""] + [f"- {line}" for line in why]
    if result not in ("full", "none"):
        lines += ["", "Tasks:", ""] + [f"- `{t}`" for t in result.split()]
    try:
        with open(path, "a", encoding="utf-8") as summary:
            summary.write("\n".join(lines) + "\n")
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main())
