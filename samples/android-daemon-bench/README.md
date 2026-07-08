# `:samples:android-daemon-bench`

Latency baseline harness for the preview-daemon work. See
[`docs/daemon/DESIGN.md` § 13](../../docs/daemon/DESIGN.md) and
[`docs/daemon/baseline-latency.md`](../../docs/daemon/baseline-latency.md).

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

- `./gradlew :samples:android-daemon-bench:composePreviewRender` — renders all
  five previews to `build/compose-previews/renders/`. Smoke test that the
  module builds and discovery wires up. **This is the cheap CI smoke** (wired
  into `check` and run per-PR in `ci.yml`'s build-samples job) so the module
  can't bit-rot without paying the full bench wall time.
- `./gradlew :samples:android-daemon-bench:benchPreviewLatency` — runs the
  **stage-0** bench matrix (3 scenarios × 3 reps × 5 phases = 45 measurements)
  and writes [`docs/daemon/baseline-latency.csv`](../../docs/daemon/baseline-latency.csv).
  Plan for ~10–15 min wall time.
- `./gradlew :samples:android-daemon-bench:benchCompileStages` — drives the
  **stage-1** (`gradle --continuous`) and **stage-2** (in-process Build Tools API)
  compile legs, appends their rows to the same CSV, and writes a stage-2
  graduation verdict to `docs/daemon/stage-2-verdict-android.md`. Run
  `benchPreviewLatency` first — the verdict reuses its stage-0
  `render,warm-after-1-line-edit` median as the render baseline.

Both bench tasks run weekly (and on demand) via
[`.github/workflows/daemon-bench.yml`](../../.github/workflows/daemon-bench.yml),
which uploads the CSV + verdict as artifacts. That job is **non-blocking** — it
never gates a PR.

## Stages measured

| Stage | Save loop | Driven by | Rows |
| ----- | --------- | --------- | ---- |
| 0 | per-save `./gradlew` | `benchPreviewLatency` | `config` / `compile` / `discovery` / `forkAndInit` / `render` × cold / warm-no-edit / warm-after-1-line-edit |
| 1 | resident `gradle --continuous` (`composePreview.daemon.continuousCompile`) | `benchCompileStages` | `compile,stage-1-warm-after-1-line-edit` |
| 2 | in-process BTA (`composePreview.daemon.compileInProcess`) | `benchCompileStages` | `compile,stage-2-cold-first-save`, `compile,stage-2-warm-after-1-line-edit`, `classloader-swap,stage-2-warm` |

The stage-2 `render` leg is unchanged from stage 0 (the daemon hot-swaps into
the same renderer), so the verdict reuses the stage-0 `render` median rather
than re-measuring it. Stage 2 is driven by `javaexec`-ing `:daemon:core`'s
`BtaBenchMain`, which calls the production `BtaCompileSession.compileIncremental()`
— the same code path the daemon's `compileSources` handler runs — using the
`btaCompile` block from this module's `daemon-launch.json`.

The verdict evaluates the promote/demote thresholds (< 2 s warm save→pixel on
Android; warm-path advantage over stage 1) and prints `PROMOTE CANDIDATE` /
`DO NOT PROMOTE` / `INCONCLUSIVE`. The memory-delta criterion is reported
informationally (the harness can't observe the stage-1 daemon's resident set).

## Phases measured

Mirrors DESIGN § 13's table:

| Phase         | How measured                                                     |
| ------------- | ---------------------------------------------------------------- |
| `config`      | wall of `composePreviewRender --dry-run` (no actions executed)         |
| `compile`     | wall of `compileDebugKotlin` in isolation                        |
| `discovery`   | wall of `composePreviewDiscover` in isolation                          |
| `forkAndInit` | composePreviewRender wall − sum(per-test render ms)                    |
| `render`      | sum of per-`testcase` `time=` attrs in `TEST-*.xml`              |

`forkAndInit` is a **derived** number — it captures everything the
composePreviewRender Test task does that isn't inside a JUnit `@Test` body: JVM
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
- Keep this module shape-for-shape with
  [`:samples:desktop-daemon-bench`](../desktop-daemon-bench): any preview-set
  change, and any change to the `BenchCompileStagesTask` body, must be mirrored
  in both modules in the same commit.
