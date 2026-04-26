# `:samples:android-daemon-bench`

Latency baseline harness for the preview-daemon work. See
[`docs/daemon/TODO.md`](../../docs/daemon/TODO.md) task **P0.1** and
[`docs/daemon/DESIGN.md` § 13](../../docs/daemon/DESIGN.md).

## Why this is a separate sample (Option A)

The existing `:samples:android` workload mixes scrolls, animation GIFs, and
`@PreviewParameter` providers — each adds hundreds of ms to the *render* row
and obscures the configuration / fork / sandbox-init phases we want to
isolate. This module ships **5 trivial `@Preview` functions** and nothing
else, so the per-render cost in the JUnit XML maps cleanly onto DESIGN § 13.

A future `:samples:android-daemon-bench:renderAll` (referenced in DESIGN § 6)
also wants a stable, drift-free workload — extending `:samples:android` for
that would re-introduce the same problem.

## Tasks

- `./gradlew :samples:android-daemon-bench:renderPreviews` — renders all
  five previews to `build/compose-previews/renders/`. Smoke test that the
  module builds and discovery wires up.
- `./gradlew :samples:android-daemon-bench:benchPreviewLatency` — runs the
  full bench matrix (3 scenarios × 3 reps × 5 phases = 45 measurements) and
  writes [`docs/daemon/baseline-latency.csv`](../../docs/daemon/baseline-latency.csv).
  Plan for ~10–15 min wall time.

## Phases measured

Mirrors DESIGN § 13's table:

| Phase         | How measured                                                     |
| ------------- | ---------------------------------------------------------------- |
| `config`      | wall of `renderPreviews --dry-run` (no actions executed)         |
| `compile`     | wall of `compileDebugKotlin` in isolation                        |
| `discovery`   | wall of `discoverPreviews` in isolation                          |
| `forkAndInit` | renderPreviews wall − sum(per-test render ms)                    |
| `render`      | sum of per-`testcase` `time=` attrs in `TEST-*.xml`              |

`forkAndInit` is a **derived** number — it captures everything the
renderPreviews Test task does that isn't inside a JUnit `@Test` body: JVM
fork startup, Robolectric sandbox bootstrap, classpath assembly, and Gradle
overhead between the build start and the first test. It's the closest
single number to "the cost the daemon eliminates by staying alive."

## Scenarios

| Scenario                  | Setup before each rep                                         |
| ------------------------- | ------------------------------------------------------------- |
| `cold`                    | `:bench:clean` first; `--no-build-cache --no-configuration-cache` |
| `warm-no-edit`            | nothing — measures the second run with everything up-to-date |
| `warm-after-1-line-edit`  | append-then-truncate a single newline to a preview file      |

The `warm-after-1-line-edit` mtime touch leaves the file byte-identical, so
we measure Gradle's reaction to "input changed" without confounding it with
"the code actually changed."

## Constraints

- Module must build under the same Gradle / AGP / JDK as other samples
  (Java 17, Kotlin 2.3.x, AGP 9.1+ — currently 9.2).
- Don't add animations, scrolls, Wear, or `@PreviewParameter` here. Add
  them to `:samples:android` if you need a heavier workload.
