# Preview daemon — in-JVM sandbox pool

> **Status:** Layer 1 (bridge multi-slot foundation) landed. Layer 2 (RobolectricHost as a sandbox
> pool) was attempted and **blocked at the Robolectric layer** — see "Layer 2 — empirical
> finding" below for what we tried and why it stalled. The next attempt needs to bypass the JUnit
> runner and drive Robolectric's lower-level `Sandbox` API directly.

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

### Layer 2 — RobolectricHost as a sandbox pool — empirical finding [blocked]

The straightforward shape — `RobolectricHost(sandboxCount: Int = 1)` spinning up N worker threads,
each running `JUnitCore.runClasses(SandboxRunner::class.java)` with a synthetic
`doNotAcquirePackage` discriminator on the `InstrumentationConfiguration` to defeat Robolectric's
sandbox cache — was prototyped end-to-end on `agent/sandbox-pool-multi-worker`. Concrete shape:

- `RobolectricHost(sandboxCount = N)`, `submit` hashes `id` to a slot, `shutdown` poisons every
  slot's queue.
- `SandboxRunner.holdSandboxOpen` calls `DaemonHostBridge.registerSandbox(this.javaClass.classLoader)`
  and polls `slot.requests`.
- `SandboxHoldingHints.workerIndex` ThreadLocal carries each worker's index into
  `SandboxHoldingRunner.createClassLoaderConfig`, which adds a unique
  `composeai.sandbox.uniq.worker<N>` `doNotAcquirePackage` so the cache key differs per worker.

**It does not work.** Even with **sequenced boots** (start worker 0, await its ready latch, then
start worker 1) the second sandbox's bootstrap stalls indefinitely while the first is alive in its
hold-open poll loop. Slot 0 boots and registers in ~4s; slot 1 never reaches `holdSandboxOpen` —
host.start times out at the configured `composeai.daemon.sandboxBootTimeoutMs` deadline.

The leftover-thread side effect is brutal: when slot 1's await throws, slot 0's worker is still
non-daemon and pinned in `holdSandboxOpen`, slot 1's worker is wedged in Robolectric internals. Any
test class that runs next in the same Gradle test JVM inherits the poisoned Robolectric state and
also fails to bootstrap a sandbox. That's an operational hazard worth flagging.

**Diagnosis.** With one sandbox alive in its hold-open poll loop, Robolectric's bootstrap path for
a second sandbox can't make progress. Likely culprits (not yet pinned with a thread dump): the
JUnit runner's parallel-universe lock, an internal Robolectric global, or a classloader-graph
ordering constraint. The discriminator strategy is fine — the cache key change is provable — but
sandbox **construction**, not just lookup, doesn't tolerate a co-resident live sandbox.

**Pivot.** The next attempt needs to bypass `RobolectricTestRunner` and `JUnitCore.runClasses`
entirely and drive Robolectric's lower-level `Sandbox` API directly:

```
val sandbox = SandboxBuilder.build(instrumentationConfig, sdkConfig, …)
sandbox.runOnMainThread { /* render here */ }
```

That removes the dummy-`@Test`-holds-the-sandbox-open trick and gives us a sandbox object we can
hand work to without keeping a worker thread blocked inside JUnit. It's the escalation path
RobolectricHost.kt's KDoc has flagged since v1 ("if this pattern fails for any reason we escalate
rather than silently switching to Robolectric's lower-level Sandbox API"); empirically that escalation
is now warranted.

That's a substantial rewrite — separate PR, with its own risk surface (sandbox lifecycle, error
paths, Compose/Looper threading inside the sandbox) — so this layer is parked at the design-doc
level until the lower-level approach is prototyped.

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
