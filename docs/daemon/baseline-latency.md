# Preview-daemon latency baseline — sidecar notes

Companion to [`baseline-latency.csv`](baseline-latency.csv). Captures the
machine, toolchain, and methodology behind the numbers — none of which fit
in the CSV itself.

The CSV's leading `target` column distinguishes Android (`:samples:android-daemon-bench`,
P0.1) from desktop (`:samples:desktop-daemon-bench`, P0.6) rows. Both
benches share the file; either can be re-run independently.

## Reference machine

Both targets ran on the same physical host:

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

Both benches ship **5 trivial `@Preview` functions** with matching shapes
(RedSquare, BlueLabel, GreenButton, Stack, Row) so the per-preview render
row compares like-for-like across targets.

- Android: `:samples:android-daemon-bench`, see
  [`samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt`](../../samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt)
  and the [Android module README](../../samples/android-daemon-bench/README.md).
- Desktop: `:samples:desktop-daemon-bench`, see
  [`samples/desktop-daemon-bench/src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt`](../../samples/desktop-daemon-bench/src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt)
  and the [desktop module README](../../samples/desktop-daemon-bench/README.md).

Total render set per run: **5 captures**.

## Phases (CSV column `phase`)

The `config`, `compile`, and `discovery` phases match across targets (each
is just a Gradle wall-clock measurement of an isolated task). The render
phases diverge because the two render paths are architecturally different:

| Phase         | Android (P0.1)                                                            | Desktop (P0.6)                                                            |
| ------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `config`      | wall of `renderPreviews --dry-run`                                        | wall of `renderPreviews --dry-run`                                        |
| `compile`     | wall of `compileDebugKotlin` (AGP)                                        | wall of `compileKotlin` (kotlin.jvm)                                      |
| `discovery`   | wall of `discoverPreviews`                                                | wall of `discoverPreviews`                                                |
| `forkAndInit` | renderPreviews wall − Σ(JUnit testcase `time=`) = JVM fork + Robolectric  | renderPreviews wall − Σ(per-preview javaexec walls) = Gradle orchestration between forks |
| `render`      | Σ JUnit `testcase` `time=` attrs (one shared sandbox renders all 5)       | Σ per-preview javaexec walls (one fresh JVM per preview, includes Skiko init) |

### Why the desktop split looks "wrong" at first glance

On Android, ONE Robolectric Test JVM bootstraps once and renders all 5
previews inside the held sandbox — so `forkAndInit` is the JVM+sandbox
bootstrap (large, one-time) and `render` is pure draw (small, per-test).

On desktop, `RenderPreviewsTask.renderWithCompose` calls
`execOperations.javaexec` ONCE PER PREVIEW — there's no shared sandbox. So:

- `render` includes per-process JVM startup + Skiko classloader init +
  Compose-Desktop runtime warmup, summed across previews. There's no
  cheaper way to attribute it without instrumenting the renderer (which
  P0.6 deliberately doesn't do — it bench-times the existing path
  unmodified).
- `forkAndInit` is small — only Gradle's orchestration cost between forks.

The implication for the daemon's cost model: on Android the daemon
amortises `forkAndInit` to ~zero by keeping the sandbox alive (warm-after-edit
forkAndInit ≈ 1.7s → 0). On desktop the daemon's addressable surface IS
`render` itself — keeping a single Compose-Desktop runtime warm across
previews instead of forking 5 times.

When `renderPreviews` is `UP-TO-DATE` (warm-no-edit on both targets),
`render` is reported as **0** by definition (no per-test / per-process work
happened) and `forkAndInit` collapses to "Gradle overhead with nothing to do."

## Scenarios (CSV column `scenario`)

Identical across targets:

| Scenario                  | Setup before each rep                                                                                                  |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `cold`                    | `:…:clean` first, then run with `--no-build-cache --no-configuration-cache`                                            |
| `warm-no-edit`            | preceding rep populated caches; nothing changes between reps                                                           |
| `warm-after-1-line-edit`  | replace a single string literal in `BenchPreviews.kt` with a unique marker before the four sub-measurements; revert    |

