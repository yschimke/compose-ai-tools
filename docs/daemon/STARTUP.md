# Daemon startup latency — analysis & options

> **Status:** analysis + a small in-flight fix. Ships timing instrumentation
> ([StartupTimings.kt](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/StartupTimings.kt))
> and the `RobolectricHost.start()` blocking change. Everything else here is
> a menu of options waiting on alignment + measurement, not committed
> direction.

## The problem

The Android daemon's first save after boot has a long cliff. Concretely
observed: the first 13 `host.submit()` calls each blocked on a
60-second `awaitSandboxReady` latch in
`RobolectricHost.publishChildLoader`; submits 14+ succeeded because by
the time they arrived, the sandbox had finished bootstrapping ~60s after
submit 1. Classic cold-start cliff with a per-submit timeout that's not
generous enough on a fresh `~/.cache/robolectric`.

The 60-second timeout was sized for warm boots ("5–15s in practice"),
which is accurate when
`~/.cache/robolectric/android-all-instrumented-{ver}-{sdk}.jar` already
exists. On a fresh checkout, it doesn't — that 150 MB jar has to come
down from Maven Central first, plus instrumentation of every class on
the daemon's classpath has to run, plus the actual sandbox boot. Cold
end-to-end: 60 s+ is normal, more on a slow network.

## Where the time actually goes

In rough order of cost on cold first run:

1. **JVM startup + Gradle daemon-classpath resolve.** ~1–2 s. Standard
   JVM cost; `java -jar daemon.jar`. Not interesting unless we're going
   for sub-second.

2. **Robolectric `InstrumentingClassLoader` instrumenting every class on
   the classpath at load time.** ~5–10 s for a 344-class daemon. This
   happens *every* boot, hot or cold. Bytecode rewriting via ASM. The
   work scales with classpath size.

3. **First-time Maven download of
   `android-all-instrumented-{ver}-{sdk}.jar` (~150 MB).** 0–60 s+
   depending on network. Cached on disk after first run; subsequent
   runs skip.

4. **Holding the sandbox open + first render.** ~50–500 ms. Negligible.

(2) is the persistent cost — it's there even after cache warms. (3)
piles on for cold-cache first runs. We can attack each, but at
different levels of effort.

## Timing instrumentation (this PR)

`StartupTimings.mark(label)` records a labelled instant on a JVM-start-
relative timeline. Marks are emitted to stderr live and buffered for a
final `summary()`. The marks already in place:

```
[+   0ms] JsonRpcServer.run() entered
[+ 20ms] RobolectricHost.start() entered
[+ 25ms] worker thread launched (Robolectric init begins)
[+8120ms] sandbox-ready latch fired                              ← (2) + (3)
[+8121ms] host.start() returned (sandbox ready)
[+8122ms] read loop entering
[+8123ms] initialize received
[+8125ms] initialize responded
[+8260ms] first renderNow received
[+8420ms] first renderFinished sent
```

Reading this timeline:

- **`worker thread launched` → `sandbox-ready latch fired`** is the
  Robolectric boot phase. On a cold cache, this is dominated by the
  android-all jar download. On a warm cache, dominated by classloader
  instrumentation.
- **`host.start() returned` → `initialize received`** is "how quickly
  did the editor send its first message after our stdio became
  responsive."
- **`initialize received` → `initialize responded`** is the daemon's
  initialize handler cost (cheap; just JSON marshalling).
- **`initialize responded` → `first renderNow received`** is the
  editor's reaction time / UI work between initialize and the first
  render.
- **`first renderNow received` → `first renderFinished sent`** is the
  cold-but-sandbox-already-warm render time. This is the steady-state
  per-render cost the daemon promises.

A `summary()` is emitted automatically once after the first
`renderFinished` lands, so a single daemon-stderr capture is enough to
see where the time went. Suppress with
`-Dcomposeai.daemon.startupQuiet=true` (marks still buffered, just not
echoed).

## In-flight fix: `RobolectricHost.start()` blocks until ready

`publishChildLoader`'s 60s `awaitSandboxReady` was racing the cold-cache
cliff per-submit. Moving the await to `RobolectricHost.start()` solves
the symptom:

- `start()` blocks until the sandbox-ready latch fires (or its budget
  runs out — default 10 minutes, configurable via
  `composeai.daemon.sandboxBootTimeoutMs`).
- `JsonRpcServer.run()` only enters its read loop after `start()`
  returns.
- The protocol model becomes `daemonReady = sandboxReady` — `initialize`
  cannot return success while the sandbox is bootstrapping.
- The client's `initialize` call hangs for the duration of cold start
  (this is fine; client-side UI shows a "warming" state).
- `publishChildLoader` is no longer the latch-wait point; it's just a
  cheap mirror of the holder's child classloader on every submit.

This is a correctness fix. It doesn't make cold start faster. The
options below do.

## Options to attack each cost, by effort

### A. Keep the daemon JVM alive across IDE sessions

**The 80% solution.** Right now the daemon exits when its parent
disconnects, so every editor restart pays cold-start. If instead the
daemon were machine-resident (like the Gradle daemon) and survived
editor restarts, the first save after `code .` hits an already-warm
sandbox. Cold start moves from "every editor open" to "every reboot."

- **Cost**: a persistent JVM eating ~500 MB–1 GB. Idle. Multiple
  daemons accumulate if the user works on multiple projects.
