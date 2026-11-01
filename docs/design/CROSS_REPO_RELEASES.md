# Releasing across the five repositories

Five repositories publish into one Maven group (`ee.schimke.composeai`) on five independent
version lines, and each consumes the ones below it **by coordinate, from a repository** — never
by `includeBuild`. That is deliberate ([`REPOSITORY_LAYERS.md`](REPOSITORY_LAYERS.md)), and the
cost it buys is this: a change to a lower repository reaches a higher one **only through a
release**. This document is the order to do that in, and the potholes on the way.

[`REPOSITORY_LAYERS.md`](REPOSITORY_LAYERS.md) says which repository a module belongs in.
This one says what to do once one of them needs to ship.

> Not to be confused with [`RELEASE_TRAINS.md`](RELEASE_TRAINS.md), which is about how often
> *this* repository publishes its 94 artifacts and how much of that is wasted rebuild. This
> document is about the order the five repositories release **in relation to each other**.

## The graph

```
compose-preview-contracts     (0)   wire shapes — depends on nothing
        │
        ▼
compose-preview-daemon        (1a)  renderers, extractors, the daemon process
        │
        ▼
rc-players                    (1)   the .rc players
        │
        ▼
compose-ai-tools              (1)   discovery, the Gradle plugin, the CLI
        │
        ▼
compose-preview-server        (2)   HTTP, the web surfaces, the UI builder
        │
        ▼
compose-preview-mcp           (3)   the local MCP server (a leaf)
```

**It is a DAG, and the release order is that list, top to bottom.** Every edge points down:

| repository | consumes | via |
| --- | --- | --- |
| `compose-preview-daemon` | contracts | `composeai-contracts`, `composeai-contracts-layoutinspector` |
| `rc-players` | contracts, daemon | `composeai-contracts`, `composeai-preview-daemon` |
| `compose-ai-tools` | contracts, daemon, rc-players | `composeai-contracts`, `composeai-preview-daemon`, `rcplayers` |
| `compose-preview-server` | all four | `composeai-contracts`, `composeai-preview-daemon`, `rc-players`, `composeai-tools` |
| `compose-preview-mcp` | contracts, daemon, compose-ai-tools, rc-players' JS player, and the server's MCP App pages | `composeai-contracts`, `composeai-preview-daemon`, `composeai-tools`, `remote-compose-player-js`, `mcp-app/server-assets.provenance.json` (a server **release tag**, not a coordinate) |

> **There is a cycle between `compose-preview-daemon` and `rc-players`, but only through
> compile-only and test edges, so the order above still holds.** rc-players depends on the
> daemon at runtime (`rc-embedded-player` `implementation`s `data-fonts-google`, and tests
> use `data-layoutinspector-connector`, through `compose-preview-daemon-bom`). The daemon
> points back at rc-players only through `rc-players-bom` on `compileOnly`, test and
> `carriedRemoteComposeTestRuntime` configurations (`daemon/desktop`, `daemon/android`,
> `data/remotecompose/connector`): nothing it publishes resolves an rc-players coordinate at
> runtime. So a daemon release never waits for rc-players. But an rc-players release that
> changes the player API can turn the daemon's tests red on its next `rcplayers` bump, which
> lands *after* the daemon has shipped. Verify both directions before assuming otherwise:
>
> ```sh
> grep -n 'ee\.schimke\.composeai:' ../rc-players/gradle/libs.versions.toml
> grep -rn 'libs.rcplayers' ../compose-preview-daemon --include=*.gradle.kts
> ```
>
> compose-ai-tools itself is no longer on either side of it: rc-players names no compose-ai-tools
> coordinate (compose-ai-tools#5336 moved `data-fonts-google` and
> `data-layoutinspector-connector` to the daemon).

## The smoothest path

Cutting a release in any one repository is already documented and mostly automatic —
[`docs/RELEASING.md`](../RELEASING.md) here, and the equivalent in each sibling. Merging the
`chore(main): release X.Y.Z` PR **is** the release. What is not documented anywhere else is the
sequencing between them, which is where the time actually goes.

