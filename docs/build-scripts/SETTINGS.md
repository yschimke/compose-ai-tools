# `settings.gradle.kts` — the decisions behind it

Why the settings script is shaped the way it is: the repository lanes it can
switch, the build cache policy, and why the module list is laid out flat.

`settings.gradle.kts` itself keeps only the **constraints** — the things a reader
must not break. Everything that explains *how we got here* lives on this page,
under stable anchors the script links to.

## Base conventions live in `build-logic`, not in `allprojects {}`

<a id="base-conventions"></a>

The per-project conventions (ktfmt + `googleStyle()` + the history-gate test
system property) used to live in the root build's `allprojects {}` block. They
now live in `ComposeAiBaseConventionsPlugin` (build-logic), applied by each
module via `plugins { id("composeai.base-conventions") }`.

Why per-module application rather than something central:

* Isolated Projects forbids the root build from configuring its siblings, so
  `allprojects {}` is not available.
* `gradle.lifecycle.beforeProject` cannot apply an included-build plugin either —
  its imperative `pluginManager.apply(id)` does not resolve against
  `pluginManagement`.
* Explicit per-module application keeps `googleStyle()` **typed**: the convention
  plugin's classpath carries the ktfmt type, so the call is checked rather than
  reflective.

See also [isolated-projects-autoinject.md](../isolated-projects-autoinject.md).

## The Remote Compose release / snapshot lane

<a id="remote-compose-lane"></a>

Three groups — `androidx.compose.remote`, `androidx.wear.compose.remote` and
`androidx.glance.wear` — resolve from one of two lines, selected by
`-Pcomposeai.remoteCompose`:

| Mode | Coordinates | Repository |
| --- | --- | --- |
| `release` (default) | the alpha versions pinned in `gradle/libs.versions.toml` | `google()` |
| `snapshot` | `1.0.0-SNAPSHOT` | the androidx-main post-submit build pinned by `androidxSnapshotBuildId` |

**The constraint: the trio moves together.** The three groups only work when
built against the same `remote-creation*`. The catalog comment above
`compose-remote` in `gradle/libs.versions.toml` records what a skewed pair costs
— a `NoClassDefFoundError` inside `RemoteButtonImpl` at render time, and a Wear
pin that briefly did not exist at all. So the mode flips all three keys at once
rather than letting one group straddle the two lines, and the snapshot repository
serves all three from **one** build id. That makes the agreement structural
instead of a manual cross-check against each POM.

Other properties of the snapshot lane worth knowing:

* It is pinned to a **build id**, not `snapshots/latest`, so even the snapshot
  lane stays reproducible: a new snapshot lands only when
  `androidxSnapshotBuildId` changes.
* The repository is scoped by group regex (which also picks up
  `androidx.compose.remote.foundation`) and `snapshotsOnly()`, so nothing else
  can drift onto an unreviewed snapshot, and every release coordinate keeps
  resolving from `google()` even in snapshot mode.
* Build ids age out of androidx.dev after a few weeks. If the artifacts 404, pick
  a fresh one from <https://androidx.dev/snapshots/builds>.

### Why the lane exists at all

`androidx.compose.remote.foundation` (which `wear.compose.remote:remote-material3`
needs) was, for a long time, published only on androidx-main, so the release line
could not resolve the Wear widget layer. It now ships on Google Maven, which is
what let the default flip back to `release`. The lane is kept anyway — the next
API this repo wants to exercise will land on androidx-main first.

### How the version override is applied

<a id="catalog-override"></a>

Snapshot mode rewrites the three version refs in place through
`versionCatalogs { create("libs") { … } }`, so `gradle/libs.versions.toml` keeps
exactly one set of coordinates — the released ones — and
`-Pcomposeai.remoteCompose=snapshot` is the only thing that can move them. Every
`libs.compose.remote.*`, `libs.wear.compose.remote.*` and `libs.glance.wear.*`
accessor then follows the mode with no per-module wiring, and a release build
cannot accidentally resolve one group off the snapshot line.

It has to be `create`, not `named`. `named` fails with *"VersionCatalogBuilder
with name 'libs' not found"* — the default catalog is registered after settings
are evaluated. `create("libs")` returns that same builder with
`gradle/libs.versions.toml` **already** imported (calling `from(...)` on it fails
with *"Multiple 'from' invocations"*), so the three lines override three versions
and leave every other entry as the TOML has it.

