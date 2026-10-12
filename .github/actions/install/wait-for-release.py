#!/usr/bin/env python3
"""Wait until a compose-preview release's CLI tarball is publicly downloadable.

The Download CLI step only calls this after its first download has failed, so a
release that is already public costs nothing extra.

The window it covers: release.yml uploads the tarball to a *draft* release and
only publishes the release once the rest of the pipeline is done. Draft assets
404 at the public `releases/download/` URL, while the Gradle plugin may already
be on Maven Central. A consumer that names the version (a literal, `pin` or
`catalog`) can get past the plugin readiness check and still land in that gap.
On 2026-10-12 m3-catalog requested v2.53.0's tarball at 09:32:18-25, curl's three
retries spent about 7 s on 404s, and the release went public at 09:32:54.

So this polls the same URL curl downloads, with a one-byte ranged GET that
follows the redirect to the asset store (a HEAD can be refused by a signed
asset URL). It sleeps with backoff, and uses whatever is left of the budget it
shares with the plugin readiness check (`plugin-readiness-timeout`, passed in
as the epoch-seconds `DEADLINE` the Resolve step computed). A 404 means "not
public yet, keep waiting". Anything else that is not a 2xx (429, 5xx, a reset)
says nothing about publication, so it is also retried, and the caller's own
download decides what happens next. Exit 0 means the asset answered. Exit 1
means the budget ran out, and the error names the publication window.
"""

from __future__ import annotations

import os
import re
import sys
import time
import urllib.error
import urllib.request

REPO = "yschimke/compose-ai-tools"

# Backoff between probes: 2, 4, 8, 15, 15 ... seconds, capped by the budget left.
FIRST_NAP_SECONDS = 2
MAX_NAP_SECONDS = 15
MAX_REQUEST_SECONDS = 30

READY, NOT_YET, NO_ANSWER = "ready", "not-yet", "no-answer"

_VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$")


def asset_url(version: str) -> str:
    return f"https://github.com/{REPO}/releases/download/v{version}/compose-preview-{version}.tar.gz"


def probe(url: str, timeout: float) -> tuple[str, str]:
    """Returns (state, detail) for one ranged GET of [url]."""
    req = urllib.request.Request(
        url, headers={"User-Agent": "compose-preview-install", "Range": "bytes=0-0"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return READY, f"{resp.status}"
    except urllib.error.HTTPError as exc:
        if exc.code in (404, 410):
            return NOT_YET, f"{exc.code}"
        return NO_ANSWER, f"{exc.code}"
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return NO_ANSWER, f"({exc})"


def run(version: str, deadline: float) -> int:
    """Polls until the asset answers or [deadline] (epoch seconds) passes."""
    url = asset_url(version)
    nap = FIRST_NAP_SECONDS
    detail = "not probed"
    while True:
        left = deadline - time.time()
        if left <= 0:
            break
        state, detail = probe(url, min(MAX_REQUEST_SECONDS, max(1.0, left)))
        if state == READY:
            print(f"✓ release v{version} is public ({url} {detail}).")
            return 0
        left = deadline - time.time()
        if left <= 0:
            break
        sleep = min(nap, left)
        reason = "not public yet" if state == NOT_YET else "did not answer"
        print(
            f"release v{version} {reason} ({url} {detail}); retrying in {sleep:.0f}s "
            f"({left:.0f}s of the shared readiness budget left)."
        )
        time.sleep(sleep)
        nap = min(nap * 2, MAX_NAP_SECONDS)

    print(
        f"::error title=compose-preview release not public yet::The CLI tarball for {version} "
        f"was not downloadable within the plugin-readiness-timeout budget ({url} {detail}). "
        "This is the publication window, not a problem with your project: a release's assets "
        "404 until the release is published, which can lag its Maven publish. Re-run in a few "
        "minutes, or pin the previous release (version: <older>, or composePreview.version in "
        "gradle.properties).",
        file=sys.stderr,
    )
    return 1


def main() -> None:
    version = (os.environ.get("VER") or "").strip()
    if not _VERSION_RE.match(version):
        print(f"::error::Refusing to wait for {version!r}: not a release version.", file=sys.stderr)
        raise SystemExit(1)
    raw_deadline = (os.environ.get("DEADLINE") or "").strip()
    if not raw_deadline.isdigit():
        print(
            f"::error::release wait ran without a numeric DEADLINE; got {raw_deadline!r}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    raise SystemExit(run(version, float(raw_deadline)))


if __name__ == "__main__":
    main()
