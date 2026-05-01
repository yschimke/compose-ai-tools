# Preview daemon — in-JVM sandbox pool

> **Status:** Layer 1 (bridge multi-slot foundation) landed. Layer 2 (RobolectricHost as a sandbox
> pool) is now **partially unblocked**: the cache-key bug that collapsed the pool to a single
> shared sandbox is fixed (use `doNotAcquireClass` not `doNotAcquirePackage` — see "Layer 2 —
> empirical finding" below), and two distinct sandbox classloaders now bootstrap. A second
> blocker remains: `Looper.sMainLooper` appears non-null when sandbox B's
> `AndroidTestEnvironment.setUpApplicationState` runs, even though the class is sandbox-loaded.
> Root cause not yet pinned; needs deeper Robolectric source reading or a thread-dump-of-the-other-sandbox
> diagnostic.

## Motivation

Today, every replica of a (workspace, module) daemon is a **separate JVM subprocess** spawned by
`DaemonSupervisor` (see `DaemonSupervisor.replicasPerDaemon`, `SubprocessDaemonClientFactory`). On
defaults that's ~2 GB resident per replica:

| Cost                    | Per-JVM | Per-sandbox-classloader |
|-------------------------|--------:|------------------------:|
| JVM baseline            | ~200 MB | —                       |
| Native heap (Skia / lib)| ~540 MB | —                       |
| `android-all` framework | ~250 MB | (re-instrumented per loader) |
| User heap + bytecode    | ~1 GB   | ~1 GB                   |

Native libraries and JVM baseline are **once-per-JVM** — duplicating them per replica is pure waste
when replicas serve the **same module** (same classpath, same SDK, same native libs). For
`replicasPerDaemon = 4` on one module, today's cost is ~8 GB; ~5 GB is achievable in one JVM with
four sandbox classloaders.

This is the case explicitly bracketed by DESIGN.md § 4's renderer-agnostic surface — the per-module
classpath constraint that forces separate JVMs *across* modules does **not** apply within a module.

## What changes

The pragmatic path: replicas-of-the-same-module become **sandbox classloaders inside one daemon
JVM**, not separate JVMs.

```
                    today                                pragmatic-path
   ┌──────────────────────────┐         ┌──────────────────────────────────┐
   │ supervisor (mcp)         │         │ supervisor (mcp)                 │
   │   replicasPerDaemon = 4  │         │   replicasPerDaemon = 4          │
   │     ├─ JVM-A 2 GB        │         │     └─ JVM 2 GB + 3×sandbox 1 GB │
   │     ├─ JVM-B 2 GB        │   →     │        ├─ sandbox 0  ─┐          │
   │     ├─ JVM-C 2 GB        │         │        ├─ sandbox 1   │ shared   │
   │     └─ JVM-D 2 GB        │         │        ├─ sandbox 2   │ JVM      │
   │   total ≈ 8 GB           │         │        └─ sandbox 3  ─┘          │
   └──────────────────────────┘         │   total ≈ 5 GB                   │
                                        └──────────────────────────────────┘
```

**What stays the same.** Replicas across **different modules** (different classpath /
Compose-version / native-lib graph) keep separate JVMs — the renderer-agnostic surface and the
per-loader native-lib limit make that boundary load-bearing. The win is purely intra-module.

## Layered plan

### Layer 1 — bridge multi-slot foundation [in progress]

`DaemonHostBridge` is the cross-classloader handoff between the host thread and the sandbox thread
(see `DaemonHostBridge.kt` KDoc). Today it has *one* request queue, *one* sandbox-classloader ref,
*one* shutdown flag — the load-bearing single-sandbox assumption.

Refactor to be **slot-keyed**: each sandbox claims a slot at boot, gets its own queue + ref + ready
latch. `slot 0` = today's single-sandbox path; the bridge surface stays source-compatible with the
existing `RobolectricHost.submit` and `SandboxRunner.holdSandboxOpen` call sites that don't yet
opt into multi-slot.

### Layer 2 — RobolectricHost as a sandbox pool — empirical finding [partial]

The straightforward shape — `RobolectricHost(sandboxCount: Int = 1)` spinning up N worker threads,
each running `JUnitCore.runClasses(SandboxRunner::class.java)` with a synthetic discriminator on
the `InstrumentationConfiguration` so Robolectric's sandbox cache builds a fresh sandbox per
worker — is prototyped on this branch. Concrete shape:

- `RobolectricHost(sandboxCount = N)`, `submit` hashes `id` to a slot, `shutdown` poisons every
  slot's queue.
- `SandboxRunner.holdSandboxOpen` calls `DaemonHostBridge.registerSandbox(this.javaClass.classLoader)`
  and polls `slot.requests`.
- `SandboxHoldingHints.workerIndex` ThreadLocal carries each worker's index into
  `SandboxHoldingRunner.createClassLoaderConfig`, which adds a unique discriminator so the cache
  key differs per worker.

#### The cache-key bug, fixed

The first attempt used `doNotAcquirePackage("composeai.sandbox.uniq.workerN")` as the
discriminator. **This was wrong** — confirmed empirically via `javap -c` on Robolectric 4.16.1:

```
InstrumentationConfiguration.equals  → checks classNameTranslations, classesToNotAcquire,
                                        instrumentedPackages, instrumentedClasses,
                                        interceptedMethods
                                      → does NOT check packagesToNotAcquire
InstrumentationConfiguration.hashCode → same set of fields; packagesToNotAcquire ignored
```

So workers with different `doNotAcquirePackage` values produce `.equals()` configurations →
`SandboxManager.getAndroidSandbox` returns the **same cached sandbox** for every worker. The
first symptom was both workers' `holdSandboxOpen` queueing on a single sandbox's main-thread
executor (one `[SDK 35 Main Thread]` in the diagnostic dump, both worker JUnit threads stuck on
`FutureTask.get`).

**Fix:** use `doNotAcquireClass("composeai.sandbox.uniq.WorkerN")` instead — `classesToNotAcquire`
**is** in `equals`, so the configs become unequal and the cache builds a fresh sandbox per worker.
The synthetic class name never matches a real class; it's purely a cache-key discriminator.

#### The remaining blocker — `Looper.sMainLooper` cross-sandbox

With the cache fix in place, sandbox B (slot 1) now genuinely bootstraps a separate
`InstrumentingClassLoader` and a separate `SDK Main Thread`. But its setup fails:

```
RobolectricHost SandboxRunner[1] failed: The main Looper has already been prepared.
java.lang.IllegalStateException: The main Looper has already been prepared.
    at android.os.Looper.prepareMainLooper(Looper.java:134)
    at org.robolectric.shadows.ShadowPausedLooper.prepareMainLooper(ShadowPausedLooper.java:431)
    at org.robolectric.shadows.ShadowPausedLooper.createMainThreadAndLooperIfNotAlive(...)
    at org.robolectric.shadows.ShadowPausedLooper.resetLoopers(ShadowPausedLooper.java:336)
    at org.robolectric.android.internal.AndroidTestEnvironment.setUpApplicationState(...)
    at org.robolectric.RobolectricTestRunner.beforeTest(RobolectricTestRunner.java:309)
```

This is surprising — `android.os.Looper` is in the instrumented set and **should** be loaded
per-sandbox (sandbox B's `Looper.class` is a different `Class<?>` than sandbox A's). Sandbox B's
`Looper.sMainLooper` should be null until `prepareMainLooper` runs for the first time on sandbox
B's class. The throw at line 9 of `$$robo$$prepareMainLooper` (`if (sMainLooper != null) throw`)
indicates it isn't.

Hypotheses to investigate next, in priority order:

1. **Reflector caching across sandboxes.** `org.robolectric.util.reflector.Reflector` is in
   `PACKAGES_TO_NEVER_ACQUIRE` so the class is shared across sandboxes. If Reflector caches
   `MethodHandle`s in a static map and the cache key isn't classloader-aware, sandbox B's call
   could route to sandbox A's `Looper.prepareMainLooper`. Verify by inspecting `Reflector` source.
2. **Robolectric instrumentation hook setting state at class-init time.** `Looper`'s `<clinit>`
   calls `RobolectricInternals.classInitializing(Class)`. That hook may invoke shadow setup paths
   that touch `Looper.sMainLooper` indirectly. Verify by adding instrumentation to log every
   write to `sMainLooper`.
3. **`AndroidTestEnvironment` static state.** Despite being per-sandbox, `AndroidTestEnvironment`
   may inadvertently share state through its dependencies (e.g. `RuntimeEnvironment` which is in
   `org.robolectric` — not sure which package set).

Captured by the always-on thread-dump diagnostic in `RobolectricHost.start` and the dedicated
`SandboxPoolDiagnosticTest` so the next session has a reproducible artefact to attack.

#### Pivot, if the Looper blocker proves fundamental

The escape valve `RobolectricHost.kt`'s KDoc has flagged since v1: bypass
`RobolectricTestRunner` / `JUnitCore.runClasses` and drive Robolectric's lower-level `Sandbox`
API directly:

```
val sandbox = sandboxManager.getAndroidSandbox(instrumentationConfig, sdk, …)
sandbox.runOnMainThread { /* render here */ }
```

That gives us a `Sandbox` object whose lifecycle is decoupled from the JUnit runner — we hand
work to it via its main-thread executor without needing a `@Test` body to keep it alive. We'd
also reimplement `setUpApplicationState`'s essentials by hand (or call into `AndroidTestEnvironment`
directly). Substantial rewrite, but it sidesteps the runner's lifecycle assumptions.