## The Robolectric snapshot probe

<a id="robolectric-snapshots"></a>

`-Pcomposeai.matrix.robolectricVersion=<version>-SNAPSHOT` feeds the snapshot
cells of the SDK compatibility matrix ([SDK_COMPATIBILITY.md](../SDK_COMPATIBILITY.md)).
Robolectric snapshots carry API 37 fixes ahead of the next stable release, which
is what lets `:samples:sdk-matrix` render at SDK 37.

Two endpoints are declared. The legacy `oss.sonatype.org` host is still reachable
but stopped accepting new snapshots during Sonatype's 2024–2025 migration to
`central.sonatype.com`; both are added so a future-published snapshot that lands
on the old host still resolves, and in practice the new one wins. Both are scoped
to `org.robolectric` so a stray snapshot artifact in another group cannot leak
in, and both are added only when the property is set, so default builds are not
slowed by extra snapshot lookups.

## `repositoriesMode = PREFER_PROJECT`

<a id="repositories-mode"></a>

Kotlin's wasmJs toolchain resolves Node.js from an Ivy repository that the Kotlin
Gradle plugin adds to the root project while `kotlinWasmNodeJsSetup` is realized.
Rejecting or ignoring that project repository makes the aggregate `check` task
fail before any tests run, because `org.nodejs:node` is not published to our Maven
repositories.

The build scripts themselves still keep dependency repositories centralized in
`dependencyResolutionManagement`; project preference exists **solely** so the
plugin-owned Node.js distribution repository remains usable.

## Build cache

<a id="build-cache"></a>

The BuildFetch remote Gradle build cache complements the local build cache
(`org.gradle.caching=true` in `gradle.properties`) by sharing task outputs across
CI runs and developer machines.

### Token resolution

The credential is the first non-blank of, in order:

1. env `BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN` (project-specific)
2. property `BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN`
3. env `BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN` (shared / general fallback)
4. property `BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN`

The project-specific name lets a developer keep a separate token per BuildFetch
project (this repo and meshcore-mobile point at different caches) in one shared
`~/.gradle/gradle.properties`; the general name lets a single token serve
everything, and is what CI exports. Env wins over a Gradle property of the same
name so CI overrides a stray local property. Each source is trimmed and
empty-checked independently, so a present-but-empty value (an unset secret that CI
still exports, say) never shadows a later source and never enables the cache with
an empty credential. When nothing resolves, the cache disables itself and the
build falls back to the local cache with no error.

### Push policy

Writes are restricted to trusted CI builds (`ON_CI=true` on main); PRs and
developer machines are read-only. The gate is value-based, so an explicit
`ON_CI=false` is honoured as read-only.

### Why the local cache stays on everywhere

<a id="local-cache-always-on"></a>

Including on the trusted main runs that push. Many writers, none of them paying a
no-cache build.

This used to be `isEnabled = !remotePushEnabled`, on the reasoning that Gradle
only pushes a task it actually *executes* — so a warm local cache resolves
everything `FROM-CACHE`, nothing is pushed, and the remote stays empty. That
reasoning holds on a developer machine with a genuinely warm cache. It does not
hold on a CI runner, and assuming it did is what starved the remote:

* `setup-gradle` restores `caches/build-cache-1` from the GitHub Actions cache,
  but reports `Save was skipped` on an exact key match — so the entry never
  accumulates. It is a frozen point-in-time snapshot, not a warm cache.
* The moment a change rotates cache keys build-wide (a build-logic edit, a
  dependency bump) that snapshot stops matching, and every task executes — and
  therefore pushes.

So on CI, "local cache on" does not suppress pushes; it suppresses the
*redundant* ones. Whatever a run genuinely had to execute still lands in the
remote. That lets every trusted main run contribute, instead of concentrating the
whole job on one designated seeder that has to build from scratch to be useful.

Why that matters here: this repo merges fast. Measured 2026-08-09, five commits
landed in the 26 minutes between a seed (`69c0517`) and the next e2e run
(`7677a3f2`) — one of them touching preview discovery, which rotates every render
key. A single seeder cannot stay ahead of that, and the jobs that could have
refreshed those keys were forbidden from pushing. Several opportunistic writers
cover it; one exhaustive writer cannot.

`gradle-cache-seed.yml` still exists as the guaranteed-to-complete baseline
writer (it is the only one that never gets cancelled), but it is no longer the
only one.

### TEMPORARY: the remote-cache kill switch (#2824)

