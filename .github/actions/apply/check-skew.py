#!/usr/bin/env python3
"""Guardrail against compose-preview CLI / Gradle-plugin version skew.

Background (issue #1920): the `apply` action installs the CLI independently
of the Gradle plugin the consumer pins in their build. When the installed CLI
is *newer* than the applied plugin, the newer CLI's Tooling-model query fails
to discover the older plugin, so the render finds zero preview modules and the
pipeline dies with a generic "no modules have the compose-preview plugin
applied" — which reads like a project misconfiguration, not the version skew
it actually is. Defaulting `cli-version: latest` made the *common* path the
broken path: every new release auto-jumped the CLI ahead of a still-pinned
plugin.

This script runs after the CLI is installed and BEFORE the pipelines pay any
Gradle cost. It reads the resolved CLI version from the environment, sniffs
the applied plugin version out of the consumer's checkout (version catalog
`[plugins]` entry or a literal `id("…") version "…"` in a build script), and
when the CLI is strictly newer, surfaces an actionable message instead of the
misleading downstream failure.

Conservative by construction — it only acts when it can read a concrete plugin
version AND both versions are clean releases (no `-SNAPSHOT` / pre-release
suffixes). When the plugin version can't be found (e.g. the consumer relies on
the CLI's `--init-script` auto-inject and deliberately tracks `latest`) it
stays silent, so the guardrail never invents a failure on the happy path.

Environment inputs:
- ``RESOLVED_CLI_VERSION`` — the concrete version the install action resolved
  (no leading "v"). Empty → skip (nothing to compare).
- ``SKEW_MODE`` — ``fail`` (default; emit ``::error::`` + exit 1), ``warn``
  (emit ``::warning::`` + exit 0), or ``off`` (skip entirely).
- ``WORKSPACE`` — consumer checkout root to scan. Defaults to ``$GITHUB_WORKSPACE``
  then ``.``.
- ``CATALOG_PATH`` — version-catalog path (the action's ``catalog-path`` input),
  relative to ``WORKSPACE``. Defaults to ``gradle/libs.versions.toml``.

Kept as a standalone, import-friendly script (pure functions + a thin
``main``) so the detection + comparison logic is unit-tested in isolation
(`test_check_skew.py`) without spinning up a runner.
"""

from __future__ import annotations

import os
import re
import sys
import tomllib

PLUGIN_ID = "ee.schimke.composeai.preview"

# Directories we never descend into while hunting for a literal plugin
# declaration — build outputs and VCS/tooling metadata can't host a consumer's
# `plugins { }` block, and walking them is pure cost.
_SKIP_DIRS = {"build", ".gradle", ".git", "node_modules", "out", "dist", ".idea"}

# Bounded walk depth: module build scripts live a handful of directories below
# the root in normal layouts. Deep enough for nested modules, shallow enough to
# stay cheap on a large monorepo.
_MAX_DEPTH = 5

# `id("ee.schimke.composeai.preview") version "0.15.8"` and its spelling
# variants: `id 'x' version '0.15.8'` (Groovy), `id("x").version("0.15.8")`,
# `version("0.15.8")`. Capture group 1 is the version literal.
_LITERAL_RE = re.compile(
    r"""id\s*[(\s]\s*["']"""
    + re.escape(PLUGIN_ID)
    + r"""["']\s*\)?\s*(?:\.\s*)?version\s*[(\s]\s*["']([^"']+)["']""",
)


def _strip_comments(source: str) -> str:
    """Drop `//` line comments and `/* … */` block comments.

    Keeps a commented-out example (`// id("…") version "…"`) from being read as
    a real declaration. Not a full Gradle parser — string-literal tracking is
    out of scope, same posture as the CLI's own auto-inject scanner.
    """
    out: list[str] = []
    i = 0
    n = len(source)
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            nl = source.find("\n", i)
            if nl < 0:
                break
            i = nl
        elif c == "/" and nxt == "*":
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def plugin_version_from_catalog(catalog_file: str) -> str | None:
    """Read the compose-preview plugin version from a Gradle version catalog.

    Handles the three `[plugins]` spellings:
    - inline table with a literal version:
      ``foo = { id = "ee.schimke.composeai.preview", version = "0.15.8" }``
    - inline table with a `version.ref` into `[versions]`:
      ``foo = { id = "…", version.ref = "composePreviewPlugin" }``
    - the colon string form: ``foo = "ee.schimke.composeai.preview:0.15.8"``

    Returns the bare version (no leading "v") or ``None`` when the catalog is
    missing/unparseable or declares no entry for the plugin id.
    """
    try:
        with open(catalog_file, "rb") as fh:
            cat = tomllib.load(fh)
    except (FileNotFoundError, OSError, tomllib.TOMLDecodeError):
        return None
    plugins = cat.get("plugins")
    if not isinstance(plugins, dict):
        return None
    versions = cat.get("versions")
    versions = versions if isinstance(versions, dict) else {}
    for entry in plugins.values():
        if isinstance(entry, str):
            # "id:version" — only ours, and only when a version is present.
            base, _, ver = entry.partition(":")
            if base == PLUGIN_ID and ver:
                return ver.lstrip("v")
            continue
        if not isinstance(entry, dict) or entry.get("id") != PLUGIN_ID:
            continue
        ver = entry.get("version")
        if isinstance(ver, str) and ver:
            return ver.lstrip("v")
        # `version.ref = "key"` parses as {"version": {"ref": "key"}}.
        if isinstance(ver, dict):
            ref = ver.get("ref")
            resolved = versions.get(ref) if isinstance(ref, str) else None
            if isinstance(resolved, str) and resolved:
                return resolved.lstrip("v")
    return None


