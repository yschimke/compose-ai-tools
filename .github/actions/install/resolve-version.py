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

# The CLI tarball that action.yml's Download step pulls. "Complete" for
# install-action purposes means this asset is present and fully uploaded —
# the MCP / viewer / VSIX assets don't matter to consumers using this action.
CLI_ASSET_TEMPLATE = "compose-preview-{ver}.tar.gz"


def fail(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def _api_request(path: str) -> urllib.request.Request:
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    token = os.environ.get("GITHUB_TOKEN", "")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    return req


def _release_is_complete(release: dict) -> bool:
    """True iff the release has the CLI tarball asset fully uploaded.

    GitHub creates the Release record as soon as the tag lands (release-please
    or release.yml's fallback), but assets are uploaded by a later job. During
    that window the Release exists but its `assets` array is empty or the
    asset is `state="open"` (partial upload), which is exactly what causes
    consumers downloading via `version: latest` to 404 or hang.
    """
    tag = release.get("tag_name")
    if not tag:
        return False
    asset_name = CLI_ASSET_TEMPLATE.format(ver=tag.lstrip("v"))
    for asset in release.get("assets") or []:
        if (
            asset.get("name") == asset_name
            and asset.get("state") == "uploaded"
            and (asset.get("size") or 0) > 0
        ):
            return True
    return False


_MAX_RELEASES_PAGES = 5
_RELEASES_PER_PAGE = 30


def _parse_next_link(link_header: str | None) -> str | None:
    """Extract the `rel="next"` URL from a GitHub `Link` response header.

    GitHub returns paginated endpoints with `Link: <url>; rel="next", <url>; rel="last"`;
    we follow the `next` URL verbatim (it already carries the right `page=…` query
    parameter) until exhausted. Returns `None` when the header is missing or has
    no `next` segment, signalling we've reached the last page.
    """
    if not link_header:
        return None
    for part in link_header.split(","):
        segment = part.strip()
        if not segment.startswith("<"):
            continue
        close = segment.find(">")
        if close == -1:
            continue
        url = segment[1:close]
        params = segment[close + 1 :]
        if 'rel="next"' in params:
            return url
    return None


def latest_version() -> str:
    """Resolve `latest` to the most recent release with a complete CLI asset.

    Walks `/releases` (newest first, drafts/prereleases skipped) and returns
    the first tag whose CLI tarball is fully uploaded. This avoids the race
    window where `/releases/latest` returns a tag whose assets haven't been
    uploaded yet — see _release_is_complete for the failure mode.

    Paginates the listing because busy repos with frequent prereleases / drafts
    can fill page 1 entirely with skipped entries before a complete published
    release shows up. Bounded at `_MAX_RELEASES_PAGES` pages
    (`= _MAX_RELEASES_PAGES * _RELEASES_PER_PAGE` entries) so a misconfigured
    pipeline fails loudly instead of walking the whole release history.
    """
    skipped: list[str] = []
    next_url: str | None = (
        f"https://api.github.com/repos/{REPO}/releases?per_page={_RELEASES_PER_PAGE}"
    )
    pages_fetched = 0
    while next_url and pages_fetched < _MAX_RELEASES_PAGES:
        req = urllib.request.Request(
            next_url,
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
                releases = json.load(resp)
                link_header = resp.headers.get("Link")
        except urllib.error.URLError as exc:
            fail(f"could not reach api.github.com: {exc}")
        pages_fetched += 1
        if not isinstance(releases, list):
            fail("releases payload was not a list")

        for release in releases:
            if release.get("draft") or release.get("prerelease"):
                continue
            tag = release.get("tag_name")
            if not tag:
                continue
            if _release_is_complete(release):
                if skipped:
                    # Surface the fall-back so a flaky release pipeline shows up
                    # in run logs instead of silently pinning consumers backwards.
                    print(
                        f"::warning::compose-preview install: skipped incomplete release(s) "
                        f"{', '.join(skipped)} (CLI tarball not yet uploaded); "
                        f"resolved latest to {tag}",
                        file=sys.stderr,
                    )
                return tag.lstrip("v")
            skipped.append(tag)

        next_url = _parse_next_link(link_header)

    cap = _MAX_RELEASES_PAGES * _RELEASES_PER_PAGE
    fail(
        f"no published release in the last {cap} has a fully-uploaded CLI tarball; "
        f"checked: {', '.join(skipped) if skipped else '(none)'}"
    )


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