<a id="remote-cache-kill-switch"></a>

`composeai.remoteCache=off` skips the remote cache entirely. Two entries are
stored **truncated** at rest — `12fcc978…a05d` (a `ClasspathEntrySnapshotTransform`
of `gradle-api-9.6.1.jar`) and `877aca9a…c1ec`
(`:samples:android-screenshot-test:mergeDebugResources`). Both are served as HTTP
200 with a `content-length` matching the truncated body, so nothing detects the
short read until the gzip stream runs off the end: *"Could not load from remote
cache: Unexpected end of ZLIB input stream"*. Gradle treats that as FATAL
(corruption is not a recoverable cache failure), so any build resolving either key
dies — and it cannot self-heal, because the load aborts before the task runs, so
nothing ever pushes a replacement.

`composeai.cacheSalt` is the escape hatch for exactly this, but it cannot reach
these two: it is an input property on `KotlinCompilationTask` only, and these are
an AGP task and an artifact transform (a transform's key comes from the input
artifact + transform implementation — a task input property never enters it).
Bumping the salt would leave both keys resolving to the same poisoned objects.

The local cache stays on regardless, so flipping this off degrades to local-only
rather than to no cache at all.

**To revert once the entries are evicted:** delete the `remoteCacheDisabled` flag
in `settings.gradle.kts` and the `composeai.remoteCache` line in
`gradle.properties`. Nothing else changed.

## Module layout

<a id="module-layout"></a>

### Why the `data-*` project paths are flat

<a id="flat-data-paths"></a>

Each `data/<product>/` directory carries a `core` (generic Android / Compose /
AndroidX-test code, published) and a `connector` (daemon glue, unpublished)
module — see [daemon/DATA-PRODUCTS.md](../daemon/DATA-PRODUCTS.md) § "Module split
(D2.2)".

The project paths are flat (`:data-a11y-core`) rather than nested
(`:data:a11y:core`) because Gradle resolves project dependencies by
`<group>:<projectName>`, and `:data:a11y:core`'s leaf name `core` collides with
`:daemon:core`'s — same group, same name, "by conflict resolution" substitutes one
for the other. Flat names avoid the collision; the directory layout on disk stays
nested under `data/<product>/`. Published Maven coordinates are set explicitly in
each module's `mavenPublishing { coordinates(...) }` block.

### `:cli:serve-wasm` is a fork, not an independent client

<a id="serve-wasm-fork"></a>

`:cli:serve-wasm` is an experimental Compose/Wasm client for the preview server,
exercising its public JSON/render/WebSocket contracts and staged into the CLI
distribution as `preview-ui/`.