**Work strictly downhill, one repository at a time, and never start the next one until the
previous one's artifacts resolve from Maven Central.** The whole train is:

1. **Land the change in the lowest repository it touches.** If a wire shape moved, that is
   `compose-preview-contracts` and nothing else — a shape the daemon and the server must agree
   on across a process boundary belongs there, never in a higher repository.
2. **Cut its release** by merging its release PR. Wait for the GitHub Release to **un-draft**.
   That is the signal, not the tag: every repository here keeps the Release a draft until the
   Central upload has succeeded, precisely so a release never announces artifacts that are not
   there.
3. **Wait for Central to serve the coordinate.** Un-drafting means the upload succeeded, not that
   the CDN has propagated. compose-ai-tools has machinery for this (the
   `compose-preview-maven-ready-<version>.json` marker); elsewhere, resolve the coordinate for
   real before moving on:
   ```sh
   curl -sI https://repo1.maven.org/maven2/ee/schimke/composeai/<artifact>/<version>/<artifact>-<version>.pom
   ```
4. **Bump the pin in the next repository up, alone, in its own PR**, and let CI build it. One
   bump per PR: when the train is moving, a red check should name one suspect.
5. **Repeat down the list.** A change that reaches the server from contracts crosses four
   release boundaries, so plan for four merges and four Central waits — not one afternoon.

**Bump every coordinate from one repository in the same PR, to the same version.** Each
repository publishes all of its coordinates on **one version line**, so a catalog holding two
versions from the same train puts two releases' worth of one library on a single classpath.
A catalog that holds two versions from one train should carry a comment saying why it is split
and what retires it, or be collapsed. (`compose-preview-daemon` carried exactly that split at
`composeai-contracts = "3.0.0"` / `3.1.1` until it collapsed to one ref.)

