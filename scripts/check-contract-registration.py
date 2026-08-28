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
     mapping the seam checker uses to tell a contract from a leak. The seam checker only
     ever asks whether a package serve *currently imports* is covered, so it cannot see a
     missing entry for a module serve has not reached yet, and it never looks at the
     values at all: a mapping naming an artifact that nothing publishes, or naming the
     wrong one, passes it. This script checks the entries themselves.

(3) has been forgotten twice in a row — once for `common-image-crop` (#4634) and once
for `agent-grant-protocol` (#4656), each caught by a human after the fact. This makes
it a build failure instead of a thing to remember.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

PROBE = "preview-server/contract-probe/build.gradle.kts"
SHELL = "scripts/check-preview-server-contracts.sh"
CI_PATHS = ".github/ci/ci-paths.json"
SETTINGS = "settings.gradle.kts"
ALLOWLIST = "scripts/serve-seam-allowlist.json"
PATH_SCOPE = ".github/ci/path-scope.py"
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


def contract_packages(root: pathlib.Path) -> dict[str, str]:
    """The package -> artifact id mapping the seam checker reads."""
    return json.loads((root / ALLOWLIST).read_text()).get("contractPackages", {})


def module_packages(root: pathlib.Path, directory: str) -> set[str]:
    """Every package declared in a module's main source set.

    Java as well as Kotlin: a JVM consumer imports a Java declaration exactly like a Kotlin
    one, so a Java-only package in a contract is as much part of the seam. No contract module
    has Java sources today — which is the reason to include them now, while it costs a glob.
    """
    packages: set[str] = set()
    main = root / directory / "src" / "main"
    for pattern in ("*.kt", "*.java"):
        for source in main.rglob(pattern):
            match = re.search(r"^\s*package\s+([\w.]+)", source.read_text(), re.M)
            if match:
                packages.add(match.group(1))
    return packages


def package_roots(packages: set[str]) -> list[str]:
    """The shallowest packages in a module — the ones worth a mapping entry.

    `…render.session` and `…render.session.subprocess` live in different modules, so a root
    is not simply the common prefix: it is any package with no ancestor in the same module.
    """
    return sorted(p for p in packages if not any(p != q and p.startswith(q + ".") for q in packages))


def mapping_for(package: str, mapping: dict[str, str]) -> tuple[str | None, str | None]:
    """The most specific entry covering `package`, as (key, artifact id).

    Longest match wins, mirroring nothing in the seam checker on purpose: the seam checker
    only asks *whether* some prefix covers a package, so `…daemon.client` silently reads as
    `…daemon` there. Resolving the same way the reader would is what exposes that.
    """
    candidates = [k for k in mapping if package == k or package.startswith(k + ".")]
    if not candidates:
        return None, None
    key = max(candidates, key=len)
    return key, mapping[key]


def path_matcher(root: pathlib.Path):
    """`glob_to_regex` from the CI path classifier itself.

    Reimplementing the matching was the bug: a prefix test accepted `daemon/core/**/*.kt` and a
    trailing-slash test accepted `daemon/core/`, neither of which selects the group for a change
    to that module's build file — `daemon/core/` in fact matches nothing at all. The only
    trustworthy answer to "does this glob schedule the job for this path" comes from the code
    that decides it.
    """
    spec = importlib.util.spec_from_file_location("path_scope", root / PATH_SCOPE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.glob_to_regex


# Shapes a contract module can grow that do not exist in it yet. Real files (below) cannot speak
# for a source set a module has not added, so these cover the forward-looking half: a module with
# only Kotlin today whose group glob is `…/src/main/kotlin/**` still has to admit a future `.java`.
#
# They are a sample, and a sample alone is gameable — one literal glob per entry satisfies all of
# them. The nonce segments raise the bar (nothing anyone would write into `ci-paths.json` on
# purpose matches them) but do not clear it, which is why the real-file check below carries the
# weight. Constants rather than random so a failure is reproducible.
REPRESENTATIVE = (
    "build.gradle.kts",
    "api/x.api",
    "src/main/kotlin/ee/schimke/composeai/X.kt",
    "src/main/java/ee/schimke/composeai/X.java",
    "src/test/kotlin/ee/schimke/composeai/XTest.kt",
    "q7v3v9v1.kt",
    "src/main/kotlin/q7v3v9v1/nested/deeper/Zq7v3.kts",
)

# Tracked files that cannot change what a module compiles or publishes, so a group that does not
# select them is not under-covering anything.
NON_COMPILING = (".md",)

# How many unselected real paths to name before summarising. A narrowed glob misses hundreds.
MAX_REPORTED = 5


def subtree_globs_for(directory: str) -> list[str]:
    """The whole-subtree patterns that would cover `directory` — its own, and each ancestor's.

    An ancestor counts: `render-session/**` legitimately covers `render-session/api`, and demanding
    one entry per project would fail a correct file.
    """
    parts = directory.split("/")
    return [f"{'/'.join(parts[: i + 1])}/**" for i in range(len(parts))]


def covering_subtree_glob(directory: str, globs: list[str]) -> str | None:
    """The group's whole-subtree glob for `directory`, or None.

    The only TOTAL statement available here. Every check below it samples — real files and
    representative shapes are both finite sets of paths, and a finite sample cannot prove that a
    pattern set covers a subtree: globs enumerating `daemon/core/*.kt`, `*.kts`, `api/**` and the
    existing source directories miss a future `src/main/resources/schema.json` with no sample to
    catch it, and the PR adding that file does not schedule the probe either, so nothing ever
    notices. Requiring the pattern itself makes the answer independent of what exists today.

    Matched literally, not by shape. The two earlier attempts at this check both INFERRED coverage
    from a pattern's shape and were wrong (a prefix test accepted `daemon/core/**/*.kt`; a
    trailing-slash test accepted `daemon/core/`, which selects nothing) — so this asks for one of a
    known-good set of spellings, and `uncovered_paths` then verifies with the classifier's own
    matcher that the spelling behaves.
    """
    wanted = set(subtree_globs_for(directory))
    return next((g for g in globs if g.strip() in wanted), None)


def module_files(root: pathlib.Path, directory: str) -> list[str]:
    """Every tracked file under `directory` that can change what the module builds.

    This is the half of the check that a hand-written glob cannot satisfy by coincidence. The
    representative list above is a fixed seven paths, so seven literal globs
    (`daemon/core/q7v3v9v1.kt`, …) pass it while no real source selects the job — measured at
    131 of `daemon/core`'s 132 tracked files left unselected. Asking the group about the files
    that are actually there removes that gap: there is no set of literal globs short enough to
    write and long enough to cover a module.
    """
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--", directory],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return []
    return [f for f in result.stdout.split("\0") if f and not f.endswith(NON_COMPILING)]


def uncovered_paths(root: pathlib.Path, directory: str, globs: list[str], to_regex) -> list[str]:
    """Paths under `directory` that no glob in the group selects.

    Both halves: the module's real tracked files, and the representative shapes it does not have
    yet. A miss in either means a change to that path does not schedule the probe — it is
    classified by some other group, so the classifier does not fail open and nothing goes red;
    the gate is simply skipped, which is the silent failure this whole script exists to catch.
    """
    patterns = [to_regex(g) for g in globs]
    unselected = lambda path: not any(p.match(path) for p in patterns)  # noqa: E731

    real = [f for f in module_files(root, directory) if unselected(f)]
    synthetic = [f"{directory}/{s}" for s in REPRESENTATIVE if unselected(f"{directory}/{s}")]

    if len(real) > MAX_REPORTED:
        shown = real[:MAX_REPORTED]
        shown.append(f"… and {len(real) - MAX_REPORTED} more tracked files under {directory}/")
        real = shown
    return real + synthetic


def check(root: pathlib.Path = REPO) -> int:
    contracts = probe_contracts(root)
    projects = shell_projects(root)
    globs = ci_group(root)
    mapping = contract_packages(root)
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

    for artifact in sorted(set(mapping.values()) - named):
        keys = sorted(k for k, a in mapping.items() if a == artifact)
        problems.append(
            f"{ALLOWLIST}: `contractPackages` maps {', '.join(keys)} to '{artifact}', which no "
            f"project in {SHELL} publishes — the seam checker would credit those imports to a "
            "contract that does not exist"
        )

    # Every mapping key names a package; the artifact it names must be the one that ships it.
    # The per-module loop below only reaches a module's *root* packages, so a nested entry —
    # `…daemon.bta` pointed at `daemon-client` while its sources sit in `daemon-core` — is
    # invisible there: `daemon-core` reduces to `…daemon`, and the value check only asks
    # whether `daemon-client` exists at all.
    owned: dict[str, set[str]] = {}
    for path, directory in resolved.items():
        artifact = ids[path]
        if artifact is not None:
            owned.setdefault(artifact, set()).update(module_packages(root, directory))
    for package, artifact in sorted(mapping.items()):
        if artifact not in named:
            continue  # already reported above, with a better message
        packages = owned.get(artifact, set())
        if not any(p == package or p.startswith(package + ".") for p in packages):
            elsewhere = sorted(a for a, ps in owned.items() if any(p == package for p in ps))
            hint = f" — it ships in {', '.join(elsewhere)}" if elsewhere else ""
            problems.append(
                f"{ALLOWLIST}: `contractPackages` maps '{package}' to '{artifact}', which "
                f"declares no such package{hint}"
            )

    for path, directory in sorted(resolved.items()):
        artifact = ids[path]
        if artifact is None:
            continue
        for package in package_roots(module_packages(root, directory)):
            key, mapped = mapping_for(package, mapping)
            if mapped is None:
                problems.append(
                    f"{ALLOWLIST}: `contractPackages` has no entry for '{package}' ({path}), so "
                    "the seam checker cannot tell that package apart from a leak once serve "
                    "imports it"
                )
            elif mapped != artifact:
                problems.append(
                    f"{ALLOWLIST}: `contractPackages` resolves '{package}' ({path}) to "
                    f"'{mapped}' via the '{key}' entry, but that package ships in '{artifact}' — "
                    f"add an explicit '{package}': '{artifact}' entry"
                )

    to_regex = path_matcher(root)
    for path, directory in sorted(resolved.items()):
        if covering_subtree_glob(directory, globs) is None:
            problems.append(
                f"{CI_PATHS}: '{CI_GROUP}' has no whole-subtree glob for {directory} (for "
                f"{path}) — add '{directory}/**'. Patterns naming particular files or source "
                "directories cover what exists today and silently stop covering the module the "
                "moment someone adds a file of a shape nobody listed"
            )
            # Its representative and real-file misses would all be noise beside that.
            continue
        missed = uncovered_paths(root, directory, globs, to_regex)
        if missed:
            problems.append(
                f"{CI_PATHS}: '{CI_GROUP}' does not select the contract probe for "
                f"{', '.join(missed)} (for {path}) — the gate would exist without running for "
                "those changes"
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
        f"published, resolved, scheduled and mapped"
    )
    return 0


if __name__ == "__main__":
    sys.exit(check())