### Layer 3 — supervisor wire-up [follow-up]

`DaemonSupervisor` keeps its `replicasPerDaemon` knob as the public surface. Behaviour change:
instead of forking N extra JVMs via `SubprocessDaemonClientFactory`, the supervisor spawns **one**
JVM per module and passes `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` as a sysprop on
the launch descriptor. The daemon's `DaemonMain` reads it and configures
`RobolectricHost(sandboxCount = N)`.

`SupervisedDaemon` collapses to a single `DaemonClient`; `clientForRender(previewId)` becomes a
no-op (the daemon handles affinity internally). The wire `render` call grows an optional
affinity-key param (or relies on the existing `previewId` payload) so the daemon's pool router can
land repeat renders on the same sandbox for cache locality.

The `replicasPerDaemon == 0` default keeps a single sandbox — bit-identical with today's behaviour
on disk.

## What this does NOT solve

- **Cross-module sharing.** Different `Compose` / `Kotlin` / `AGP` versions still need separate
  JVMs. `gradle-plugin/.../AndroidPreviewClasspath.kt:35` builds per-module classpaths; collapsing
  those into one classloader is a different (and fundamentally more constrained) problem.
- **OOM blast radius.** A leak in one pooled sandbox now takes down its peers in the same JVM. The
  recycle policy (DESIGN.md § 9 — heap drift > 30%, render-time drift > 50%) accounts in JVM-global
  heap, which is awkward to attribute per sandbox. v1 keeps the existing whole-JVM recycle and
  accepts the larger blast radius as the trade for the memory win.
- **Desktop replicas.** AWT EDT and Skiko's GPU context are process-global. Sibling Desktop
  previews would serialise through the EDT regardless of where they live; an in-JVM pool buys
  little until we move off-screen renders to software rendering on dedicated threads. Out of scope.

## Relation to existing docs

- DESIGN.md § 9 — sandbox bootstrap and recycle policy. The pool inherits the recycle invariants;
  per-slot recycle is a v2 follow-up.
- CLASSLOADER.md — the parent/child classloader split is per-sandbox today; the pool keeps that
  split per slot, with each slot's child loader resolving against the same module classpath.
- ROBOLECTRIC-PRIMER.md § "Native library loading" — confirms the load-once-per-JVM constraint;
  pool sandboxes share the parent-loaded native libs cleanly.