**The exception is a dependency that crosses repositories on one classpath.** When a third-party
library that several repositories call directly makes an API break, as ClassGraph 5 did, the
repositories that share a runtime classpath have to move it together with the pins that carry the
port. compose-ai-tools' `cli` and `render-host` load `daemon-core`, and the server and
compose-preview-mcp load both. So each downstream PR bumps the library *and* the upstream pins in
one change (compose-preview-server#1852, compose-preview-mcp#145). Splitting them leaves a 4.x
caller on a 5.x classpath, which is a `NoSuchMethodError` that no compile step catches. Say so in
the PR body, and keep everything else out of that PR.

**Skip a repository only when nothing in it consumes the change.** rc-players sits between the
daemon and compose-ai-tools in the order above, but it consumes only four coordinates; a daemon
release that touches none of them does not need an rc-players release to reach compose-ai-tools.
Check the catalog rather than assuming the chain is dense.

### What makes it rough, and what to do instead

**Do not batch an external-dependency sweep with an inter-repo bump.** They fail differently.
An external bump fails at compile or at a pixel; an inter-repo bump fails at resolution. Landing
both in one PR means bisecting a red `check` across two unrelated causes, five times over.
Sweep externals first, let them settle, then run the train.

**Renovate ceilings are per-repository and do not propagate.** The shared preset
(`github>yschimke/renovate-config`) carries none of the compatibility floors — every one of them
lives in the consuming repository's own `packageRules`, and a module moving between repositories
does **not** bring its ceiling rule along. This has already cost this project twice: eight
Renovate PRs in `compose-preview-contracts`, each past a floor and each with green CI, because
the catalog arrived from here whole and the ceiling rules did not (see that repository's
`AGENTS.md`); and `rc-players`, which held the skiko `PathConic` seams that block Compose
Multiplatform 1.12.0 while carrying **no `packageRules` at all**.

> **Green CI is not evidence that a ceiling held.** A ceiling protects a *published ABI floor* or
> a *runtime pairing* — a consumer in another repository, or a native library loaded at render
> time. Neither is on this build's classpath, so neither can turn this build red. That is the
> whole reason the ceiling is a Renovate rule and not a test.

**When a module moves, move its ceiling rule in the same change** — and check that the rule still
*applies* at the destination. The two failure modes are symmetric and both are live:

* a ceiling **rule** left behind, so nothing stops the drift; and
* a ceiling **comment** carried across without its rule, so the catalog reads as if something is
  enforced when nothing is. `rc-players`' `compose-bom-compat` comment claimed a constraint in a
  file that repository has never had, and justified it in terms of `renderer-android` — a module
  in a different repository. The ref was free to float the whole time.

**Check the version line a coordinate is on, not just its number.** All five repositories publish
into `ee.schimke.composeai`, so a coordinate's group says nothing about which repository owns it
or which line it is on. `rc-players` pins `common-io` and `data-layoutinspector-core` at
`2.18.0` — compose-ai-tools' pre-split number for coordinates that `compose-preview-contracts`
has owned since the extraction and has since taken to `3.1.1`. The number looks like a mild lag
and is actually a repository boundary.

**Clear stuck draft releases before cutting the next one.** A Release that is still a draft means
the Central upload did not finish — the artifacts are not there, whatever the tag says.
`compose-preview-contracts` is carrying one at `v3.23.0`: its tag exists but nothing at 3.23.0
is on Central, behind published later versions. Re-run the release workflow for the tag and
let the finalize step un-draft it, or delete the draft; do not leave a tag that consumers can see
and cannot resolve.

**Do not hand-write the next version anywhere.** `.release-please-manifest.json` records the
**last released** version, not the next one. Writing the version being released into it tells
release-please that version already shipped and makes it propose the one after a number nothing
has tagged.

**A GitHub release is not a Maven version.** compose-ai-tools cuts a GitHub Release on every
merge of its release PR, but publishes to Central only the modules that changed
([`RELEASING.md`](../RELEASING.md), "Not every release publishes"). A release that changed no
published module uploads nothing, not even the BOM, so `v2.76.0`–`v2.78.0` exist as tags and CLI
tarballs while `compose-ai-tools-bom` went from `2.75.0` straight to `2.79.0`. That is correct, and
there is nothing to "pick up":

- **A Maven consumer** (the server, compose-preview-mcp, the vscode plugin pin, rc-players, xr)
  needs no bump for a skipped release. It changed none of the coordinates that consumer
  resolves, so the pin it has already resolves the same bytes. Renovate only proposes versions
  Central serves, so it never offers a skipped one. Do not publish a marker BOM to make the
  numbers line up. It would spend Central quota (file count, size and release count are metered
  per organisation) on an artifact with no new content.
- **A GitHub-asset consumer** (the CLI installer, the `install` action, the agent plugins)
  follows GitHub releases. It gates on the `compose-preview-maven-ready-<version>.json` marker,
  which records the Maven line that release's CLI resolves.
- **A hand-written Maven pin** must name a version Central serves. Read it from the marker's
  `pluginVersion` or from `compose-ai-tools-bom`'s `maven-metadata.xml`, never from the newest
  GitHub tag. For a release you are waiting on, check
  `curl -sI https://repo1.maven.org/maven2/ee/schimke/composeai/compose-ai-tools-bom/<v>/compose-ai-tools-bom-<v>.pom`.

**A derived pin needs automation, or every Renovate bump of it stays red.** compose-preview-vscode
writes down `composePreviewDaemon`, the daemon release the pinned plugin's BOM imports, and vendors
protocol fixtures at that tag. Renovate can move the plugin pin but can't derive the other two.
So its plugin-pin PRs failed `check-daemon-pin.mjs` and sat red, and the extension fell six
releases behind (2.74.1 while 2.79.0 was on Central). Anything a repository derives from an
upstream release belongs in a workflow that runs on the Renovate branch, as the server's
`Regenerate goldens` does.

## Checklist

For each repository, in order, lowest first:

- [ ] The change is in the lowest repository that can hold it.
- [ ] External dependency sweep landed separately and settled.
- [ ] Every ceiling rule for a module that moved came with it, and still applies here.
- [ ] No stuck draft release from a previous cut.
- [ ] Release PR merged; GitHub Release **un-drafted**.
- [ ] Coordinate resolves from Maven Central for real.
- [ ] Next repository up: one PR, all coordinates from this train, same version, CI green.
- [ ] Pins name versions Central serves, not GitHub tags: a release that skipped Maven needs no bump.
