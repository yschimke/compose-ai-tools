# Daemon startup latency

## The cold-start cliff

The Android daemon's first save after boot has a long cliff. Concretely
observed: the first 13 `host.submit()` calls each blocked on a 60-second
`awaitSandboxReady` latch in `RobolectricHost.publishChildLoader`;
submits 14+ succeeded because by the time they arrived, the sandbox had
finished bootstrapping ~60s after submit 1.

The 60-second timeout was sized for warm boots ("5–15s in practice"),
which is accurate when
`~/.cache/robolectric/android-all-instrumented-{ver}-{sdk}.jar` already
exists. On a fresh checkout that 150 MB jar has to come down from Maven
Central first, plus instrumentation of every class on the daemon's
classpath, plus the actual sandbox boot. Cold end-to-end: 60 s+ is
normal.

## Where the time goes

Rough breakdown on cold first run:

1. **Robolectric `InstrumentingClassLoader` instrumenting every class on
   the classpath at load time.** ~5–10 s for a 344-class daemon. Happens
   *every* boot, hot or cold. Bytecode rewriting via ASM.
2. **First-time Maven download of `android-all-instrumented-{ver}-{sdk}.jar`
   (~150 MB).** 0–60 s+ depending on network. Cached after first run.
3. **JVM startup + classpath resolve + first render.** ~1–2 s + ~50–500 ms.

(1) is the persistent cost. (2) piles on for cold-cache first runs.

## The current fix

Two changes shipped:

1. **`RobolectricHost.start()` blocks until ready.** The 60s
   `awaitSandboxReady` was racing the cold-cache cliff per-submit.
   Moving the await to `start()` fixes the symptom:
   - `start()` blocks until the sandbox-ready latch fires (or its budget
     runs out — default 10 minutes, configurable via
     `composeai.daemon.sandboxBootTimeoutMs`).
   - `JsonRpcServer.run()` only enters its read loop after `start()`
     returns.
   - `daemonReady = sandboxReady` — `initialize` cannot return success
     while the sandbox is bootstrapping.
   - `publishChildLoader` is no longer a latch-wait point; it's just a
     cheap mirror of the holder's child classloader on every submit.

   This is a correctness fix. It doesn't make cold start faster.

2. **`StartupTimings` instrumentation.**
   [`StartupTimings.kt`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/StartupTimings.kt)
   records labelled instants on a JVM-start-relative timeline. Marks emit
   to stderr live and buffer for a final `summary()`:

   ```
   [+   0ms] JsonRpcServer.run() entered
   [+ 20ms] RobolectricHost.start() entered
   [+ 25ms] worker thread launched (Robolectric init begins)
   [+8120ms] sandbox-ready latch fired
   [+8121ms] host.start() returned (sandbox ready)
   [+8122ms] read loop entering
   [+8123ms] initialize received
   [+8125ms] initialize responded
   [+8260ms] first renderNow received
   [+8420ms] first renderFinished sent
   ```

   `summary()` emits automatically once after the first `renderFinished`
   lands. Suppress with `-Dcomposeai.daemon.startupQuiet=true`.

## Future options

Menu of follow-ups, by leverage:

- **Machine-resident daemon** (highest priority). Daemon survives editor
  restarts; cold start moves from "every editor open" to "every reboot."
  Lifecycle change only; needs a different anchor than parent-PID.
- **AppCDS / Class Data Sharing.** Standard OpenJDK feature. May be
  defeated by Robolectric's load-time bytecode rewriting; needs a
  benchmark.
- **Cache instrumented bytecode on disk.** Robolectric's instrumentation
  is deterministic per (input class, Robolectric version, shadow set).
  Persist post-instrumentation bytes to a side cache. ~2 weeks of work;
  60–80% reduction on warm-cache boots.
- **Shared daemon supervisor across projects.** One JVM hosts per-project
  sandboxes. Combines well with machine-resident.
- **JVM checkpoint/restore (CRaC, Project Leyden).** Research project.
- **Drop Robolectric for Layoutlib.** Rewrite. Worth scoping as
  research.

## Cross-references

- [DESIGN.md](DESIGN.md) — daemon architecture overview.
- [PROTOCOL.md](PROTOCOL.md) — the `daemonReady` notification model.
- [CLASSLOADER.md](CLASSLOADER.md) — disposable user classloader.
- [ROBOLECTRIC-PRIMER.md](ROBOLECTRIC-PRIMER.md) — what
  `InstrumentingClassLoader` does and why cost (1) is unavoidable.
