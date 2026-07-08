# Preview-daemon latency baseline — sidecar notes

Companion to [`baseline-latency.csv`](baseline-latency.csv). Captures
machine, toolchain, and methodology.

The CSV's leading `target` column distinguishes Android
(`:samples:android-daemon-bench`, P0.1) from desktop
(`:samples:desktop-daemon-bench`, P0.6). Either bench can be re-run
independently.

## Reference machine

- **Host:** `kodurock`, Linux 7.0.0 (CachyOS, x86_64)
- **CPU:** AMD Ryzen 9 3900X (12C / 24T)
- **RAM:** 32 GiB
- **JDK:** OpenJDK 21.0.10 (build 21.0.10+7)
- **Gradle:** 9.4.1 (via `./gradlew`)

Toolchain pinned per target:

| Target  | Plugins / runtimes                                                  |
| ------- | ------------------------------------------------------------------- |
| android | AGP 9.2.0, Kotlin 2.3.20, Robolectric 4.16.1 SDK 35, Compose BOM 2026.04.01 |
| desktop | Compose Multiplatform 1.10.3, Kotlin 2.3.20, kotlin.jvm plugin       |

## Workloads

Both benches ship **5 trivial `@Preview` functions** with matching
shapes (RedSquare, BlueLabel, GreenButton, Stack, Row) so per-preview
render rows compare like-for-like across targets.

- Android: `:samples:android-daemon-bench`, see
  [`samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt`](../../samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt).
- Desktop: `:samples:desktop-daemon-bench`, see
  [`samples/desktop-daemon-bench/src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt`](../../samples/desktop-daemon-bench/src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt).

Total render set per run: **5 captures**.

## Stages (CSV column `scenario` prefix)

The CSV started as a stage-0 baseline (per-save `./gradlew`). Issue #1586 added
the two faster save loops that shipped behind experimental flags, driven by the
sibling `benchCompileStages` task:

| Stage | Save loop | `scenario` values | Source |
| ----- | --------- | ----------------- | ------ |
| 0 | per-save `./gradlew` | `cold`, `warm-no-edit`, `warm-after-1-line-edit` | `benchPreviewLatency` |
| 1 | resident `gradle --continuous` (`composePreview.daemon.continuousCompile`) | `stage-1-warm-after-1-line-edit` | `benchCompileStages` |
| 2 | in-process BTA (`composePreview.daemon.compileInProcess`) | `stage-2-cold-first-save`, `stage-2-warm-after-1-line-edit`, `stage-2-warm` | `benchCompileStages` |

Stage 1 rows carry `phase=compile` — the wall of one resident-Gradle rebuild,
parsed from `BUILD SUCCESSFUL in N` exactly as `ContinuousCompileWorker` does.
Stage 2 rows carry `phase=compile` (the real
`BtaCompileSession.compileIncremental()` driven via `:daemon:core`'s
`BtaBenchMain`) and `phase=classloader-swap` (the child-loader rotation that
follows a successful compile). The stage-2 `render` leg is unchanged from stage
0, so it is not re-measured — `benchCompileStages` reuses the stage-0
`render,warm-after-1-line-edit` median when it evaluates the graduation verdict
(`docs/daemon/stage-2-verdict-<target>.md`) against the promote/demote thresholds
(< 1 s warm save→pixel on desktop, < 2 s on Android, warm-path advantage over
stage 1 above 200 ms).

## Phases (CSV column `phase`)

`config`, `compile`, and `discovery` match across targets (Gradle
wall-clock of an isolated task). The render phases diverge because the
two render paths are architecturally different:

| Phase         | Android (P0.1)                                                            | Desktop (P0.6)                                                            |
| ------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `config`      | wall of `composePreviewRender --dry-run`                                        | wall of `composePreviewRender --dry-run`                                        |
| `compile`     | wall of `compileDebugKotlin` (AGP)                                        | wall of `compileKotlin` (kotlin.jvm)                                      |
| `discovery`   | wall of `composePreviewDiscover`                                                | wall of `composePreviewDiscover`                                                |
| `forkAndInit` | composePreviewRender wall − Σ(JUnit testcase `time=`) = JVM fork + Robolectric  | composePreviewRender wall − Σ(per-preview javaexec walls) = Gradle orchestration between forks |
| `render`      | Σ JUnit `testcase` `time=` attrs (one shared sandbox renders all 5)       | Σ per-preview javaexec walls (one fresh JVM per preview, includes Skiko init) |

