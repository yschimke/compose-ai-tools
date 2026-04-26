#!/usr/bin/env python3
"""Resolve a compose-preview CLI version from action inputs.

Reads INPUT_VERSION from the environment ("latest" | "catalog" |
literal) and prints the bare version string (no leading "v") on stdout.
CATALOG_PATH and CATALOG_KEY are honoured when INPUT_VERSION="catalog".
GITHUB_TOKEN, when set, authenticates the releases API call.

Kept as a separate script (rather than inline Python in action.yml)
so the catalog parser is testable in isolation and the YAML stays
free of heredoc-in-command-substitution syntax that's easy to break
on edit.
"""

from __future__ import annotations

import json
import os
import sys
import tomllib
import urllib.error
import urllib.request

REPO = "yschimke/compose-ai-tools"


def fail(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def latest_version() -> str:
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases/latest",
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    token = os.environ.get("GITHUB_TOKEN", "")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.load(resp)
    except urllib.error.URLError as exc:
        fail(f"could not reach api.github.com: {exc}")
    tag = data.get("tag_name")
    if not tag:
        fail("releases/latest payload missing tag_name")
    return tag.lstrip("v")


def catalog_version() -> str:
    path = os.environ.get("CATALOG_PATH") or "gradle/libs.versions.toml"
    key = os.environ.get("CATALOG_KEY") or "composePreviewCli"
    try:
        with open(path, "rb") as fh:
            cat = tomllib.load(fh)
    except FileNotFoundError:
        fail(f"version catalog not found: {path}")
    except OSError as exc:
        fail(f"could not read {path}: {exc}")
    except tomllib.TOMLDecodeError as exc:
        fail(f"could not parse {path}: {exc}")
    versions = cat.get("versions") or {}
    value = versions.get(key)
    if value is None:
        fail(f"version key {key!r} not found in {path} [versions]")
    if not isinstance(value, str):
        fail(f"version key {key!r} in {path} is not a string: {value!r}")
    return value.lstrip("v")


def main() -> None:
    inp = (os.environ.get("INPUT_VERSION") or "latest").strip()
    if inp == "latest":
        print(latest_version())
    elif inp == "catalog":
        print(catalog_version())
    else:
        print(inp.lstrip("v"))


if __name__ == "__main__":
    main()
