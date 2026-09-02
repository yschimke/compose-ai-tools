#!/usr/bin/env python3
"""Trust the compose-preview plugin in an imported project's dependency verification metadata.

An imported project may harden its build against precisely what an import does: resolve a plugin it
has never heard of. mullvad/mullvadvpn-app ships ``gradle/verification-metadata.xml`` with
``<verify-metadata>true</verify-metadata>``, so every artifact needs a checksum and the auto-injected
preview plugin has none. The render dies during configuration, before a single preview is discovered
(yschimke/compose-preview-imports#30)::

    > Dependency verification failed for configuration 'classpath'
      3 artifacts failed verification: ee.schimke.composeai.preview.gradle.plugin-...pom ...

The alternative considered and rejected was rendering with ``--dependency-verification=lenient``,
which makes verification non-fatal for EVERY artifact in the build for the sake of getting two of
ours through. Appending two ``<trust>`` entries leaves every dependency the project actually
declares strictly verified, and trusts only the coordinates we are the reason for.

This edits a THROWAWAY checkout, discarded with the CI job. It is never run against a repository
anyone keeps.
"""

from __future__ import annotations

import re
import sys

# Both groups are required, and that is not obvious: the plugin is injected by its MARKER
# coordinate (ee.schimke.composeai.preview:...gradle.plugin), whose POM resolves the implementation
# from a different group entirely. Trusting the marker alone leaves the implementation unverifiable
# and the build still fails.
GROUPS = ("ee.schimke.composeai.preview", "ee.schimke.composeai")

TRUST_TAG = re.compile(r"<trust\b[^>]*>")
ATTR = re.compile(r"""\b([A-Za-z]+)\s*=\s*["']([^"']*)["']""")


def trusts_group_unconstrained(text: str, group: str) -> bool:
    """True only for an entry that trusts the whole group with no further constraint.

    Gradle ANDs the group/name/version/file constraints on a ``<trust>``, so
    ``<trust group="ee.schimke.composeai" name="something-else"/>`` trusts one artifact and says
    nothing about ours. Treating that as coverage would skip the append and leave the import failing
    verification anyway (PR #4990 review).
    """
    for tag in TRUST_TAG.findall(text):
        attrs = dict(ATTR.findall(tag))
        if attrs.get("group") == group and set(attrs) == {"group"}:
            return True
    return False


def add_trust_entries(text: str, groups=GROUPS) -> tuple[str, list[str]]:
    """Return the metadata with any missing group-wide trust entries added, and which were added."""
    missing = [g for g in groups if not trusts_group_unconstrained(text, g)]
    if not missing:
        return text, []

    entries = "".join('         <trust group="%s"/>\n' % g for g in missing)

    if "</trusted-artifacts>" in text:
        return re.sub(r"[ \t]*</trusted-artifacts>", entries + "      </trusted-artifacts>", text, count=1), missing
    if "</configuration>" in text:
        block = "      <trusted-artifacts>\n" + entries + "      </trusted-artifacts>\n   </configuration>"
        return re.sub(r"[ \t]*</configuration>", block, text, count=1), missing
    # A file with neither is not a verification file we understand. Failing here is better than
    # rendering with the hardening silently unaddressed.
    raise SystemExit("::error::%s has no <trusted-artifacts> or <configuration> to extend" % PATH_HINT)


PATH_HINT = "verification metadata"


def main(argv: list[str]) -> int:
    global PATH_HINT
    if len(argv) != 2:
        raise SystemExit("usage: trust-preview-plugin.py <path-to-verification-metadata.xml>")
    path = PATH_HINT = argv[1]

    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    updated, added = add_trust_entries(text)
    if not added:
        print("upstream already trusts the preview plugin coordinates — left as is")
        return 0

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(updated)
    print("trusted in the throwaway checkout: " + ", ".join(added))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
