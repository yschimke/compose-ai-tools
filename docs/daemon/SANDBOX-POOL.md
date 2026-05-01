# Preview daemon — in-JVM sandbox pool

> **Status:** Layer 1 (bridge multi-slot foundation) and Layer 2 (RobolectricHost as a sandbox
> pool) are landed and working. `RobolectricHost(sandboxCount = N)` boots N distinct Robolectric
> sandboxes in one JVM, each with its own `InstrumentingClassLoader` and `SDK Main Thread`;
> renders dispatch to slots via `Math.floorMod(id, N)`. Two-sandbox boot completes in ~7s on a
> warm cache. The `RobolectricHostPoolTest` asserts distinct classloaders + stable per-slot
> dispatch.
>
> Layer 3 (supervisor wire-up — make `replicasPerDaemon` translate into `sandboxCount` on a single
> daemon JVM rather than spawning N JVMs) is a follow-up.

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

### Layer 1 — bridge multi-slot foundation [landed]

`DaemonHostBridge` is the cross-classloader handoff between the host thread and the sandbox thread
(see `DaemonHostBridge.kt` KDoc). Today it has *one* request queue, *one* sandbox-classloader ref,
*one* shutdown flag — the load-bearing single-sandbox assumption.

Refactor to be **slot-keyed**: each sandbox claims a slot at boot, gets its own queue + ref + ready
latch. `slot 0` = today's single-sandbox path; the bridge surface stays source-compatible with the
existing `RobolectricHost.submit` and `SandboxRunner.holdSandboxOpen` call sites that don't yet
opt into multi-slot.

### Layer 2 — RobolectricHost as a sandbox pool [landed]

`RobolectricHost(sandboxCount: Int = 1)` spins up N worker threads, each running
`JUnitCore.runClasses(SandboxRunner::class.java)`. Each worker bootstraps its own Robolectric
sandbox — distinct `InstrumentingClassLoader`, distinct `SDK Main Thread` — registers itself with
`DaemonHostBridge.registerSandbox` to claim a slot, then polls `slot.requests`. `submit` dispatches
via `Math.floorMod(id, sandboxCount)`. `shutdown` poisons every slot.

Default `sandboxCount = 1` preserves the pre-pool single-sandbox path bit-for-bit.

`RobolectricHostPoolTest` asserts:
- both slots accept renders (id-bucketed by `id and 1`);
- each bucket consistently sees one sandbox classloader (stable dispatch);
- the two buckets see **different** classloaders (proof the cache fix below took effect).

Constraint: `sandboxCount > 1` requires `userClassloaderHolder == null`. The disposable child
URLClassLoader is single-instance today; per-slot child loaders are layered work for the
fileChanged hot-reload path. The supervisor's production daemon path stays at `sandboxCount = 1`
until that lands.

#### Two cache-key bugs, both fixed

Getting Robolectric to actually build N sandboxes — instead of returning the same cached
sandbox for every worker — required defeating its `SandboxManager.SandboxKey` cache (which is
keyed on `InstrumentationConfiguration` equality + a few mode enums). Two interlocking subtleties:

**Bug 1: `doNotAcquirePackage` is silently ignored by `equals`/`hashCode`.** Confirmed empirically
via `javap -c` on Robolectric 4.16.1:

```
InstrumentationConfiguration.equals   → classNameTranslations, classesToNotAcquire,
                                         instrumentedPackages, instrumentedClasses,
                                         interceptedMethods
                                      → packagesToNotAcquire is NOT compared
InstrumentationConfiguration.hashCode → same set of fields; packagesToNotAcquire ignored
```

So workers with different `doNotAcquirePackage` values produce `.equals()` configurations →
`SandboxManager.getAndroidSandbox` returns the same cached sandbox → both workers' `holdSandboxOpen`
queue on a single sandbox's main-thread executor (visible in a thread dump as one
`[SDK 35 Main Thread]` with both worker JUnit threads stuck on `FutureTask.get`).

**Fix:** use `doNotAcquireClass("composeai.sandbox.uniq.RunnerN")` — `classesToNotAcquire` **is**
in `equals`, so per-worker configs become genuinely unequal and the cache builds a fresh sandbox
per worker. Synthetic class name; never resolved.

**Bug 2: `createClassLoaderConfig` is invoked twice per runner — on different threads.** With
the class-level discriminator fix in place, the next failure surfaced was:

```
RobolectricHost SandboxRunner[1] failed: The main Looper has already been prepared.
    at android.os.Looper.prepareMainLooper
    at ShadowPausedLooper.createMainThreadAndLooperIfNotAlive
    at AndroidTestEnvironment.setUpApplicationState
```

A diagnostic probe revealed both workers' SDK Main Threads had **the same** `sandboxCl` identity
hash and the same `sMainLooper` instance — i.e. Robolectric was *still* returning a shared
sandbox despite the discriminator fix. The probe of `createClassLoaderConfig` itself showed why:
the method is invoked twice for one runner instance, first on the worker thread (where the
worker-index ThreadLocal hint **is** set) and again later on the sandbox's main thread (where it
**isn't**). The second invocation produced a config without the discriminator — and that
no-discriminator config was identical across all workers, so they all collapsed onto one
cache entry.

**Fix:** snapshot the worker-index hint in the runner's constructor (which JUnit invokes on the
worker thread before the sandbox exists) into a per-instance `private val poolWorkerIndex`. Both
subsequent `createClassLoaderConfig` calls — wherever they run — read the same snapshot value
and apply the same discriminator (the runner's identity hash). Stable cache key per runner;
distinct cache keys across runners.

The fix lives in [`SandboxHoldingRunner.kt`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/SandboxHoldingRunner.kt).
KDoc on `poolWorkerIndex` and `SandboxHoldingHints.workerIndex` warn future maintainers not to
read the ThreadLocal directly inside `createClassLoaderConfig`.

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