It is a **fork**. An earlier note said it "shares no code with" the server — true
of the runtime relationship and false of the source: these are the same app as
compose-preview-server's `wasm-ui`, compiled twice. That sentence is how the two
drifted for a day with nothing noticing, shipping [#4821](https://github.com/yschimke/compose-ai-tools/issues/4821)'s
wrong picture inside `preview-ui/` while `serve` ran the fixed server.

Why two copies rather than one artifact: each repository compiles the app against
**its own** M3 catalog — `:samples:design-catalog-m3-shared` here,
`:native-catalog-m3` there — so neither build's output can stand in for the
other's. The duplication is accepted and gated:
`.github/ci/check_serve_wasm_fork.py` holds the shared sources byte-identical
against a pinned upstream SHA. **Port a change to both, then bump the pin.**

### JDK 21+ samples are gated on the running JVM

<a id="jdk21-samples"></a>

Each module under `samples/sdk21/` pulls in tooling whose own Gradle plugin is
compiled to Java 21 bytecode, and therefore cannot load on this repo's default
JDK 17 build daemon (`gradle/gradle-daemon-jvm.properties`, pinned to
`toolchainVersion=17`). Rather than bump the daemon repo-wide and force every
contributor onto JDK 21, the pin stays at 17 and inclusion is gated on
`JavaVersion.current()`. The dedicated CI workflow
`.github/workflows/samples-sdk21.yml` rewrites the daemon-jvm-properties file on
the runner (never committed) so the daemon launches on 21 and the subtree gets
exercised on every PR that touches it.

Currently just `samples/sdk21/android-metro-viewmodel` — Metro 1.x DI, whose
Gradle plugin jar targets Java 21.

### The ktfmt project-path snapshot

<a id="ktfmt-project-paths"></a>

Settings snapshots the project paths that carry ktfmt into the
`composeai.ktfmtProjectPaths` system property, for the root build's
`ktfmtCheckAll` / `ktfmtFormatAll` aggregate tasks.

Under Isolated Projects the root build cannot iterate `allprojects` to discover
its siblings, but it can depend on their tasks by path. The paths are gathered in
settings — where every project is already known — and handed to the root build
through a system property, read back via a configuration-cache-tracked
`providers.systemProperty(...)`. The channel must be closure-free: a
`gradle.lifecycle.beforeProject` closure cannot carry the list, because Isolated
Projects isolates the action and cannot serialize the captured settings-script
reference.

Two exclusions:

* Only projects **with a build script** apply `composeai.base-conventions` (and
  therefore own a `ktfmtCheck` / `ktfmtFormat` task). Container projects like
  `:daemon` / `:samples`, which exist only because of nested includes and have no
  build file, are skipped.
* The **root** project is left out: it can't apply `composeai.base-conventions`,
  because a build-logic plugin on the root classpath leaks to every subproject and
  collides with their versioned plugin aliases.

## Modules that used to be here

<a id="extractions"></a>

Settings used to carry a paragraph for each of these. They are recorded here
instead; nothing in this build depends on any of them as source.

| What left | Where it went | Why |
| --- | --- | --- |
| The preview server, and `cli/serve-web` (the Lit/Vue frontend whose bundle was committed into the server's resources) | [yschimke/compose-preview-server](https://github.com/yschimke/compose-preview-server) | The extraction [#4732](https://github.com/yschimke/compose-ai-tools/issues/4732) planned, finished. `:cli` consumes it as `ee.schimke.composeai:compose-preview-serve` (`composeai-preview-serve` in the version catalog). |
| The mobile + Wear "session viewer" client apps (`:clients:*`) | [yschimke/compose-preview-client](https://github.com/yschimke/compose-preview-client) | [#2533](https://github.com/yschimke/compose-ai-tools/issues/2533). They consumed `compose-preview serve` purely through the wire contract ([serve/SESSION-VIEWER-PROTOCOL.md](../serve/SESSION-VIEWER-PROTOCOL.md)), never a code dependency, so the lift was clean. |
| `:cli-scripting`, the Kotlin-scripting host for `compose-preview script <path>`, and the intermediate `:examples-scripting` reference | [yschimke/compose-ai-contrib](https://github.com/yschimke/compose-ai-contrib) | Step C of the clean-API carve-out ([#1084](https://github.com/yschimke/compose-ai-tools/issues/1084)). Scripting is now a standalone consumer of `:preview-data-api` + `:gradle-preview-driver`; its absence from this repo is the proof the published API is expressive enough to build features *against*, not just inside. |
| The Remote Compose players — both the Kotlin Multiplatform one written here and the vendored AndroidX embedded player it is compared against — plus the Wasm browser bundle, the iOS XCFramework and the lane-comparison recipes | [yschimke/rc-players](https://github.com/yschimke/rc-players) | Consumed as coordinates (`libs.rcplayer.*`). The dependency runs both ways and neither direction is a build-time cycle: that repository takes `data-fonts-google` and `data-layoutinspector-connector` back from this one, at released coordinates. |
| The Remote Compose design catalog (`:samples:design-catalog-remote-m3`) | [yschimke/wear-m3-catalog](https://github.com/yschimke/wear-m3-catalog) | [#4588](https://github.com/yschimke/compose-ai-tools/issues/4588) — co-located with the Wear catalog it is compared against and the kit both reproduce. `:samples:remotecompose` (the "how to preview Remote Compose" demo) stays here. |

### `:render-host` moved the other way

<a id="render-host"></a>

`:render-host` came **here** from yschimke/compose-preview-server, where it
published as `compose-preview-render-host`. It is offline behaviour, which
[design/REPOSITORY_LAYERS.md](../design/REPOSITORY_LAYERS.md) places in layer 1,
and it had zero project dependencies inside the server — it lived there only
because it was written inside the `serve` package and went along when the server
was extracted. Depending on it from `:cli` is half of the dependency cycle in
[compose-preview-server#180](https://github.com/yschimke/compose-preview-server/issues/180),
and the half with no reason to exist.

Which repository a module *belongs* in is normative, and lives in
[design/REPOSITORY_LAYERS.md](../design/REPOSITORY_LAYERS.md).
