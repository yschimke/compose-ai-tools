#!/usr/bin/env python3
"""Report which split-out consumers a PR's contract changes will reach.

`compose-ai-tools` used to contain every consumer of its own contracts, so a
protocol change and the code reading it moved in one commit and CI checked both.
After the split (#4732) two consumers live elsewhere:

  * yschimke/compose-preview-vscode    — the VS Code extension
  * yschimke/compose-preview-contracts — the published wire contracts
  * yschimke/compose-preview-server    — the preview server behind `serve`

Both pin a *released* version of this repository and vendor copies of what they
need. Their own gates catch drift — but only against the release they pin, which
means a contract change here is invisible to them until somebody bumps that pin.
That is the gap #4732 records as "a `daemon-protocol` bump has to fail the
consumer's CI, not just the producer's".

**This is informational, never blocking, and that is deliberate.** A consumer
cannot adopt a change that has not been released yet, so failing this PR would
deadlock: the PR waits for the consumer, the consumer waits for the release, the
release waits for the PR. What is actually missing is not a veto but *visibility*
— the obligation exists the moment the change lands, and nothing surfaced it.
The consumer-side half of the gate (a canary that tests against this repo's
`main` rather than its pin) is what turns drift red over there.

Usage:
    consumer-contract-notice.py <changed-path>...
    git diff --name-only BASE HEAD | consumer-contract-notice.py -

Prints a Markdown report to stdout, or the sentinel `NO_CONTRACT_SURFACES` when
the change touches none. Always exits 0.
"""

from __future__ import annotations

import fnmatch
import sys

SENTINEL = "NO_CONTRACT_SURFACES"

EXT = "yschimke/compose-preview-vscode"
CONTRACTS = "yschimke/compose-preview-contracts"
SERVER = "yschimke/compose-preview-server"

# Each entry: the surface, the paths that constitute it, and per consumer what
# that consumer has to do once this change is released. Keep the follow-ups
# concrete — a notice that only says "something changed" gets ignored.
SURFACES: list[dict] = [
    {
        "name": "Daemon protocol fixtures",
        "patterns": ["docs/daemon/protocol-fixtures/*"],
        "why": (
            "The cross-language wire goldens. Both consumers vendor a copy and parse it "
            "in their own suites; that shared parse is the drift check."
        ),
        "consumers": {
            EXT: (
                "vendors these at `protocol-fixtures/`. Its `Protocol Fixtures` workflow "
                "diffs that copy against the release its `plugin-version.json` pins, so it "
                "goes red on the pin bump unless the same commit runs "
                "`scripts/sync-protocol-fixtures.sh`."
            ),
            CONTRACTS: (
                "vendors these at `docs/daemon/protocol-fixtures/`, where `MessagesTest` and "
                "`daemonFraming` round-trip them. Re-sync when adopting the release."
            ),
        },
    },
    {
        "name": "Device catalog",
        # `daemon/devices/**` was here too, until that module moved to
        # compose-preview-contracts. What is left is this repository's copy — the plugin's — which
        # is exactly the half a drift test on the other side compares against.
        "patterns": [
            "gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/DeviceDimensions.kt",
        ],
        "why": (
            "`DeviceDimensions` — the device table and the `spec:` grammar — is duplicated "
            "between the plugin and the daemon, and has drifted before (`parent=`, a "
            "symmetric `orientation=`)."
        ),
        "consumers": {
            CONTRACTS: (
                "publishes `daemon-devices` and runs `DeviceDimensionsCatalogDriftTest` against "
                "a checkout of this repository. Its CI compares the two copies on every run, so "
                "a one-sided change here turns it red."
            ),
        },
    },
    {
        "name": "Daemon launch descriptor",
        # The `DaemonLaunchDescriptor.kt` pattern was here too, until `daemon/protocol` moved to
        # compose-preview-contracts. The builder that writes the descriptor is still here.
        "patterns": [
            "gradle-plugin/daemon-launch-builder/src/main/**",
        ],
        "why": (
            "Three copies of the schema, and the TypeScript one is the only reader that "
            "*gates* on the version."
        ),
        "consumers": {
            EXT: (
                "carries that reader. `checkDaemonLaunchSchema` here already checks it when a "
                "checkout is present (CI sets `COMPOSE_PREVIEW_VSCODE_ROOT`), so a mismatch "
                "should have failed this PR — if it did not, the checkout step is the thing to "
                "look at."
            ),
        },
    },
    {
        "name": "Spatial scene schema",
        "patterns": ["schema/spatial-scene.schema.json"],
        "why": "The Kotlin, C++ and TypeScript mirrors are generated from this one file.",
        "consumers": {
            EXT: (
                "holds the TypeScript mirror at `src/webview/shared/spatialScene.ts`, and "
                "**no gate covers it** — `--check` here compares only the Kotlin and C++ "
                "mirrors, because this repository cannot write into that one. Regenerate it "
                "there with `gen-spatial-scene.mjs --emit-typescript`."
            ),
        },
    },
    {
        "name": "Preview selector fixtures",
        "patterns": ["docs/serve/preview-selector-fixtures.json"],
        "why": (
            "The golden table for `--id` / `--filter` / `--preview`. Two implementations "
            "answer that question — `previewIdMatchesRequest` here and "
            "`previewIdMatchesStandaloneRequest` in the server, which builds "
            "`ServeCommandOptions` itself now that `serve` is a launcher (#5177) — and this "
            "table is the only thing pinning them together (#5185). The failure mode is "
            "silent: a preview that stops matching produces no error, it is just absent."
        ),
        "consumers": {
            SERVER: (
                "vendors this at `docs/serve/preview-selector-fixtures.json` and runs it through "
                "its own rule in `PreviewSelectorFixturesTest`. Its `selector-fixtures` CI job "
                "diffs that copy against the compose-ai-tools release its `composeai-tools` "
                "version pin names, so it goes red on the pin bump unless the same commit runs "
                "`scripts/sync-preview-selector-fixtures.sh`."
            ),
        },
    },
]