def plugin_version_from_build_scripts(workspace: str) -> str | None:
    """Find a literal `id("…") version "…"` plugin pin in the checkout.

    Walks build scripts under [workspace] (bounded depth, build/VCS dirs
    skipped) and returns the first version literal that pins the plugin id.
    Returns ``None`` when nothing matches.
    """
    root_depth = workspace.rstrip(os.sep).count(os.sep)
    for dirpath, dirnames, filenames in os.walk(workspace):
        depth = dirpath.rstrip(os.sep).count(os.sep) - root_depth
        if depth >= _MAX_DEPTH:
            dirnames[:] = []
        else:
            dirnames[:] = [d for d in dirnames if d not in _SKIP_DIRS]
        for name in ("build.gradle.kts", "build.gradle"):
            if name not in filenames:
                continue
            try:
                with open(os.path.join(dirpath, name), encoding="utf-8") as fh:
                    text = _strip_comments(fh.read())
            except OSError:
                continue
            m = _LITERAL_RE.search(text)
            if m:
                return m.group(1).lstrip("v")
    return None


# `gradle.properties` key holding the project version pin (issue #3738). Kept in
# lockstep with the CLI's `VERSION_PIN_PROPERTY`, the extension's `versionPin.ts`
# and the install action's `resolve-version.py`.
PIN_PROPERTY = "composePreview.version"

# All three separators a Java properties file allows — `key=v`, `key : v`, bare
# `key v` — because the CLI reads this file through `java.util.Properties`, which
# accepts all three. Kept in lockstep with `VersionPin.kt`, `versionPin.ts` and
# the install action's `resolve-version.py`.
_PIN_RE = re.compile(
    r"^[ \t]*"
    + re.escape(PIN_PROPERTY)
    + r"(?:[ \t]*[=:][ \t]*|[ \t]+)(\S+)[ \t]*$",
    re.MULTILINE,
)


def pin_from_properties(workspace: str) -> str | None:
    """Read `composePreview.version` from the workspace's `gradle.properties`.

    The project version pin (issue #3738): one value the CLI, the VS Code
    extension and this action all read, so the version a developer renders
    against locally is the version CI renders against. It governs the plugin
    the CLI **auto-injects** — which is what makes it a usable answer to "which
    plugin will this build apply" for the auto-inject / zero-code project, the
    one shape where the scans below find nothing at all.

    Takes the last assignment when the file has duplicates, matching
    `Properties.load` on the CLI side.
    """
    path = os.path.join(workspace, "gradle.properties")
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return None
    matches = _PIN_RE.findall(text)
    if not matches:
        return None
    return matches[-1].strip().lstrip("v") or None


def detect_plugin_version(workspace: str, catalog_path: str) -> str | None:
    """Best-effort applied-plugin version for [workspace].

    Catalog `[plugins]` entry first, then a literal declaration in a build
    script, then the `gradle.properties` version pin. ``None`` when none is
    found — the signal to stay silent rather than guess.

    **An explicit declaration outranks the pin, deliberately.** The pin decides
    what auto-inject applies, but auto-inject *skips* a module that declares the
    plugin itself, so in a project that has both, the declared version is the one
    actually on that module's classpath — and it is what this function's two
    consumers (`cli-version: auto` resolution and the skew guard) must reason
    about, or the guard would certify a setup as skew-proof while the declared
    module still failed discovery. The pin therefore fills in exactly the gap it
    was added for: the auto-inject project, where nothing is declared anywhere
    and this previously returned ``None`` and fell back to ``latest``.

    When both exist and disagree, [conflicting_pin] surfaces it rather than
    letting either win silently.
    """
    catalog_file = (
        catalog_path
        if os.path.isabs(catalog_path)
        else os.path.join(workspace, catalog_path)
    )
    return (
        plugin_version_from_catalog(catalog_file)
        or plugin_version_from_build_scripts(workspace)
        or pin_from_properties(workspace)
    )


