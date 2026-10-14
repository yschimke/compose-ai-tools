#!/usr/bin/env python3
"""Reject a release-cutting PR title on a pull request that ships nothing.

release-please cuts a release for every `feat:`, `fix:` and `perf:` that lands, and every release
moves the CLI tag, rewrites the public actions' `@vX` refs and asks consumers to bump. Across the
last 238 such commits on `main`, 12 changed nothing a release carries: the publish plan's own
script, internal workflows, CI pins. Those want `ci:`, `build:`, `test:`, `docs:` or `chore:`,
which land without a release.

The check is deliberately one-sided. A path is INTERNAL only when it matches a rule below; anything
unknown counts as shipped, so a misclassification can only let an unnecessary release through,
never block one that ships a change. Shipped surfaces that look internal are called out:

- `.github/actions/**` (other than Markdown): public composite actions consumers pin at a tag.
- `.github/workflows/*-reusable.yml`: reusable workflows catalogs call at a tag.
- `src/testFixtures`: `java-test-fixtures` publishes it as a variant.
- `gradle/`, `build-logic/`, root build scripts: they change what every module builds.

Usage: check-release-type.py <title>  (changed paths on stdin, one per line)
Exit 0 when the title is fine, 1 when it would cut a release for internal-only changes.
"""

from __future__ import annotations

import re
import sys

RELEASING = re.compile(r"^(feat|fix|perf)(\([^)]*\))?!?:")

# Kept in step with TEST_SOURCE in maven-publish-plan.sh.
TEST_SOURCE = re.compile(r"(^|/)src/(?!testFixtures/)(test[A-Za-z]*|[a-z][A-Za-z]*Test[A-Za-z]*)/")

INTERNAL = [
    re.compile(r"\.md$"),
    re.compile(r"^docs/"),
    re.compile(r"^\.github/workflows/(?!.*-reusable\.ya?ml$)"),
    re.compile(r"^\.github/(scripts|ci|ISSUE_TEMPLATE)/"),
    re.compile(r"^\.github/[^/]+\.(json|ya?ml)$"),
    re.compile(r"^\.(claude|githooks)/"),
    re.compile(r"^(samples|demos)/"),
    re.compile(r"^scripts/test[-_]"),
]


def internal(path: str) -> bool:
    return bool(TEST_SOURCE.search(path)) or any(rule.search(path) for rule in INTERNAL)


def verdict(title: str, paths: list[str]) -> str | None:
    """None when the title is acceptable, else the reason it is not."""
    match = RELEASING.match(title)
    if not match or not paths:
        return None
    if all(internal(p) for p in paths):
        return f"`{match.group(1)}` cuts a release, but every changed path is internal"
    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-release-type.py <title> < paths", file=sys.stderr)
        return 2
    title = sys.argv[1]
    paths = [line.strip() for line in sys.stdin if line.strip()]
    reason = verdict(title, paths)
    if reason is None:
        print(f"OK: {title}")
        return 0
    print(reason)
    for p in paths:
        print(f"  {p}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
