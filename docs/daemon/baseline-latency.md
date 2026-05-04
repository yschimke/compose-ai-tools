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
| android | AGP 9.2.0, Kotlin 2.3.21, Robolectric 4.16.1 SDK 35, Compose BOM 2026.04.01 |
| desktop | Compose Multiplatform 1.10.3, Kotlin 2.3.21, kotlin.jvm plugin       |

## Workloads

Both benches ship **5 trivial `@Preview` functions** with matching
shapes (RedSquare, BlueLabel, GreenButton, Stack, Row) so per-preview
render rows compare like-for-like across targets.

- Android: `:samples:android-daemon-bench`, see
  [`samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt`](../../samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt).
- Desktop: `:samples:desktop-daemon-bench`, see
  [`samples/desktop-daemon-bench/src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt`](../../samples/desktop-daemon-bench/src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt).

Total render set per run: **5 captures**.

## Phases (CSV column `phase`)

`config`, `compile`, and `discovery` match across targets (Gradle
wall-clock of an isolated task). The render phases diverge because the
two render paths are architecturally different:

| Phase         | Android (P0.1)                                                            | Desktop (P0.6)                                                            |
| ------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `config`      | wall of `renderPreviews --dry-run`                                        | wall of `renderPreviews --dry-run`                                        |
| `compile`     | wall of `compileDebugKotlin` (AGP)                                        | wall of `compileKotlin` (kotlin.jvm)                                      |
| `discovery`   | wall of `discoverPreviews`                                                | wall of `discoverPreviews`                                                |
| `forkAndInit` | renderPreviews wall − Σ(JUnit testcase `time=`) = JVM fork + Robolectric  | renderPreviews wall − Σ(per-preview javaexec walls) = Gradle orchestration between forks |
| `render`      | Σ JUnit `testcase` `time=` attrs (one shared sandbox renders all 5)       | Σ per-preview javaexec walls (one fresh JVM per preview, includes Skiko init) |

On Android, ONE Robolectric Test JVM bootstraps once and renders all 5
inside the held sandbox — `forkAndInit` is the JVM+sandbox bootstrap
(large, one-time) and `render` is pure draw (small, per-test).

On desktop, `RenderPreviewsTask.renderWithCompose` calls
`execOperations.javaexec` ONCE PER PREVIEW. So `render` includes
per-process JVM startup + Skiko classloader init + Compose-Desktop
runtime warmup, summed across previews. `forkAndInit` is small — only
Gradle's orchestration cost between forks.

When `renderPreviews` is `UP-TO-DATE`, `render` is reported as **0** by
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
# Android — see :samples:android-daemon-bench/README.md
./gradlew :samples:android-daemon-bench:benchPreviewLatency

# Desktop — see :samples:desktop-daemon-bench/README.md
./gradlew :samples:desktop-daemon-bench:benchPreviewLatency
```

Wall time on the reference machine: **~85 s** (android), **~170 s**
(desktop — desktop's per-preview forks dominate). Both tasks do ~36
sub-builds. Neither is configuration-cache compatible (they shell out to
nested `./gradlew` invocations), so expect the CC entry to be discarded
each invocation.

The desktop bench appends rows to the CSV; if it sees the legacy P0.1
layout (no `target` column) it migrates by prepending `android,` to
every existing row. Either order produces the same final file.