The string-literal swap (rather than a comment edit) is required: kotlinc
strips comments and downstream `.class`-hashing tasks (`renderPreviews`,
`discoverPreviews`) stay `UP-TO-DATE` for comment-only edits. A literal
swap is the smallest mutation that propagates to bytecode and forces the
full pipeline to re-execute.

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

Three things stand out:

1. **`forkAndInit` is far smaller on desktop** — 0.8s warm-after-edit vs
   1.7s on Android. No Robolectric: no `Sandbox` classloader to construct,
   no `Application` reflection, no `Configuration`/`Resources` bootstrap.
   This is the daemon-addressable surface that exists on both targets.
2. **`render` is far larger on desktop** — 9.5s warm-after-edit vs 5.5s on
   Android. That's NOT because Compose-Desktop is slower per draw — it's
   because every desktop preview pays its own JVM+Skiko+Compose-runtime
   bootstrap (5 forks × ~1.9s each). Android amortises the equivalent
   bootstrap exactly once. **The daemon's per-render floor on desktop will
   likely be lower than Android's ~1.1s** once it amortises that bootstrap
   the same way Android already does.
3. **`compile` + `discovery` cold are larger on desktop** — likely because
   the Compose-Desktop classpath drag (Skiko native artefacts, broader
   compose-multiplatform graph) inflates first-time configuration of those
   tasks. Warm-no-edit is essentially the same — once everything is up to
   date, Gradle's overhead dominates and Compose-Desktop's classpath cost
   drops out.

For the indicator-tier cost model in
[PREDICTIVE.md § 6a](PREDICTIVE.md#6a-ux-response--predicted-vs-measured-cost-model):

- The "predicted vs measured" thresholds need separate `target` lines for
  the daemon's per-render floor: Android ≈ 1.1s/preview (sandbox warm),
  desktop ≈ TBD once the daemon collapses 5 forks → 1 warm runtime, but
  empirically below 1s/preview (per-fork measured ~1.9s includes ~1s of
  amortizable bootstrap).
- The `forkAndInit` row already differs by ~900ms warm-after-edit; the
  daemon should claim ~all of it on Android, and ~half on desktop (since
  desktop's `forkAndInit` is mostly Gradle orchestration the daemon
  doesn't replace).

## Sample size & variance

3 reps per scenario × 5 phases × 2 targets = **90 rows**. Median is the
headline number; raw rows let you check spread.

In the captured runs, render-phase variance across reps that actually ran
rendering was ~3.6% (android) and ~2.5% (desktop). `forkAndInit` cold
variance is wider on both targets because it absorbs first-time
classloader/runtime init on a freshly cleaned project.

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
layout (no `target` column) it migrates by prepending `android,` to every
existing row. Either order — android-then-desktop or desktop-then-android —
produces the same final file.

## Notes for daemon comparison (future D2.1 work)

The numbers here are the **Gradle path baseline** the daemon path
(`docs/daemon/TODO.md` D2.1) needs to beat. Headline median targets:

- **Android daemon-warm focused-preview render** must be < 1s on this
  machine to meet DESIGN § 13's stated v1 target. With 5 previews
  rendering in 5.5s via the Gradle path → ~1.1s per preview is the
  per-render floor we inherit. Daemon must shave the 1.7s `forkAndInit`
  and 0.5s `config` from the warm-after-edit scenario at minimum.
- **Desktop daemon-warm focused-preview render** has a much bigger
  addressable surface — the per-fork ~1.9s is mostly amortizable. Target:
  < 0.5s/preview once Compose-Desktop runtime is shared across renders.
  The daemon's value proposition on desktop is the SAME shape as on
  Android (collapse N independent JVMs into one warm sandbox-equivalent),
  just with different starting numbers.
- **Daemon-warm no-edit** must be ~zero on both targets (no Gradle
  round-trip) to meet the < 5ms floor in DESIGN § 8 "Cost shape" table.