def conflicting_pin(workspace: str, catalog_path: str) -> tuple[str, str] | None:
    """`(declared, pinned)` when both exist and disagree, else ``None``.

    A project that declares `id("…") version "X"` in a module *and* pins
    `composePreview.version=Y` is telling us two different things: the declared
    module renders against X, every auto-injected module against Y. Neither
    number describes the whole build, so the useful move is to say so — the
    resolution above stays deterministic, and the caller warns.
    """
    catalog_file = (
        catalog_path
        if os.path.isabs(catalog_path)
        else os.path.join(workspace, catalog_path)
    )
    declared = plugin_version_from_catalog(
        catalog_file
    ) or plugin_version_from_build_scripts(workspace)
    pinned = pin_from_properties(workspace)
    if declared and pinned and declared != pinned:
        return (declared, pinned)
    return None


_SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def parse_release(version: str) -> tuple[int, int, int] | None:
    """Parse a *clean release* `major.minor.patch` into a tuple.

    Returns ``None`` for anything with a pre-release / build suffix
    (``0.15.9-SNAPSHOT``, ``0.16.0-rc1``, ``…-feature-x``) or a non-numeric
    shape. We only compare clean releases — skew between SNAPSHOTs / locally
    built CLIs is expected during development and must never trip the guard.
    """
    m = _SEMVER_RE.match(version.strip().lstrip("v"))
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def _workspace_and_catalog() -> tuple[str, str]:
    """Resolve the (workspace, catalog_path) pair from the environment.

    Shared by the skew guard (`main`) and the `detect-plugin` resolver so both
    read the consumer checkout the same way.
    """
    workspace = (
        os.environ.get("WORKSPACE") or os.environ.get("GITHUB_WORKSPACE") or "."
    )
    catalog_path = os.environ.get("CATALOG_PATH") or "gradle/libs.versions.toml"
    return workspace, catalog_path


def detect_plugin_main() -> int:
    """`detect-plugin` entry point: print the applied plugin version, or nothing.

    Backs the `apply` action's `cli-version: auto` resolution — when a concrete
    plugin version is pinned in the checkout the CLI is installed at exactly
    that version (skew-proof by construction), otherwise the action falls back
    to `latest`. Prints the bare version on stdout when found, prints nothing
    when not, and always exits 0 (a missing pin is the expected auto-inject
    case, not an error).
    """
    workspace, catalog_path = _workspace_and_catalog()
    version = detect_plugin_version(workspace, catalog_path)
    if version:
        print(version)
    return 0


def main() -> int:
    mode = (os.environ.get("SKEW_MODE") or "fail").strip().lower()
    if mode == "off":
        return 0

    cli_raw = (os.environ.get("RESOLVED_CLI_VERSION") or "").strip().lstrip("v")
    if not cli_raw:
        # No resolved CLI version handed in (source build, install skipped) —
        # nothing to compare against.
        return 0

    workspace, catalog_path = _workspace_and_catalog()

    # A declared version and a project pin that disagree describe two different
    # builds — the declared module renders against one, every auto-injected
    # module against the other. Neither number covers the whole build, so say so
    # rather than let the comparison below imply the whole project is aligned.
    # Always a warning, never fatal: it is a configuration smell, not the
    # discovery failure this guard blocks on.
    conflict = conflicting_pin(workspace, catalog_path)
    if conflict:
        declared, pinned = conflict
        print(
            f"::warning::compose-preview: this project declares the plugin at "
            f"v{declared} and pins `composePreview.version={pinned}`. Modules that "
            f"declare the plugin apply v{declared}; auto-injected modules apply "
            f"v{pinned} (auto-inject skips declared modules). The check below "
            f"compares against v{declared}. Align the two, or drop one."
        )

    plugin_raw = detect_plugin_version(workspace, catalog_path)
    if not plugin_raw:
        # Couldn't read a pinned plugin version (auto-inject / unrecognised
        # shape). Don't guess — staying silent keeps the happy path green.
        return 0

    cli = parse_release(cli_raw)
    plugin = parse_release(plugin_raw)
    if cli is None or plugin is None:
        # A SNAPSHOT / pre-release on either side — skew is expected, skip.
        return 0

    if cli <= plugin:
        # CLI matches or trails the plugin: the plugin-discovery hazard this
        # guard exists for doesn't apply. (Plugin newer than CLI is a separate,
        # rarer case that `compose-preview doctor` already flags for majors.)
        print(
            f"compose-preview: CLI v{cli_raw} aligned with applied plugin v{plugin_raw}."
        )
        return 0

    message = (
        f"compose-preview CLI v{cli_raw} is newer than the applied Gradle plugin "
        f"v{plugin_raw}. The newer CLI can't discover the older plugin, so the "
        f"render finds no preview modules (you'd otherwise see the misleading "
        f'"no modules have the compose-preview plugin applied"). Fix by bumping '
        f"the plugin to v{cli_raw}, or pin the CLI to the plugin so they can't "
        f"skew: set `cli-version: catalog` (with `catalog-key` pointing at your "
        f"plugin's version key). See "
        f"https://github.com/yschimke/compose-ai-tools/blob/main/.github/actions/apply/README.md#version-skew"
    )
    if mode == "warn":
        print(f"::warning::{message}")
        return 0
    print(f"::error::{message}")
    return 1


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "detect-plugin":
        sys.exit(detect_plugin_main())
    sys.exit(main())
