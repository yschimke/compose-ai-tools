#!/usr/bin/env python3
"""Fail-open changed-path classifier for PR workflow jobs.

The JSON config contains named groups of glob patterns, optional ignored paths,
and optional global paths. A PR group is enabled when any changed file matches
it. Ignored files enable nothing. An empty diff, unreadable config, git error,
or an unclassified path enables every group so a new source tree cannot
silently escape CI.

Non-PR events always enable every group. Output is written as `name=true|false`
lines, suitable for appending directly to GITHUB_OUTPUT.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def glob_to_regex(pattern: str) -> re.Pattern:
    pat = pattern.strip().lstrip("/")
    if "/" not in pat.replace("/**", ""):
        if not pat.startswith("**/") and not pat.startswith("**"):
            pat = "**/" + pat
    out: list[str] = []
    i = 0
    while i < len(pat):
        if pat[i] == "*":
            if pat[i : i + 3] == "**/":
                out.append("(?:[^/]+/)*")
                i += 3
            elif pat[i : i + 2] == "**":
                out.append(".*")
                i += 2
            else:
                out.append("[^/]*")
                i += 1
        elif pat[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(pat[i]))
            i += 1
    regex = "".join(out)
    if regex.endswith("/.*"):
        regex = regex[: -len("/.*")] + "(?:/.*)?"
    return re.compile("^" + regex + "$")


def _matches(path: str, patterns: list[re.Pattern]) -> bool:
    return any(pattern.match(path) for pattern in patterns)


def decide(files: list[str], raw_config: dict) -> dict[str, bool]:
    groups = raw_config.get("groups")
    if not isinstance(groups, dict) or not groups:
        raise ValueError("groups missing or empty")
    compiled = {
        name: [glob_to_regex(str(pattern)) for pattern in patterns]
        for name, patterns in groups.items()
    }
    ignored = [glob_to_regex(str(p)) for p in raw_config.get("ignorePaths", [])]
    global_paths = [glob_to_regex(str(p)) for p in raw_config.get("globalPaths", [])]
    all_on = {name: True for name in compiled}
    if not files:
        return all_on
    result = {name: False for name in compiled}
    for path in files:
        if _matches(path, global_paths):
            return all_on
        matched = False
        for name, patterns in compiled.items():
            if _matches(path, patterns):
                result[name] = True
                matched = True
        if not matched and not _matches(path, ignored):
            # Unknown paths fail open. This is deliberately stricter than a
            # positive allow-list: adding a new source tree runs everything.
            return all_on
    return result


def changed_files(repo: Path, base_sha: str, head_sha: str) -> list[str]:
    proc = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", base_sha, head_sha],
        cwd=repo,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip()[:500])
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    args = parser.parse_args()
    repo = Path(os.environ.get("GITHUB_WORKSPACE") or os.getcwd())
    try:
        raw = json.loads((repo / args.config).read_text())
        groups = raw.get("groups") or {}
        if os.environ.get("EVENT_NAME") != "pull_request":
            result = {name: True for name in groups}
        else:
            base = os.environ.get("BASE_SHA", "")
            head = os.environ.get("HEAD_SHA", "")
            if not base or not head:
                raise ValueError("missing BASE_SHA/HEAD_SHA")
            result = decide(changed_files(repo, base, head), raw)
    except Exception as error:  # noqa: BLE001 - fail-open is the contract
        print(f"path-scope: {error}; enabling every group", file=sys.stderr)
        try:
            raw = json.loads((repo / args.config).read_text())
            result = {name: True for name in (raw.get("groups") or {})}
        except Exception:
            return 1
    for name, enabled in result.items():
        print(f"{name}={'true' if enabled else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
