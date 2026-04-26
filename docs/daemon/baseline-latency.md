# Preview-daemon latency baseline — sidecar notes

Companion to [`baseline-latency.csv`](baseline-latency.csv). Captures the
machine, toolchain, and methodology behind the numbers — none of which fit
in the CSV itself.

## Reference machine

- **Host:** `kodurock`, Linux 7.0.0 (CachyOS, x86_64)
- **CPU:** AMD Ryzen 9 3900X (12C / 24T)
- **RAM:** 32 GiB
- **JDK:** OpenJDK 21.0.10 (build 21.0.10+7)
- **Gradle:** 9.4.1 (via `./gradlew`)
- **AGP:** 9.2.0
- **Kotlin:** 2.3.21
- **Robolectric:** 4.16.1, SDK 35
- **Compose BOM (stable):** 2026.04.01

## Workload

`:samples:android-daemon-bench` — five trivial `@Preview` functions, each a
single non-animated, non-scrolling capture. Total render set: **5
testcases**. See `samples/android-daemon-bench/src/main/kotlin/com/example/daemonbench/BenchPreviews.kt`
and the [module README](../../samples/android-daemon-bench/README.md).

## Phases (CSV column `phase`)

| Phase         | Captured how                                                                   |
| ------------- | ------------------------------------------------------------------------------ |
| `config`      | wall time of `./gradlew :…:renderPreviews --dry-run` — no actions executed     |
| `compile`     | wall time of `./gradlew :…:compileDebugKotlin` (in isolation; includes config) |
| `discovery`   | wall time of `./gradlew :…:discoverPreviews` (in isolation; includes config)   |
| `forkAndInit` | renderPreviews wall − sum(per-`testcase` `time=`) = JVM fork + Robolectric init + Gradle overhead |
| `render`      | sum of per-`testcase` `time=` attrs in `build/test-results/renderPreviews/TEST-*.xml`             |

`forkAndInit` is **derived**, not measured directly — it's the cost
the daemon path is designed to amortise to ~zero by keeping the sandbox
alive. When `renderPreviews` is `UP-TO-DATE` (warm-no-edit + some
warm-after-1-line-edit reps where kotlinc emitted identical bytecode),
`render` is reported as **0** by definition (no per-test work happened) and
`forkAndInit` collapses to "Gradle overhead with nothing to do."

The `--dry-run` config row each scenario also doubles as a sanity check —
it's the floor below which Gradle invocation can't go regardless of caching.

## Scenarios (CSV column `scenario`)

| Scenario                  | Setup before each rep                                                                                                  |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `cold`                    | `:…:clean` first, then run with `--no-build-cache --no-configuration-cache`                                            |
| `warm-no-edit`            | preceding rep populated caches; nothing changes between reps                                                           |
| `warm-after-1-line-edit`  | replace a single string literal in `BenchPreviews.kt` with a unique marker before the four sub-measurements; revert after |

The `warm-after-1-line-edit` scenario uses a **string-literal swap** rather
than a comment edit because kotlinc strips comments and downstream
`.class`-hashing tasks (`renderPreviews`, `discoverPreviews`) stay
`UP-TO-DATE` for comment-only edits. A literal swap is the smallest input
mutation that propagates to bytecode and forces the full pipeline to
re-execute.

## Sample size & variance

3 reps per scenario × 5 phases = **45 rows**. Median is the headline
number; raw rows let you check spread.

In the captured run, render-phase variance across the 6 reps that actually
ran rendering (3 cold + 3 warm-after-edit) was ~3.6% — tight enough that
the median is robust. `forkAndInit` cold variance (2661–4041 ms) is wider
because it absorbs Robolectric sandbox init, which is GC-sensitive on a
freshly cleaned project.

## Running it

```sh
./gradlew :samples:android-daemon-bench:benchPreviewLatency
```

Wall time on the reference machine: **~85 s**. The task does ~36 sub-builds
of `:…:renderPreviews` and friends. It's not configuration-cache compatible
(it shells out to a nested `./gradlew`), so expect the CC entry to be
discarded each invocation.

Bench overwrites `baseline-latency.csv` each run. To preserve a previous
baseline, rename or commit it first.

## Notes for daemon comparison (future D2.1 work)

The numbers here are the **Gradle path baseline** the daemon path
(`docs/daemon/TODO.md` D2.1) needs to beat. Headline median targets:

- **Daemon-warm focused-preview render** must be < 1s on this machine to
  meet DESIGN § 13's stated v1 target. With 5 previews rendering in 5.5s
  via the Gradle path → ~1.1s per preview is the per-render floor we
  inherit. Daemon must shave the 1.7s `forkAndInit` and 0.5s `config` from
  the warm-after-edit scenario at minimum.
- **Daemon-warm no-edit** must be ~zero (no Gradle round-trip) to meet
  the < 5ms floor in DESIGN § 8 "Cost shape" table.
