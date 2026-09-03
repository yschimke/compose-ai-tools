#!/usr/bin/env python3
"""Wait until a compose-preview release's Gradle plugin is actually resolvable.

A release's CLI tarball is downloadable minutes — sometimes tens of minutes —
before its Gradle plugin is, and a build dispatched inside that window dies
during configuration with `Could not find
ee.schimke.composeai.preview:…gradle.plugin:<version>`: an error that names the
*consumer's* project and says nothing about a race (issues #5034, #5051).

`version: latest` sidesteps this by resolving to a release carrying its
Maven-readiness marker (see resolve-version.py). This script is the guard for
every other mode — a literal version, `pin`, `catalog` — where the version is
whatever the consumer named and no marker can help.

# What "resolvable" means here

Not "the marker POM exists". The marker is a `pom`-packaged stub whose only
content is a dependency on the implementation, in a different group, and the
implementation in turn has same-version `api` dependencies (`preview-discovery`,
`daemon-launch-builder`, `compose-preview-config`). Any of those lagging in CDN
propagation fails the consumer's configuration just as hard. So the artifact set
is *derived from the POMs themselves* rather than hardcoded: the marker's POM
names the implementation, the implementation's POM names its same-version
siblings, and every one of them has to answer.

The alternative — a real Gradle resolution, as `maven-readiness.yml` performs —
needs a JDK and a Gradle distribution. This action deliberately installs neither
(consumers compose `actions/setup-java` themselves) and runs before the CLI is
even downloaded, so a POM-walk over the same coordinate set is what is available
here without turning a legibility guard into a build.

# The three states an artifact can be in

- **found** — 200 from a mirror.
- **absent** — a definite "not there" (404) from every mirror.
- **inconclusive** — a mirror answered 429 or 5xx, which says "would not
  answer", not "is not there". A Central rate limit must never be reported as an
  unpublished release.

A run passes when everything is found; it warns and passes when nothing is
absent and something was inconclusive (no verdict to give); and it keeps waiting
while anything is absent, because a definite absence is never masked by a
neighbour's rate limit.
"""

from __future__ import annotations

import os
import re
import sys
import time
import urllib.error
import urllib.request

PLUGIN_ID = "ee.schimke.composeai.preview"
MARKER_ARTIFACT = f"{PLUGIN_ID}.gradle.plugin"

# Only our own coordinates are walked. A third-party dependency of the plugin
# (Kotlin, OkHttp, ASM …) has been on Central for months; it is the artifacts
# published by *this* release, together, that propagate independently.
OWN_GROUP_PREFIX = "ee.schimke.composeai"

MIRRORS = (
    "https://plugins.gradle.org/m2",
    "https://repo1.maven.org/maven2",
)

# Per-request ceiling. Past the first sweep the real bound is what is left of the
# budget; this only stops one stalled connection from eating a long budget in a
# single attempt.
MAX_REQUEST_SECONDS = 30
# What a request on the *first* sweep gets even when the budget is smaller. The
# budget bounds how long the guard will keep waiting; one attempt always happens,
# because a check that never checks is worse than a slightly late answer. Every
# later request is bounded by what is actually left.
MIN_FIRST_REQUEST_SECONDS = 5
RETRY_SECONDS = 15

FOUND, ABSENT, INCONCLUSIVE = "found", "absent", "inconclusive"


def _path(group: str, artifact: str, version: str, ext: str) -> str:
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"


class BudgetExhausted(Exception):
    """Raised instead of probing when the caller's budget is spent.

    A distinct signal on purpose: "we ran out of time" must not become a
    no-verdict warning-pass, which is how an unpublished release would slip
    through as success.
    """