- **Mitigation**: supervisor evicts on memory pressure or staleness
  (e.g. no client connection for 24h).
- **Implementation**: lifecycle change only. The daemon's existing
  parent-PID-bound trust model needs a thoughtful generalisation —
  current model assumes the parent IS the client. A machine-resident
  daemon needs a different anchor (PID file lock, socket-based
  handshake).
- **Verdict**: highest leverage near-term. Most other options become
  optional once this exists.

### B. AppCDS / Class Data Sharing

Standard OpenJDK feature: record loaded class metadata in one run,
replay it in subsequent runs. The Gradle daemon already uses this; ~30–
50% startup reduction is typical.

- **Cost**: a one-time recording run; subsequent boots add `-XX:SharedArchiveFile=...`.
- **Caveat**: Robolectric's `InstrumentingClassLoader` rewrites bytecode
  at load time. If AppCDS shares the *uninstrumented* form, Robolectric
  still has to re-instrument on every boot, defeating the cache.
  **Needs a real benchmark.** Possibly limited to non-instrumented
  framework classes (Kotlin stdlib, Compose, kotlinx-serialization).
- **Verdict**: try this. It's free if it works. May be neutral if not.

### C. Cache instrumented bytecode on disk

Robolectric's instrumentation is deterministic for a given (input class,
Robolectric version, shadow set, configuration) tuple. Intercept
post-instrumentation, persist the rewritten bytes to a side cache, restore
on subsequent boots.

- **Cost**: meaningful engineering (custom `InstrumentingClassLoader`
  subclass, cache-key validation, eviction policy). Maybe two weeks of
  focused work. We'd own it; Robolectric upstream doesn't ship this.
- **Win**: boot collapses to "load N pre-instrumented `.class` bytes
  from disk." Eliminates cost (2). 60–80% reduction on warm-cache
  boots.
- **Cache invalidation key**: hash of (input class bytes, Robolectric
  version, shadow-set fingerprint). Stable as long as the user's
  classpath is stable; flushes on Robolectric bumps.
- **Verdict**: right second move *if* (A) isn't enough. Otherwise
  optional.

### D. Shared daemon supervisor across projects

One daemon process hosts per-project sandboxes. JVM startup paid once
per machine; new project = new sandbox inside the same process; warm
sandboxes survive when the user switches projects.

- Each sandbox still pays its own cost (2) on first init. JVM-startup
  amortises.
- Architecture: process supervision, classloader isolation between
  projects, IPC for "switch active project."
- Combines well with (A).
- Reference: Bazel's persistent workers, IntelliJ's shared compile
  daemon.
- **Verdict**: layer on top of (A). Only worth doing once (A) exists.

### E. JVM checkpoint/restore (CRaC, Project Leyden)

Snapshot the JVM after sandbox boot; restore from snapshot on next
start. Sub-second startup if it works.

- **CRaC**: requires specific JDK distros (Azul Zulu CRaC, OpenJDK CRaC
  builds). Not standard.
- **Project Leyden**: in early access. AOT compilation + ahead-of-time
  class linking. Long roadmap.
- **Caveat**: interaction with Robolectric's bytecode rewriting is
  unclear. May need significant engineering to make the snapshot
  contain the post-instrumentation state coherently.
- **Verdict**: research project, not next-quarter work. Revisit when
  Leyden's premain stabilises.

### F. Drop Robolectric

Use Layoutlib (Android Studio's preview engine) instead. Native;
designed for fast iterative preview; doesn't have a 5–10 s instrumentation
phase.

- **Cost**: rewrite. Different rendering surface; the daemon's whole
  interior changes. Compose-on-Android via Layoutlib has its own
  quirks.
- **Verdict**: worth scoping as a research item. Not a "fix."

## What we're doing now

This PR ships:

1. `StartupTimings` instrumentation across the boot path, so the
   timeline above is observable, not modelled.
2. The `RobolectricHost.start()` blocking change so the cold-cliff
   symptom (per-submit 60s timeouts) goes away. Cold start still takes
   the time it takes; the daemon now correctly reports "warming, not
   ready" to the client during that window.

Specifically NOT shipping (to keep this PR tight):

- (A) machine-resident daemon. **Highest priority follow-up.**
- (B) AppCDS measurement.
- (C) instrumented-bytecode cache.
- (D), (E), (F).

## Recommended sequence

1. **(A) machine-resident daemon.** Single biggest UX win for the
   smallest amount of work.
2. **(B) AppCDS measurement.** Free if it works.
3. **(C) instrumented-bytecode cache** if (A)+(B) don't get cold-cache
   warm-up under 5s.
4. Park (D), (E), (F) until measurements say they're worth it.

## Cross-references

- [DESIGN.md](DESIGN.md) — daemon architecture overview.
- [PROTOCOL.md](PROTOCOL.md) — the `daemonReady` notification / model
  this fix aligns with.
- [CLASSLOADER.md](CLASSLOADER.md) — the disposable user classloader
  story; classloader instrumentation cost (2) is a separate bucket from
  the user-classloader swap that doc covers.
- [ROBOLECTRIC-PRIMER.md](ROBOLECTRIC-PRIMER.md) — for context on what
  `InstrumentingClassLoader` actually does and why (2) is unavoidable
  without a different rendering engine or a custom cache.
