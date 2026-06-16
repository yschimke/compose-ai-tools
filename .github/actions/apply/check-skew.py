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


def detect_plugin_version(workspace: str, catalog_path: str) -> str | None:
    """Best-effort applied-plugin version for [workspace].

    Catalog first (the documented, Renovate-friendly pin), then a literal
    declaration in a build script. ``None`` when neither is found — the signal
    to stay silent rather than guess.
    """
    catalog_file = (
        catalog_path
        if os.path.isabs(catalog_path)
        else os.path.join(workspace, catalog_path)
    )
    return plugin_version_from_catalog(catalog_file) or plugin_version_from_build_scripts(
        workspace
    )


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


def main() -> int:
    mode = (os.environ.get("SKEW_MODE") or "fail").strip().lower()
    if mode == "off":
        return 0

    cli_raw = (os.environ.get("RESOLVED_CLI_VERSION") or "").strip().lstrip("v")
    if not cli_raw:
        # No resolved CLI version handed in (source build, install skipped) —
        # nothing to compare against.
        return 0

    workspace = (
        os.environ.get("WORKSPACE") or os.environ.get("GITHUB_WORKSPACE") or "."
    )
    catalog_path = os.environ.get("CATALOG_PATH") or "gradle/libs.versions.toml"

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
    sys.exit(main())