def _get(url: str, timeout: float) -> tuple[str, str, str]:
    """Returns (state, body, detail). Body is empty unless the state is FOUND."""
    if timeout <= 0:
        raise BudgetExhausted(url)
    req = urllib.request.Request(url, headers={"User-Agent": "compose-preview-install"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return FOUND, resp.read().decode("utf-8", "replace"), f"{url} 200"
    except urllib.error.HTTPError as exc:
        if exc.code in (404, 410):
            return ABSENT, "", f"{url} {exc.code}"
        # 429 and 5xx say "would not answer", not "is not there". So does
        # anything else unexpected — err towards no verdict, never a false one.
        return INCONCLUSIVE, "", f"{url} {exc.code}"
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return INCONCLUSIVE, "", f"{url} ({exc})"


def check(paths: str, deadline: float, minimum: bool = False) -> tuple[str, str, str]:
    """Look for one artifact across the mirrors.

    Every mirror is tried before an inconclusive verdict is returned, so a
    rate-limited Plugin Portal cannot hide a 200 on Central. Returns
    (state, body, detail).
    """
    detail = ""
    inconclusive = False
    for mirror in MIRRORS:
        url = f"{mirror}/{paths}"
        state, body, detail = _get(url, _request_timeout(deadline, minimum))
        if state == FOUND:
            return FOUND, body, detail
        if state == INCONCLUSIVE:
            inconclusive = True
    return (INCONCLUSIVE if inconclusive else ABSENT), "", detail


def _request_timeout(deadline: float, minimum: bool = False) -> float:
    """What one request may take.

    Never more than what is left of the budget — that is the promise the
    `plugin-readiness-timeout` input makes — except on the first sweep, where
    [MIN_FIRST_REQUEST_SECONDS] guarantees the guard actually gets to ask once.
    """
    left = deadline - time.monotonic()
    if minimum:
        left = max(left, MIN_FIRST_REQUEST_SECONDS)
    return min(MAX_REQUEST_SECONDS, max(0.0, left))


_DEPENDENCY_RE = re.compile(r"<dependency>(.*?)</dependency>", re.S)
_GROUP_RE = re.compile(r"<groupId>\s*([^<\s]+)\s*</groupId>")
_ARTIFACT_RE = re.compile(r"<artifactId>\s*([^<\s]+)\s*</artifactId>")
_VERSION_RE = re.compile(r"<version>\s*([^<\s]+)\s*</version>")


def same_version_dependencies(pom: str, version: str) -> list[tuple[str, str]]:
    """`(group, artifact)` for this release's own dependencies named in [pom].

    Scoped to [OWN_GROUP_PREFIX] and to exactly [version]: those are the
    artifacts published by the same release, which therefore propagate on their
    own schedule and are the ones a partial propagation strands.
    """
    found = []
    for block in _DEPENDENCY_RE.findall(pom):
        group = _GROUP_RE.search(block)
        artifact = _ARTIFACT_RE.search(block)
        declared = _VERSION_RE.search(block)
        if not (group and artifact and declared):
            continue
        if declared.group(1) != version:
            continue
        if not group.group(1).startswith(OWN_GROUP_PREFIX):
            continue
        entry = (group.group(1), artifact.group(1))
        if entry not in found:
            found.append(entry)
    return found


def one_pass(
    version: str, deadline: float, notes: list[str], minimum: bool = False
) -> str:
    """One sweep over the whole coordinate set, appending to [notes].

    Walks the POMs rather than a hardcoded list: the marker names the
    implementation, the implementation names its same-version siblings. Stops at
    the first absence — there is nothing to learn from the rest of the set once
    the run has to wait anyway. Raises [BudgetExhausted] rather than reporting a
    verdict it did not have time to reach.
    """
    inconclusive = False

    state, marker_pom, detail = check(
        _path(PLUGIN_ID, MARKER_ARTIFACT, version, "pom"), deadline, minimum
    )
    notes.append(f"marker: {state} ({detail})")
    if state == ABSENT:
        return ABSENT
    if state == INCONCLUSIVE:
        return INCONCLUSIVE

    pending = same_version_dependencies(marker_pom, version)
    seen: set[tuple[str, str]] = set()
    while pending:
        group, artifact = pending.pop(0)
        if (group, artifact) in seen:
            continue
        seen.add((group, artifact))

        pom_state, pom, pom_detail = check(
            _path(group, artifact, version, "pom"), deadline, minimum
        )
        notes.append(f"{group}:{artifact} pom: {pom_state} ({pom_detail})")
        if pom_state == ABSENT:
            return ABSENT
        if pom_state == INCONCLUSIVE:
            inconclusive = True
            continue

        # The jar is what the buildscript classpath actually loads; a POM that
        # has propagated without its jar is exactly the partial state this
        # guard exists to catch.
        jar_state, _, jar_detail = check(
            _path(group, artifact, version, "jar"), deadline, minimum
        )
        notes.append(f"{group}:{artifact} jar: {jar_state} ({jar_detail})")
        if jar_state == ABSENT:
            return ABSENT
        if jar_state == INCONCLUSIVE:
            inconclusive = True

        pending.extend(same_version_dependencies(pom, version))

    return INCONCLUSIVE if inconclusive else FOUND


def run(version: str, budget: float) -> int:
    """Poll until ready, out of budget, or unable to tell. Returns an exit code."""
    deadline = time.monotonic() + budget
    notes: list[str] = ["nothing probed"]
    first = True
    while True:
        try:
            state = one_pass(version, deadline, notes, minimum=first)
        except BudgetExhausted:
            break
        first = False
        if state == FOUND:
            print(
                f"✓ plugin {MARKER_ARTIFACT}:{version} and its classpath are resolvable."
            )
            return 0
        if state == INCONCLUSIVE:
            print(
                "::warning::compose-preview install: could not get a verdict on whether plugin "
                f"{version} is published ({notes[-1]}); proceeding.",
                file=sys.stderr,
            )
            return 0

        left = deadline - time.monotonic()
        if left <= 0:
            break
        nap = min(RETRY_SECONDS, left)
        print(
            f"plugin {MARKER_ARTIFACT}:{version} not published yet ({notes[-1]}); "
            f"retrying in {nap:.0f}s ({left:.0f}s of the {budget:.0f}s budget left)."
        )
        time.sleep(nap)

    print(
        f"::error title=compose-preview plugin not published yet::The CLI release {version} "
        f"exists, but its Gradle plugin is not resolvable from plugins.gradle.org or Maven "
        f"Central after {budget:.0f}s ({notes[-1]}). This is the publication window, not a "
        "problem with your project: a release is downloadable as a CLI before the plugin of the "
        "same version publishes. Re-run in a few minutes, or pin the previous release "
        "(version: <older>, or composePreview.version in gradle.properties).",
        file=sys.stderr,
    )
    return 1


def main() -> None:
    version = (os.environ.get("VER") or "").strip()
    if not version:
        print("::error::plugin readiness check ran with no resolved version", file=sys.stderr)
        raise SystemExit(1)
    raw_budget = (os.environ.get("BUDGET") or "").strip()
    if not raw_budget.isdigit():
        print(
            "::error::plugin-readiness-timeout must be a whole number of seconds; "
            f"got {raw_budget!r}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    raise SystemExit(run(version, float(raw_budget)))


if __name__ == "__main__":
    main()
