# Preview daemon — in-JVM sandbox pool

> **Status:** sketch, work in progress. Foundational bridge refactor in flight; multi-worker
> orchestration and the supervisor-side wire-up are follow-ups.

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

### Layer 2 — RobolectricHost as a sandbox pool [next]

`RobolectricHost(sandboxCount: Int = 1)`. When `sandboxCount > 1`:

- `start()` launches N worker threads. Each runs `JUnitCore.runClasses(SandboxRunner::class.java)`.
  Each `SandboxRunner.holdSandboxOpen` registers itself with `DaemonHostBridge.registerSandbox` and
  receives an exclusive slot id.
- `submit(req)` hashes `req.id` (or `previewId`) modulo N, dispatches to the chosen slot's queue.
- `shutdown()` poisons every slot.

Robolectric's `Sandbox` cache (keyed on `@Config` + `InstrumentationConfiguration`) may serialise
sandbox bootstrap across worker threads — that's acceptable as long as **renders** run in parallel
once the pool is warm. Validate empirically before declaring done.

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
