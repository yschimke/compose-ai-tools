# `:samples:desktop-daemon-bench`

Desktop latency baseline harness for the preview-daemon work — the
counterpart to [`:samples:android-daemon-bench`](../android-daemon-bench)
and [`docs/daemon/DESIGN.md` § 13](../../docs/daemon/DESIGN.md).

## Why a separate sample (Option A)

Mirrors the P0.1 reasoning: `:samples:cmp` mixes a `@PreviewParameter`
provider (4 fan-outs across `SwatchPreview`) and an `App()` composable that
isn't a trivial leaf — both inflate the per-render row and conflate per-
process JVM startup with composition time. This module ships **5 trivial
`@Preview` functions** matching the Android bench shape-for-shape so the
two CSVs compare like-for-like.

## Tasks

- `./gradlew :samples:desktop-daemon-bench:renderPreviews` — renders all
  five previews to `build/compose-previews/renders/`. Smoke test that the
  module builds and the desktop renderer (`renderer-desktop`) wires up.
- `./gradlew :samples:desktop-daemon-bench:benchPreviewLatency` — runs the
  full bench matrix (3 scenarios × 3 reps × 5 phases = 45 measurements) and
  appends desktop rows to
  [`docs/daemon/baseline-latency.csv`](../../docs/daemon/baseline-latency.csv).
  The first time it sees the CSV in the legacy P0.1 layout (no `target`
  column) it migrates existing rows by prepending `android,`. Plan for
  ~5–10 min wall time on the reference machine.

## Phases measured

Mirrors P0.1's table; the desktop equivalents differ on `forkAndInit` and
`render` because the desktop renderer has no shared sandbox:

| Phase         | How measured                                                                                |
| ------------- | ------------------------------------------------------------------------------------------- |
| `config`      | wall of `renderPreviews --dry-run` (no actions executed)                                    |
| `compile`     | wall of `compileKotlin` in isolation (kotlin.jvm — single task, no `compileDebugKotlin`)    |
| `discovery`   | wall of `discoverPreviews` in isolation (renderer-agnostic; same task name on both targets) |
| `forkAndInit` | renderPreviews wall − sum(per-preview javaexec walls); see "Desktop divergence" below       |
| `render`      | sum of per-preview `DesktopRendererMain` `javaexec` walls (one process per preview)         |

### Desktop divergence in `render` accounting

Android renders all previews inside ONE Robolectric Test JVM that holds the
sandbox open across `@Test` methods, and the JUnit XML records per-test
times. Desktop renders one preview PER `javaexec` (see
`RenderPreviewsTask.renderWithCompose`) — there's no JUnit run, no shared
sandbox, no per-test XML.

Two consequences:

1. **`render` includes per-process startup.** The bench probes the renderer
   directly (same args `RenderPreviewsTask` builds), one `java -cp …
   DesktopRendererMainKt` invocation per preview, and times each call's
   wall. The number you see is `JVM startup + Skiko classloader init +
   Compose-Desktop runtime warmup + actual draw`, summed across previews.
   You can't separate the four without instrumenting the renderer, which
   P0.6 deliberately doesn't do.
2. **`forkAndInit` is small.** It's `renderPreviews wall − Σ probe walls`,
   which captures only Gradle's orchestration cost between/around the
   forks (the forks themselves live in `render`).

The implication for the daemon's cost model: on Android the daemon
amortises `forkAndInit` to ~zero by keeping the sandbox alive (huge win on
warm-after-edit). On desktop the daemon's addressable surface is `render`
itself — keeping a single Compose-Desktop runtime warm across previews
instead of forking N times.

## Scenarios

Identical to P0.1:

| Scenario                  | Setup before each rep                                                                                                  |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `cold`                    | `:bench:clean` first; `--no-build-cache --no-configuration-cache`                                                      |
| `warm-no-edit`            | preceding rep populated caches; nothing changes between reps                                                           |
| `warm-after-1-line-edit`  | replace a single string literal in `BenchPreviews.kt` with a unique marker before the four sub-measurements; revert    |

The `warm-after-1-line-edit` scenario uses a string-literal swap (not a
comment edit) for the same reason as P0.1: kotlinc strips comments and
downstream `.class`-hashing tasks (`renderPreviews`, `discoverPreviews`)
stay UP-TO-DATE.

## Constraints

- Mirror the Android bench shape-for-shape. If you grow the preview set,
  grow `:samples:android-daemon-bench` to match in the same commit.
- No animations, no scrolls, no `@PreviewParameter` here.
- The bench appends to a shared CSV — running both Android and desktop
  benches back-to-back is fine; order doesn't matter.