On Android, ONE Robolectric Test JVM bootstraps once and renders all 5
inside the held sandbox — `forkAndInit` is the JVM+sandbox bootstrap
(large, one-time) and `render` is pure draw (small, per-test).

On desktop, `RenderPreviewsTask.renderWithCompose` calls
`execOperations.javaexec` ONCE PER PREVIEW. So `render` includes
per-process JVM startup + Skiko classloader init + Compose-Desktop
runtime warmup, summed across previews. `forkAndInit` is small — only
Gradle's orchestration cost between forks.

When `composePreviewRender` is `UP-TO-DATE`, `render` is reported as **0** by
definition and `forkAndInit` collapses to "Gradle overhead with nothing
to do."

## Scenarios (CSV column `scenario`)

| Scenario                  | Setup before each rep                                                                                                  |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `cold`                    | `:…:clean` first, then run with `--no-build-cache --no-configuration-cache`                                            |
| `warm-no-edit`            | preceding rep populated caches; nothing changes between reps                                                           |
| `warm-after-1-line-edit`  | replace a single string literal in `BenchPreviews.kt` with a unique marker before the four sub-measurements; revert    |

The string-literal swap (rather than a comment edit) is required:
kotlinc strips comments and downstream `.class`-hashing tasks stay
`UP-TO-DATE` for comment-only edits. A literal swap is the smallest
mutation that propagates to bytecode.

## Headline takeaways — desktop vs android

Median ms across 3 reps per (target, phase, scenario):

| Phase         | Scenario                  | android | desktop | desktop − android |
| ------------- | ------------------------- | ------- | ------- | ----------------- |
| `config`      | cold                      |    1311 |    1264 | −47               |
| `config`      | warm-no-edit              |     557 |     481 | −76               |
| `config`      | warm-after-1-line-edit    |     527 |     500 | −27               |
| `compile`     | cold                      |    1670 |    2203 | +533              |
| `compile`     | warm-no-edit              |     534 |     498 | −36               |
| `compile`     | warm-after-1-line-edit    |     909 |     972 | +63               |
| `discovery`   | cold                      |    1218 |    1748 | +530              |
| `discovery`   | warm-no-edit              |     572 |     508 | −64               |
| `discovery`   | warm-after-1-line-edit    |     695 |    1209 | +514              |
| `forkAndInit` | cold                      |    2870 |    1464 | −1406             |
| `forkAndInit` | warm-no-edit              |     598 |     511 | −87               |
| `forkAndInit` | warm-after-1-line-edit    |    1690 |     797 | −893              |
| `render`      | cold                      |    5464 |    9378 | +3914             |
| `render`      | warm-no-edit              |       0 |       0 | 0                 |
| `render`      | warm-after-1-line-edit    |    5518 |    9463 | +3945             |

## Sample size & variance

3 reps per scenario × 5 phases × 2 targets = **90 rows**. Median is the
headline. Render-phase variance across reps that actually rendered:
~3.6% (android), ~2.5% (desktop). `forkAndInit` cold variance is wider
on both targets because it absorbs first-time classloader/runtime init.

## Running it

```sh
# Stage 0 (+ render baseline the stage-2 verdict reuses).
./gradlew :samples:android-daemon-bench:benchPreviewLatency   # Android
./gradlew :samples:desktop-daemon-bench:benchPreviewLatency   # Desktop

# Stage 1 + stage 2 + graduation verdict. Run after benchPreviewLatency.
./gradlew :samples:android-daemon-bench:benchCompileStages    # Android
./gradlew :samples:desktop-daemon-bench:benchCompileStages    # Desktop
```

Both also run weekly (and on demand) via
[`.github/workflows/daemon-bench.yml`](../../.github/workflows/daemon-bench.yml),
which uploads the CSV + per-target verdict as artifacts. That workflow is
**non-blocking** — the per-PR critical path only runs the cheap
`composePreviewRender` smoke (`ci.yml` build-samples job). Shared-runner numbers
are noisier than the reference machine above; treat them as trend, not gospel.

Wall time on the reference machine: **~85 s** (android), **~170 s**
(desktop — desktop's per-preview forks dominate). Both tasks do ~36
sub-builds. Neither is configuration-cache compatible (they shell out to
nested `./gradlew` invocations), so expect the CC entry to be discarded
each invocation.

The desktop bench appends rows to the CSV; if it sees the legacy P0.1
layout (no `target` column) it migrates by prepending `android,` to
every existing row. Either order produces the same final file.
