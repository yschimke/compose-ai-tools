#!/usr/bin/env python3
"""Change-scoped preview rendering: decide which modules a PR run must render.

Reads the PR's changed files and a committed scope config
(`.github/preview-scope.json` by default) and prints a single line:

    full             render everything (default / any doubt)
    none             nothing render-affecting changed — skip the pipelines
    mod1,mod2,...    render only these Gradle modules (comma-separated,
                     no leading colon)

The contract is *fail-open*: every ambiguous, unexpected, or error path
resolves to `full`, so scoping can only ever skip work that provably cannot
change a rendered preview. The cases that scope:

* Every changed file is matched by `ignorePaths` -> `none`.
* Every changed file is either ignored or lives inside a module under one of
  the `scopedRoots` -> the changed modules are expanded to all (transitive)
  dependent modules via the Gradle project graph, then intersected with the
  modules that apply the compose-preview plugin -> that set (or `none` when
  it is empty).

Anything else — a file outside the scoped roots (build scripts, version
catalogs, CI, the plugin itself, ...), a file that cannot be attributed to a
module, a missing/failed Gradle graph, a build with no statically applied
preview plugin (the CLI auto-inject path), renames we cannot follow — is
`full`.

Config schema (all paths repo-relative, `**`-style globs):

    {
      "scopedRoots": ["samples"],
      "ignorePaths": ["docs/**", "**/*.md"]
    }

Classification precedence per file: scoped-module ownership first, then
`ignorePaths`, then global. Module ownership winning over `ignorePaths` means
a `samples/wear/fixture.md` still scopes `samples:wear` in, even though
`**/*.md` would ignore the same file elsewhere — content inside a module is
always treated as able to affect that module's renders.

Environment (set by the apply action):
    WORKSPACE            checkout root (default: cwd)
    SCOPE_CONFIG         path to the scope config JSON
    BASE_SHA / HEAD_SHA  PR diff endpoints (base must already be fetched)
    WANT_GRAPH           "1" when the caller renders scoped (compose
                         pipeline); anything else skips the Gradle graph and
                         collapses partial scopes to `full` (the a11y /
                         notifications pipelines only distinguish
                         none-vs-not-none)
    SCOPE_GRAPH_TIMEOUT  seconds for the Gradle graph invocation (default 300)

Pure stdlib; unit-tested by test_compute_scope.py.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

FULL = "full"
NONE = "none"

PREVIEW_PLUGIN_IDS = (
    "ee.schimke.composeai.preview",
    "ee.schimke.composeai.preview.config",
)


def _log(msg: str) -> None:
    print(f"compute-scope: {msg}", file=sys.stderr)


def glob_to_regex(pattern: str) -> re.Pattern:
    """Convert a `**`-style glob to a regex over repo-relative POSIX paths.

    `**` crosses directory separators (including matching nothing), `*` and
    `?` do not. A pattern without a `/` is treated as `**/<pattern>` so bare
    basename patterns (`*.md`) match at any depth. A trailing `/**` also
    matches the bare directory itself.
    """
    pat = pattern.strip().lstrip("/")
    if "/" not in pat.replace("/**", ""):
        # Bare basename pattern (no directory component besides a possible
        # trailing /**): anchor at any depth.
        if not pat.startswith("**/") and not pat.startswith("**"):
            pat = "**/" + pat
    out = []
    i = 0
    while i < len(pat):
        c = pat[i]
        if c == "*":
            if pat[i : i + 2] == "**":
                # Collapse `**/` and `/**` so they can match zero segments.
                if pat[i : i + 3] == "**/":
                    out.append("(?:[^/]+/)*")
                    i += 3
                    continue
                out.append(".*")
                i += 2
                continue
            out.append("[^/]*")
            i += 1
            continue
        if c == "?":
            out.append("[^/]")
            i += 1
            continue
        out.append(re.escape(c))
        i += 1
    regex = "".join(out)
    # `dir/**` should also match `dir` itself.
    if regex.endswith("/.*"):
        regex = regex[: -len("/.*")] + "(?:/.*)?"
    return re.compile("^" + regex + "$")


class ScopeConfig:
    def __init__(self, scoped_roots: list[str], ignore_paths: list[str]):
        self.scoped_roots = [r.strip().strip("/") for r in scoped_roots if r.strip()]
        self.ignore_regexes = [glob_to_regex(p) for p in ignore_paths]

    @staticmethod
    def load(path: Path) -> "ScopeConfig | None":
        try:
            raw = json.loads(path.read_text())
        except FileNotFoundError:
            return None
        except (OSError, json.JSONDecodeError) as e:
            _log(f"unreadable scope config {path}: {e} — full render")
            raise ScopeError from e
        roots = raw.get("scopedRoots") or []
        if not isinstance(roots, list) or not roots:
            _log(f"{path} has no scopedRoots — full render")
            raise ScopeError
        ignores = raw.get("ignorePaths") or []
        if not isinstance(ignores, list):
            raise ScopeError
        return ScopeConfig(roots, [str(p) for p in ignores])

    def root_of(self, rel_path: str) -> str | None:
        for root in self.scoped_roots:
            if rel_path == root or rel_path.startswith(root + "/"):
                return root
        return None

    def is_ignored(self, rel_path: str) -> bool:
        return any(rx.match(rel_path) for rx in self.ignore_regexes)


class ScopeError(Exception):
    """Any condition that must resolve to a full render."""


def changed_files(workspace: Path, base_sha: str, head_sha: str) -> list[str]:
    """`git diff --name-only` between the PR base and head/merge commit.

    `--no-renames` so a rename surfaces as delete+add and both sides get
    classified. Any git failure raises ScopeError (-> full render).
    """
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", base_sha, head_sha],
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        _log(f"git diff failed: {e}")
        raise ScopeError from e
    if proc.returncode != 0:
        _log(f"git diff exited {proc.returncode}: {proc.stderr.strip()[:500]}")
        raise ScopeError
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def partition_files(
    files: list[str], config: ScopeConfig
) -> tuple[list[str], list[str]]:
    """Split changed files into (scoped, ignored). Any global file raises.

    Precedence: scoped-root containment first, then ignorePaths, then global
    (ScopeError -> full). Note scoped files are attributed to modules later,
    against the real Gradle project graph — this step only decides that the
    file is *allowed* to scope.
    """
    scoped: list[str] = []
    ignored: list[str] = []
    for f in files:
        if config.root_of(f) is not None:
            scoped.append(f)
        elif config.is_ignored(f):
            ignored.append(f)
        else:
            _log(f"global path changed: {f}")
            raise ScopeError
    return scoped, ignored


def load_graph(graph_path: Path) -> list[dict]:
    try:
        raw = json.loads(graph_path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        _log(f"unreadable project graph {graph_path}: {e}")
        raise ScopeError from e
    projects = raw.get("projects")
    if not isinstance(projects, list) or not projects:
        _log("project graph is empty")
        raise ScopeError
    for p in projects:
        if "?" in (p.get("dependencies") or []):
            # The init script could not resolve a project dependency's path
            # on this Gradle version — the closure would be incomplete.
            _log(f"project {p.get('path')} has an unresolvable project dependency")
            raise ScopeError
    return projects


def map_files_to_projects(
    scoped_files: list[str],
    projects: list[dict],
    config: ScopeConfig,
    workspace: Path,
) -> set[str]:
    """Attribute each scoped file to the deepest project whose dir contains it.

    The owning project must itself live under a scoped root (the root project
    trivially contains everything and must never win). Unattributable files
    raise (-> full render): e.g. a file in a module that was deleted in this
    PR, or loose files directly under a scoped root.
    """
    ws = workspace.resolve()
    dirs: list[tuple[str, str]] = []  # (rel dir, project path)
    for p in projects:
        try:
            rel = Path(p["dir"]).resolve().relative_to(ws).as_posix()
        except ValueError:
            continue
        if rel == ".":
            continue  # root project — never an owner
        if config.root_of(rel) is None:
            continue  # project outside the scoped roots
        dirs.append((rel, p["path"]))
    # Deepest match first.
    dirs.sort(key=lambda d: d[0].count("/"), reverse=True)

    owners: set[str] = set()
    for f in scoped_files:
        owner = next(
            (path for rel, path in dirs if f == rel or f.startswith(rel + "/")),
            None,
        )
        if owner is None:
            _log(f"no module owns changed file {f} (deleted module or loose file)")
            raise ScopeError
        owners.add(owner)
    return owners


def reverse_closure(changed: set[str], projects: list[dict]) -> set[str]:
    """Changed projects plus every project that (transitively) depends on them."""
    rdeps: dict[str, set[str]] = {}
    for p in projects:
        for dep in p.get("dependencies") or []:
            rdeps.setdefault(dep, set()).add(p["path"])
    affected = set(changed)
    frontier = list(changed)
    while frontier:
        cur = frontier.pop()
        for dependent in rdeps.get(cur, ()):
            if dependent not in affected:
                affected.add(dependent)
                frontier.append(dependent)
    return affected


def resolve_scope(
    files: list[str],
    config: ScopeConfig,
    workspace: Path,
    want_graph: bool,
    graph_loader,
) -> str:
    """Pure decision core. graph_loader() -> list[dict] (projects), called at
    most once and only when a partial scope needs the dependency closure.
    Every ScopeError resolves to a full render here so callers only ever see
    the three-value result."""
    try:
        if not files:
            # An empty PR diff is unexpected — don't guess.
            _log("empty diff — full render")
            return FULL
        scoped, ignored = partition_files(files, config)
        if not scoped:
            _log(f"all {len(ignored)} changed file(s) ignored — nothing to render")
            return NONE
        if not want_graph:
            # This pipeline doesn't render scoped; it only needed the none-check.
            return FULL
        projects = graph_loader()
        preview_modules = {p["path"] for p in projects if p.get("hasPreviewPlugin")}
        if not preview_modules:
            # No statically applied plugin (CLI auto-inject consumer): we can't
            # know which modules render, so we can't scope.
            _log("no module statically applies the compose-preview plugin — full render")
            return FULL
        changed_projects = map_files_to_projects(scoped, projects, config, workspace)
        affected = reverse_closure(changed_projects, projects)
        scope = sorted(m.lstrip(":") for m in affected & preview_modules)
        if not scope:
            _log(
                f"changed module(s) {sorted(changed_projects)} have no preview-module "
                "dependents — nothing to render"
            )
            return NONE
        _log(f"scoped to {len(scope)} module(s): {', '.join(scope)}")
        return ",".join(scope)
    except ScopeError:
        return FULL


def run_gradle_graph(workspace: Path, init_script: Path, timeout: int) -> list[dict]:
    out = Path(tempfile.mkstemp(prefix="scope-graph-", suffix=".json")[1])
    gradlew = workspace / "gradlew"
    if not gradlew.exists():
        _log("no ./gradlew in workspace")
        raise ScopeError
    cmd = [
        str(gradlew),
        "--no-configuration-cache",
        "-q",
        "-I",
        str(init_script),
        f"-Dcomposeai.scope.graph.out={out}",
        "help",
    ]
    try:
        proc = subprocess.run(
            cmd, cwd=workspace, capture_output=True, text=True, timeout=timeout
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        _log(f"gradle graph run failed: {e}")
        raise ScopeError from e
    if proc.returncode != 0:
        _log(f"gradle graph run exited {proc.returncode}: {proc.stderr.strip()[-800:]}")
        raise ScopeError
    return load_graph(out)


def main() -> int:
    workspace = Path(os.environ.get("WORKSPACE") or os.getcwd())
    config_path = Path(
        os.environ.get("SCOPE_CONFIG") or ".github/preview-scope.json"
    )
    if not config_path.is_absolute():
        config_path = workspace / config_path
    base_sha = os.environ.get("BASE_SHA", "")
    head_sha = os.environ.get("HEAD_SHA", "")
    want_graph = os.environ.get("WANT_GRAPH") == "1"
    timeout = int(os.environ.get("SCOPE_GRAPH_TIMEOUT") or "300")
    init_script = Path(__file__).resolve().parent / "scope-project-graph.init.gradle"

    try:
        config = ScopeConfig.load(config_path)
    except ScopeError:
        print(FULL)
        return 0
    if config is None:
        _log(f"no scope config at {config_path} — scoping off, full render")
        print(FULL)
        return 0
    if not base_sha or not head_sha:
        _log("missing BASE_SHA/HEAD_SHA — full render")
        print(FULL)
        return 0

    try:
        files = changed_files(workspace, base_sha, head_sha)
        result = resolve_scope(
            files,
            config,
            workspace,
            want_graph,
            lambda: run_gradle_graph(workspace, init_script, timeout),
        )
    except ScopeError:
        result = FULL
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
