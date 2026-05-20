# `contrib/` — staging area for `yschimke/compose-ai-contrib`

This directory tracks the upcoming split of all Amper and Bazel
integration code out of `compose-ai-tools` into a new dedicated repo,
[`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)
(does not exist yet).

The contrib repo consumes `compose-ai-tools` **only through published
Maven Central artifacts**. No path-based deps, no `includeBuild`. This
file is the migration plan; once the new repo is bootstrapped most of
its contents move out and only a stub link remains here.

## Why split

- The Amper/Bazel surface is fixture-shaped (samples + worked-example
  docs + opt-in CI), not core. Keeping it in the tools repo dilutes the
  "this is the published toolchain" message and slows PRs that don't
  touch it.
- The CLI (`cli/`) is Gradle-specific by design — its `GradleConnection`
  is the Tooling-API client and `AutoInject` wires the plugin via
  `--init-script`. Replicating that shape for Amper/Bazel would mean
  forking the CLI per build system, which we want to avoid.
- The daemon (`daemon/core` + `daemon/desktop` + `daemon/android`) and
  MCP server (`mcp/`) are already build-system-agnostic — they consume
  `daemon-launch.json` + `previews.json` on disk. Any build system that
  can produce those files can drive them.

## Architectural cut

```
┌──────────────────── compose-ai-tools (this repo) ────────────────────┐
│                                                                       │
│  PUBLISHED ARTIFACTS (Maven Central — ee.schimke.composeai:…)         │
│    daemon-core, daemon-desktop, daemon-android                        │
│    renderer-desktop, renderer-android                                 │
│    data-*-core, data-*-connector  (a11y, theme, history, …)          │
│    render-session-api, render-session-subprocess                      │
│    render-session-embedded-desktop                                    │
│    preview-annotations                                                │
│    gradle-plugin   (Gradle-only consumers)                            │
│                                                                       │
│  NEW HOOKS NEEDED (extracted from gradle-plugin internals):           │
│    preview-discovery        ← ClassGraph scan, currently 1392 LOC     │
│                               inside DiscoverPreviewsTask             │
│    daemon-launch-builder    ← typed builder for daemon-launch.json,   │
│                               currently DaemonClasspathDescriptor     │
│                               (internal) + AndroidPreviewClasspath    │
│                               helpers in the gradle plugin            │
│    render-cli               ← ~30 LOC main wrapping                   │
│                               SubprocessRenderSessions.open(...)      │
│                                                                       │
│  CLI (Gradle-only, stays here):                                       │
│    compose-preview  ← GradleConnection / AutoInject / Tooling API     │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘

┌──────────────── yschimke/compose-ai-contrib (future) ─────────────────┐
│                                                                       │
│  NO CLI of its own. Just build-system-native glue invoking the        │
│  published hooks via `java -jar`:                                     │
│                                                                       │
│  bazel/                                                               │
│    rules/                                                             │
│      compose_preview.bzl       ← discover_previews,                   │
│                                  build_daemon_descriptor,             │
│                                  render_previews rules                │
│    samples/                                                           │
│      resources/                ← ex-samples/bazel                     │
│      apk/                      ← ex-samples/bazel-apk                 │
│                                                                       │
│  amper/                                                               │
│    rules/                      ← (TBD — Amper's extension model is    │
│                                  still maturing; may be a separate    │
│                                  helper jar invoked from module.yaml) │
│    samples/                                                           │
│      android/                  ← ex-samples/amper-android             │
│      cmp-desktop/              ← ex-samples/amper-cmp-desktop         │
│                                                                       │
│  contract-tests/                                                      │
│    AmperContractTest           ← moved from                           │
│                                  :render-session-subprocess:test      │
│                                  (consumes published artifacts only)  │
│                                                                       │
│  docs/                                                                │
│    amper.md                    ← lifted from                          │
│    bazel.md                       NON_GRADLE_INTEGRATION.md           │
│                                  worked-example sections              │
│                                                                       │
│  .github/workflows/                                                   │
│    amper-android.yml           ← lifted verbatim                      │
│    bazel.yml                   ← lifted verbatim                      │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

## Three-phase plan

### Phase A — Extract the hooks (in `compose-ai-tools`, blocking)

These extractions are **prerequisites** for compose-ai-contrib to consume
the toolchain via published artifacts without re-implementing internals.

- [ ] **`:preview-discovery`** — new module. Pure JVM library wrapping
      ClassGraph; lifts the scan from `DiscoverPreviewsTask` (1392 LOC,
      `gradle-plugin/src/main/kotlin/.../DiscoverPreviewsTask.kt`).
      Provides a programmatic API and a `java -jar` main class:
      ```
      java -jar preview-discovery.jar \
        --classes <dir> [--classes <dir>...] \
        --dependency-jars <jar>:<jar>:... \
        --module <name> --variant <name> \
        --out previews.json
      ```
      `DiscoverPreviewsTask` becomes a thin Gradle adapter around the
      same library. Published as `ee.schimke.composeai:preview-discovery`.

- [ ] **`:daemon-launch-builder`** — new module. Promotes
      `DaemonClasspathDescriptor` from `internal` to a public typed
      builder; lifts the classpath/JVM-args/sysprop assembly from
      `gradle-plugin/.../daemon/DaemonBootstrapTask.kt` +
      `gradle-plugin/.../AndroidPreviewClasspath.kt` into pure-JVM code.
      Programmatic API + `java -jar` main:
      ```
      java -jar daemon-launch-builder.jar \
        --module <path> --variant <desktop|debug|release> \
        --renderer-classpath <jar>:<jar>:... \
        --user-classpath <jar>:<dir>:... \
        --previews-json <path> \
        --render-output-dir <dir> \
        --out daemon-launch.json
      ```
      Published as `ee.schimke.composeai:daemon-launch-builder`.
      **Bonus:** the duplicated `writeDescriptor` helper in
      `NonGradleContractTest` + `AmperContractTest` goes away.

- [ ] **`:render-cli`** — new module. ~30 LOC main wrapping
      `SubprocessRenderSessions.open(config).renderNow(...)`:
      ```
      java -jar render-cli.jar \
        --descriptor daemon-launch.json \
        --workspace-root <dir> \
        --previews Foo.Greeting,Bar.Welcome
      ```
      Published as `ee.schimke.composeai:render-cli`.

Each new module wires `composeai.maven-publishing` and rides the existing
`release-please` flow.

### Phase B — Bootstrap `yschimke/compose-ai-contrib`

- [ ] Create the repo with the layout shown above.
- [ ] All deps via Maven Central; nothing path-based.
- [ ] Lift the four sample dirs and the two CI workflows from
      `compose-ai-tools`.
- [ ] Rewrite `AmperContractTest` to depend on
      `ee.schimke.composeai:render-cli` + `:render-session-subprocess`
      from Maven Central instead of `project(":render-session-subprocess")`.
- [ ] Lift the "Worked example: JetBrains Amper" + "Worked example:
      Bazel" sections out of `docs/NON_GRADLE_INTEGRATION.md`.

### Phase C — Cut over from this repo

- [ ] Delete `samples/{amper-android,amper-cmp-desktop,bazel,bazel-apk}/`.
- [ ] Delete
      `render-session/subprocess/src/test/.../AmperContractTest.kt`.
- [ ] Delete `.github/workflows/{amper-android,bazel}.yml`.
- [ ] Slim `docs/NON_GRADLE_INTEGRATION.md` to just the contract
      sections (daemon-launch.json schema, previews.json schema,
      classpath layering, sysprops). Add a one-line link to
      compose-ai-contrib's worked-example docs.
- [ ] Replace this `contrib/` directory with a stub `contrib/README.md`
      that points at the new repo.

## What stays in `compose-ai-tools`

- All published artifacts (the toolchain itself).
- `docs/NON_GRADLE_INTEGRATION.md` minus the worked-example sub-sections
  — the contract is the published interface and belongs here.
- `render-session/subprocess/src/test/.../NonGradleContractTest.kt` —
  proves the contract is portable without naming any specific build
  system.
- Doc-comment mentions of Bazel/Amper as illustrative non-Gradle
  consumers in `notification-preview-runtime/` and `renderer-android/`.

## Simplifications this move enables

Independently valuable, listed here for visibility:

- **Plugin slims down.** `DiscoverPreviewsTask` shrinks from 1392 LOC
  (Gradle wiring + ClassGraph scanning + multi-preview fan-out) to a
  thin `@TaskAction` over the new library.
- **Descriptor builder stops being duplicated.** Today the gradle plugin
  has the canonical builder (`internal`), and the two contract tests
  each re-implement the same `writeDescriptor(...)` helper. Phase A
  collapses three implementations into one.
- **`:render-session-subprocess:test` becomes generically "JSON-RPC
  contract verified."** Today it has one build-system-agnostic test
  (`NonGradleContractTest`) and one Amper-specific test
  (`AmperContractTest`). After Phase C the module's test suite has no
  build-system bias.
- **Sample tree gets less misleading.** Four of `samples/`'s 17
  directories are non-Gradle fixtures excluded from `settings.gradle.kts`
  by convention only. Moving them out makes the "every dir under
  `samples/` is a Gradle subproject" rule a real rule.
- **CI sheet trims.** `.github/workflows/{amper-android,bazel}.yml` are
  path-filtered opt-in jobs that almost never trigger on tools-repo PRs.

## Open questions

These should be resolved before Phase A starts:

1. **Should `:preview-discovery` ship a CLI main, or just a library?**
   Argument for CLI: Bazel rules invoke `java -jar` cleanly. Argument
   against: a CLI is overhead for Gradle/JVM consumers who can just call
   the API.
2. **Does `:daemon-launch-builder` need to handle Android classpath
   layering?** `AndroidPreviewClasspath` is non-trivial (AGP
   `artifactView` resolution, R.jar appending). Pure JVM lift may need
   to leave the Android-specific layering in the gradle plugin and
   expose a generic "assemble these jars into a daemon descriptor" API.
3. **Amper's extension model.** Amper 0.10 doesn't have first-class
   plugin support yet. The contrib repo's Amper integration may be just
   "shell scripts that invoke `java -jar` between Amper task runs," not
   real Amper plugins. Track [AMPER-471](https://youtrack.jetbrains.com/projects/AMPER/issues/AMPER-471).
4. **`:render-cli` vs giving `DaemonMain` a one-shot mode.** Adding
   `--render-once` to `DaemonMain` avoids a new published artifact but
   couples the JSON-RPC server to a CLI parsing concern. Separate
   `:render-cli` is cleaner.

## Current state

This `contrib/` directory currently contains only this README. No file
moves have happened yet — Phase C waits on Phase A + B.