def matches(path: str, pattern: str) -> bool:
    """Glob match where `**` spans directories, as in a workflow `paths:` filter."""
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-2])
    return fnmatch.fnmatch(path, pattern)


def affected(paths: list[str]) -> list[tuple[dict, list[str]]]:
    """`(surface, sorted matching paths)` for every surface the change touches."""
    out = []
    for surface in SURFACES:
        hits = sorted({p for p in paths for pat in surface["patterns"] if matches(p, pat)})
        if hits:
            out.append((surface, hits))
    return out


def report(hits: list[tuple[dict, list[str]]]) -> str:
    lines = [
        "<!-- consumer-contract-notice -->",
        "## Contract surfaces this PR reaches",
        "",
        "This change touches contracts that consumers outside this repository depend on. "
        "**Nothing here blocks the merge** — a consumer cannot adopt a change that has not "
        "shipped yet, so gating on them would deadlock. It is here so the follow-up is "
        "visible now rather than discovered on a pin bump months later.",
        "",
    ]
    for surface, paths in hits:
        lines.append(f"### {surface['name']}")
        lines.append("")
        lines.append(surface["why"])
        lines.append("")
        for path in paths[:10]:
            lines.append(f"- `{path}`")
        if len(paths) > 10:
            lines.append(f"- …and {len(paths) - 10} more")
        lines.append("")
        for repo, todo in surface["consumers"].items():
            lines.append(f"**[{repo}](https://github.com/{repo})** {todo}")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main(argv: list[str]) -> int:
    args = argv[1:]
    if args == ["-"]:
        paths = [ln.strip() for ln in sys.stdin if ln.strip()]
    else:
        paths = [a for a in args if a.strip()]

    hits = affected(paths)
    if not hits:
        print(SENTINEL)
        return 0
    sys.stdout.write(report(hits))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
