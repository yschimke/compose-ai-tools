#!/usr/bin/env python3
"""Decide whether a PR needs the Integration workflow's external-repo matrix.

Reads the PR's changed files and a committed ignore list
(`.github/integration-scope.json`) and prints a single token:

    true     run the full external-repo matrix (default / any doubt)
    false    every changed file is ignore-listed and provably cannot change
             what the plugin discovers/renders in an external consumer repo —
             the slow matrix is skipped and a gate re-emits the required legs

The contract is *fail-open*: an empty diff, a git failure, a missing/unreadable
config, or any unexpected error all resolve to `true`, so scoping can only ever
skip work that cannot affect the external builds. The one case that skips is
"every changed file matches an ignore glob".

Why an ignore list (not an allow list): the external consumer builds only pull
the artifacts `build-plugin` publishes to mavenLocal (gradle-plugin,
renderer-*, daemon-*, data-*, preview-annotations) plus the CLI-materialised
init script. They never build THIS repo's own `samples/**`, the VS Code
extension, docs, the deploy image, or the design-artifacts scripts — so those
are safe to ignore. Anything not on the list (the plugin, CLI, renderers,
daemon, data modules, build wiring, or a path we haven't classified) defaults
to a full run.

Env:
    BASE_SHA / HEAD_SHA  PR diff endpoints (base must already be fetched)
    SCOPE_CONFIG         override config path (default .github/integration-scope.json)

Pure stdlib; unit-tested by test_integration_scope.py. The glob semantics match
.github/actions/apply/compute-scope.py.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

RUN = "true"
SKIP = "false"

REPO_ROOT = Path(__file__).resolve().parents[2]


def _log(msg: str) -> None:
    print(f"integration-scope: {msg}", file=sys.stderr)


def glob_to_regex(pattern: str) -> re.Pattern:
    """Convert a `**`-style glob to a regex over repo-relative POSIX paths.

    `**` crosses directory separators (including matching nothing), `*` and
    `?` do not. A pattern without a `/` is treated as `**/<pattern>` so bare
    basename patterns (`*.md`) match at any depth. A trailing `/**` also
    matches the bare directory itself. (Mirrors compute-scope.py.)
    """
    pat = pattern.strip().lstrip("/")
    if "/" not in pat.replace("/**", ""):
        if not pat.startswith("**/") and not pat.startswith("**"):
            pat = "**/" + pat
    out = []
    i = 0
    while i < len(pat):
        c = pat[i]
        if c == "*":
            if pat[i : i + 2] == "**":
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
    if regex.endswith("/.*"):
        regex = regex[: -len("/.*")] + "(?:/.*)?"
    return re.compile("^" + regex + "$")


def load_ignore_regexes(config_path: Path) -> list[re.Pattern]:
    raw = json.loads(config_path.read_text())
    ignores = raw.get("ignorePaths") or []
    if not isinstance(ignores, list) or not ignores:
        raise ValueError("ignorePaths missing or empty")
    return [glob_to_regex(str(p)) for p in ignores]


def changed_files(base_sha: str, head_sha: str) -> list[str]:
    """`git diff --name-only --no-renames` between PR base and head/merge.

    `--no-renames` so a rename surfaces as delete+add and both sides get
    classified against the ignore list.
    """
    proc = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", base_sha, head_sha],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git diff exited {proc.returncode}: {proc.stderr.strip()[:400]}")
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def decide(files: list[str], ignore_regexes: list[re.Pattern]) -> str:
    """`false` (skip) iff there is at least one file and all are ignore-listed."""
    if not files:
        _log("empty diff — running the matrix (don't guess)")
        return RUN
    unmatched = [f for f in files if not any(rx.match(f) for rx in ignore_regexes)]
    if unmatched:
        _log(
            f"{len(unmatched)} of {len(files)} changed file(s) can affect the "
            f"external builds (e.g. {unmatched[0]}) — running the matrix"
        )
        return RUN
    _log(f"all {len(files)} changed file(s) ignore-listed — skipping the matrix")
    return SKIP


def main() -> int:
    # Fail open: any exception below prints `true`.
    try:
        config_path = Path(
            os.environ.get("SCOPE_CONFIG")
            or (REPO_ROOT / ".github" / "integration-scope.json")
        )
        base_sha = os.environ.get("BASE_SHA", "")
        head_sha = os.environ.get("HEAD_SHA", "")
        if not base_sha or not head_sha:
            _log("missing BASE_SHA/HEAD_SHA — running the matrix")
            print(RUN)
            return 0
        ignore_regexes = load_ignore_regexes(config_path)
        files = changed_files(base_sha, head_sha)
        print(decide(files, ignore_regexes))
        return 0
    except Exception as e:  # noqa: BLE001 — fail-open is the whole contract
        _log(f"unexpected error ({e}) — running the matrix")
        print(RUN)
        return 0


if __name__ == "__main__":
    sys.exit(main())
