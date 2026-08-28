#!/usr/bin/env python3
"""Assert the four lists that describe a preview-server contract agree.

Adding a contract module means editing four hand-maintained places, and three of the
four fail *silently* when you forget one:

  1. `contracts` in preview-server/contract-probe/build.gradle.kts — the artifact ids
     the probe resolves. Miss it and the module is not proven resolvable.
  2. `CONTRACT_PROJECTS` in scripts/check-preview-server-contracts.sh — the Gradle
     paths the script publishes first. Miss it and (1) fails loudly, which is the one
     that already tells you.
  3. `preview_server_contracts` in .github/ci/ci-paths.json — the paths that *schedule*
     the probe job. Miss it and the gate simply does not run for changes to the new
     module: green CI, no coverage.
  4. `contractPackages` in scripts/serve-seam-allowlist.json — the package -> artifact
     mapping the seam checker uses to tell a contract from a leak.

(3) has been forgotten twice in a row — once for `common-image-crop` (#4634) and once
for `agent-grant-protocol` (#4656), each caught by a human after the fact. This makes
it a build failure instead of a thing to remember.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

PROBE = "preview-server/contract-probe/build.gradle.kts"
SHELL = "scripts/check-preview-server-contracts.sh"
CI_PATHS = ".github/ci/ci-paths.json"
SETTINGS = "settings.gradle.kts"
CI_GROUP = "preview_server_contracts"


def probe_contracts(root: pathlib.Path) -> list[str]:
    """The artifact ids `checkContractSurface` resolves."""
    text = (root / PROBE).read_text()
    start = text.index("val contracts =")
    end = text.index("\n  )", start)
    return re.findall(r'^\s*"([A-Za-z0-9._-]+)",?\s*$', text[start:end], re.M)


def shell_projects(root: pathlib.Path) -> list[str]:
    """The Gradle paths the probe script publishes before resolving them."""
    text = (root / SHELL).read_text()
    start = text.index("CONTRACT_PROJECTS=(")
    end = text.index("\n)", start)
    return re.findall(r'"(:[^"]+)"', text[start:end])


def project_dir(root: pathlib.Path, path: str) -> str:
    """Where a Gradle path lives on disk, honouring settings.gradle.kts remappings."""
    settings = (root / SETTINGS).read_text()
    match = re.search(
        r'project\("' + re.escape(path) + r'"\)\.projectDir = file\("([^"]+)"\)', settings
    )
    return match.group(1) if match else path.strip(":").replace(":", "/")


def artifact_id(root: pathlib.Path, directory: str) -> str | None:
    build = root / directory / "build.gradle.kts"
    if not build.is_file():
        return None
    match = re.search(r'artifactId\s*=\s*"([^"]+)"', build.read_text())
    return match.group(1) if match else None


def ci_group(root: pathlib.Path) -> list[str]:
    groups = json.loads((root / CI_PATHS).read_text())["groups"]
    if CI_GROUP not in groups:
        raise SystemExit(f"{CI_PATHS}: no '{CI_GROUP}' group")
    return groups[CI_GROUP]


def covered(directory: str, globs: list[str]) -> bool:
    """True when some glob in the CI group schedules the probe for `directory`.

    A prefix test rather than an equality test on purpose: `render-session/**` legitimately
    covers both `render-session/api` and `render-session/subprocess`, and demanding an exact
    entry per project would fail a correct file.
    """
    for glob in globs:
        prefix = glob.split("*", 1)[0].rstrip("/")
        if prefix and (directory == prefix or directory.startswith(prefix + "/")):
            return True
    return False


def check(root: pathlib.Path = REPO) -> int:
    contracts = probe_contracts(root)
    projects = shell_projects(root)
    globs = ci_group(root)
    problems: list[str] = []

    resolved = {p: project_dir(root, p) for p in projects}
    ids = {p: artifact_id(root, d) for p, d in resolved.items()}

    unpublished = sorted(p for p, a in ids.items() if a is None)
    if unpublished:
        problems.append(
            f"{SHELL}: no artifactId found for {', '.join(unpublished)} — the probe cannot "
            "resolve a module that does not publish"
        )

    named = {a for a in ids.values() if a}
    for missing in sorted(named - set(contracts)):
        problems.append(f"{PROBE}: '{missing}' is published by {SHELL} but not in `contracts`")
    for missing in sorted(set(contracts) - named):
        problems.append(f"{SHELL}: '{missing}' is in `contracts` but no project publishes it")

    for path, directory in sorted(resolved.items()):
        if not covered(directory, globs):
            problems.append(
                f"{CI_PATHS}: '{CI_GROUP}' does not cover {directory}/ (for {path}), so the "
                "contract probe is not scheduled for changes to it — the gate would exist "
                "without running"
            )

    if problems:
        print("check-contract-registration: FAILED\n")
        for problem in problems:
            print(f"  - {problem}")
        print(
            "\nA contract module is registered in four places; see this script's docstring "
            "for what each one does."
        )
        return 1

    print(
        f"check-contract-registration: OK — {len(contracts)} contract(s), "
        f"published, resolved and scheduled"
    )
    return 0


if __name__ == "__main__":
    sys.exit(check())
