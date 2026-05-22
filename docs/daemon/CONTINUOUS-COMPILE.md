# Continuous-compile worker — spike

**Status:** opt-in spike behind `composePreview.daemon.continuousCompile`.
Default is off. This document describes the design, what we expect to
measure, and the exit criteria for either landing it as the default or
abandoning it for a stage-2 attempt that hosts the Kotlin Build Tools
API inside the daemon JVM directly.

## Why

[`baseline-latency.csv`](baseline-latency.csv) ("warm-after-1-line-edit")
shows roughly **2 s of Gradle plumbing per save** before the daemon even
sees the change — config + compile + discovery walls add up to that even
when `compileDebugKotlin` itself is producing only a handful of bytes of
new bytecode. The daemon save loop already pays this on every keystroke
through `gradleService.compileOnly()` → `:<module>:composePreviewCompile`
([gradleService.ts:366](../../vscode-extension/src/gradleService.ts)).

`gradle --continuous` keeps a single Gradle invocation resident, with
configuration cached, the task graph wired, and the Kotlin compiler
warm. On each source change Gradle's file watcher fires another build
incrementally. The configuration + tooling-API overhead per save drops
from ~500 ms to one watcher notification.

This is the architectural choice JetBrains made for **Compose Hot
Reload** ([JetBrains/compose-hot-reload](https://github.com/JetBrains/compose-hot-reload),
[blog post](https://blog.jetbrains.com/kotlin/2026/01/the-journey-to-compose-hot-reload-1-0-0/));
their `Recompiler` module launches a continuous Gradle daemon and
broadcasts changed class files over a socket. We're not copying their
hot-swap path (the daemon already has the parent/child classloader
split for that — see [CLASSLOADER.md](CLASSLOADER.md)) — just borrowing
the Gradle-as-long-running-compiler idea.

DESIGN.md § 1 still lists "hot kotlinc / compile-daemon integration"
as a non-goal; this is the first explicit exception. The flag remains
opt-in until the spike is either promoted or retired.

## How

```
                                       ┌─────────────────────────────────┐
   onDidSaveTextDocument               │ gradle --continuous             │
       │                               │   :<module>:composePreviewCompile│
       ▼                               │                                  │
   gradleService.compileOnly(module) ──┤ ChunkLineSplitter ◀─── stdout    │
       │ (worker registered?)          │   ↳ parses                       │
       ▼                               │     Change detected, …           │
   worker.waitForNextBuild() ───┐      │     BUILD SUCCESSFUL in 420ms    │
                                │      └─────────────────────────────────┘
              ┌─ ok=true ───────┴─ resolves with the build's outcome
              │
       (existing) fileChanged({kind:"source"}) → daemon
              ↓
       (existing) renderNow → PNG
```

`ContinuousCompileWorker`
([continuousCompileWorker.ts](../../vscode-extension/src/daemon/continuousCompileWorker.ts))
owns the subprocess. State machine:

| State    | Transition trigger                                   | Effect                                                              |
| -------- | ---------------------------------------------------- | ------------------------------------------------------------------- |
| idle     | "Change detected, executing build…" line             | Records `buildStartedAtMs`, emits `buildStarted`                    |
| running  | "BUILD SUCCESSFUL in N(ms\|s)" / "BUILD FAILED in …" | Emits `buildFinished({ok, durationMs, errors})`, returns to idle    |

`waitForNextBuild()` resolves on the next outcome whose trigger landed
after the caller's clock, or on an in-flight build whose `Change
detected` is fresher than `IN_FLIGHT_ACCEPT_WINDOW_MS` (500 ms — see
the constant's comment). Stale in-flight builds, the warm-up build,
and subprocess exits all resolve as null; callers (the gradle service)
fall back to a one-shot `composePreviewCompile` invocation in that
case so the save loop never gets stuck.

`ContinuousCompileManager`
([continuousCompileManager.ts](../../vscode-extension/src/daemon/continuousCompileManager.ts))
holds one worker per module, started at daemon warm and stopped on
deactivate / `composePreview.restartDaemon`. The `GradleService` looks
the worker up at save time and delegates if one is registered.

## What we expect to measure

Hypothesis: a single Gradle invocation amortises the ~500 ms
configuration wall across every save, leaving only the kotlinc work
itself. On a 1-line edit that should compress the **2 s →
sub-500 ms** for the compile leg of the save loop. Combined with the
existing daemon (which eliminates the ~1.7 s `forkAndInit` line in
[`baseline-latency.csv`](baseline-latency.csv)), save → pixels should
land under 1 s on desktop and inside the 3 s budget on Android.

Open questions the spike is meant to answer:

1. **Does Gradle's file watcher pick up VS Code's saves reliably?**
   `WatchService` semantics on Linux/macOS/Windows + WSL/Docker have
   been historically uneven. Spike log line on every detected change
   so we can see when the watcher misses one.
2. **Does Gradle's `--continuous` debounce (~250 ms quiet period)
   conflict with rapid resaves?** The in-flight acceptance window in
   the worker is calibrated for the common case; race tests in
   [`continuousCompileWorker.test.ts`](../../vscode-extension/src/test/continuousCompileWorker.test.ts)
   cover the stale-in-flight case explicitly.
3. **Memory footprint.** A Gradle daemon held resident per module is
   real RAM. Worth instrumenting against a typical workspace
   (3 – 5 module project) before promoting the default.
4. **Compose plugin classpath rebuilds.** If the user touches
   `libs.versions.toml` or a `build.gradle.kts` the continuous build
   may need to reconfigure. Confirm it self-recovers, or tear down +
   respawn on the existing Tier-1 classpath-dirty signal.

## Exit criteria

**Promote to default** when:

- A 1-line edit save loop, measured under the harness flow used by
  [`baseline-latency.csv`](baseline-latency.csv), lands **< 1 s on
  desktop**, **< 2 s on Android**, on the reference machine.
- The `samples/android-daemon-bench` and `samples/desktop-daemon-bench`
  scenarios run for 10 minutes without the worker getting wedged.
- Memory delta vs the off path is acceptable on the same fixture
  workspace (rough budget: < 500 MB extra resident per module).

**Abandon, escalate to stage 2 (BTA in-daemon)** when:

- Gradle's watcher misses saves or debounces them in ways that produce
  wrong-frame renders we can't paper over.
- The per-save win on desktop is < 200 ms (i.e. the Gradle daemon's
  round-trip cost on `gradle --continuous` is itself the floor).
- The Compose plugin path can't survive a long-running daemon for
  long enough (Gradle's per-build memory growth is a known issue).

Either way, the spike is contained: dropping the flag back to `false`
removes the worker entirely from the save loop. No production code
path off the flag changes.
