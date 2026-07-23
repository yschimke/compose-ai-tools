# Preview daemon — in-JVM sandbox pool

## Status

All three layers landed.

- **Layer 1** — bridge multi-slot foundation.
- **Layer 2** — `RobolectricHost(sandboxCount = N)` boots N distinct
  Robolectric sandboxes in one JVM, each with its own
  `InstrumentingClassLoader` and `SDK Main Thread`; renders dispatch to
  slots via `Math.floorMod(id, N)`. Two-sandbox boot completes in ~7s on
  warm cache.
- **Layer 3** — `DaemonSupervisor` passes
  `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` on the launch
  descriptor instead of spawning N+1 JVM subprocesses. Public surface
  (`SupervisedDaemon.client`, `allClients`, `clientForRender`) is
  unchanged. **Default `replicasPerDaemon = 4`** — every daemon comes up
  with 5 in-JVM sandboxes. Set `0` to opt out.

## Memory math

For `replicasPerDaemon = 4` on one module:

| Cost                    | Per-JVM | Per-sandbox-classloader |
|-------------------------|--------:|------------------------:|
| JVM baseline            | ~200 MB | —                       |
| Native heap (Skia / lib)| ~540 MB | —                       |
| `android-all` framework | ~250 MB | (re-instrumented per loader) |
| User heap + bytecode    | ~1 GB   | ~1 GB                   |

Native libs and JVM baseline are once-per-JVM. Pre-Layer-3 ~8 GB across
5 JVMs; post-Layer-3 ~5 GB in one JVM with 5 sandbox classloaders.

```
                    today                                pool
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

Replicas across **different modules** keep separate JVMs — different
classpath / Compose version / native-lib graph. The win is
intra-module.

## Layer 1 — bridge multi-slot foundation

`DaemonHostBridge` is the cross-classloader handoff between host thread
and sandbox thread. Refactored to be **slot-keyed**: each sandbox claims
a slot at boot, gets its own queue + ref + ready latch. `slot 0` =
single-sandbox path.

## Layer 2 — `RobolectricHost` as a sandbox pool

`RobolectricHost(sandboxCount: Int = 1)` spins up N worker threads, each
running `JUnitCore.runClasses(SandboxRunner::class.java)`. Each worker
bootstraps its own Robolectric sandbox (distinct
`InstrumentingClassLoader`, distinct `SDK Main Thread`), registers
itself with `DaemonHostBridge.registerSandbox` to claim a slot, then
polls `slot.requests`. `submit` dispatches via
`Math.floorMod(id, sandboxCount)`. `shutdown` poisons every slot.

Default `sandboxCount = 1` preserves the pre-pool single-sandbox path
bit-for-bit.

`RobolectricHostPoolTest` asserts:
- both slots accept renders (id-bucketed by `id and 1`);
- each bucket consistently sees one sandbox classloader (stable
  dispatch / slot affinity);
- the two buckets see **different** classloaders.

### Cache-key fixes

Getting Robolectric to actually build N sandboxes required defeating
its `SandboxManager.SandboxKey` cache:

**`doNotAcquirePackage` is silently ignored by `equals`/`hashCode`.**
Confirmed via `javap -c` on Robolectric 4.16.1:

```
InstrumentationConfiguration.equals   → classNameTranslations, classesToNotAcquire,
                                         instrumentedPackages, instrumentedClasses,
                                         interceptedMethods
                                      → packagesToNotAcquire is NOT compared
```

**Fix:** use `doNotAcquireClass("composeai.sandbox.uniq.RunnerN")` —
`classesToNotAcquire` **is** in `equals`. Synthetic class name; never
resolved.

**`createClassLoaderConfig` is invoked twice per runner — on different
threads.** First on the worker thread (where the worker-index
ThreadLocal hint is set), again later on the sandbox's main thread
(where it isn't). The second invocation produced a config without the
discriminator.

**Fix:** snapshot the worker-index hint in the runner's constructor
into `private val poolWorkerIndex`. Both subsequent
`createClassLoaderConfig` calls read the same snapshot. Lives in
[`SandboxHoldingRunner.kt`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/SandboxHoldingRunner.kt).

## Layer 2 — empirical bench

Referenced by `SandboxPoolMemoryBench.kt`. Boots
`RobolectricHost(sandboxCount = 4)` in a fresh JVM, runs a warm-up
render per sandbox, reports heap + native-virtual delta. Sample run on
Robolectric 4.16.1 / SDK 35 / OpenJDK 17 / Linux x86_64:

```
baseline:  heap=10 MiB    nativeHeap=3758 MiB
warm (×4): heap=101 MiB   nativeHeap=4596 MiB
delta:     heap=91 MiB    nativeHeap=838 MiB
per-sandbox amortized: heap≈22 MiB  nativeHeap≈209 MiB
```

`nativeHeap` is `committedVirtualMemorySize` — a portable proxy for "JVM
resident set" that folds JVM heap + native libs + instrumented framework
JARs + mmap'd resources. The 838 MiB delta for 4 sandboxes conflates
once-per-JVM cost (Skia / sqlite / Robolectric native libs ~540 MiB;
framework loading ~250 MiB) with per-sandbox cost (per-loader
instrumented bytecode + classloader internals — ~75 MiB each).

Attribution against the subprocess model:

| replicasPerDaemon | Subprocess model | Pool model | Saved |
|------------------:|----------------:|----------:|----:|
| 0 (1 sandbox)     | ~1 GB           | ~1 GB     | 0   |
| 2 (3 sandboxes)   | ~3 GB           | ~1.15 GB  | ~1.85 GB |
| 3 (4 sandboxes)   | ~4 GB           | ~1.23 GB  | ~2.77 GB |
| 4 (5 sandboxes, default) | ~5 GB    | ~1.30 GB  | ~3.70 GB |

Bench prints to JUnit's `<system-out>` so CI logs preserve the numbers;
rerun and update the table on Robolectric / JDK / framework upgrades.

## Slot dispatch / affinity-aware dispatch

Constraint: `sandboxCount > 1` requires `userClassloaderHolder == null`.
Stable dispatch via `Math.floorMod(id, sandboxCount)` — same preview ID
goes to the same slot, so per-slot warm caches survive across renders.
The `previewId` argument on `clientForRender` is preserved for a future
affinity-aware wire change.

## Layer 3 supervisor surface

`DaemonSupervisor` keeps `replicasPerDaemon` as the public surface.
Implementation:

- `spawn(project, modulePath)` reads the descriptor, calls
  `descriptor.withSandboxCount(1 + replicasPerDaemon)` to inject
  `composeai.daemon.sandboxCount` into `systemProperties`, and forks a
  **single** JVM via `clientFactory.spawn`. The previous N+1-JVM-fork
  loop and `replicaSpawnExecutor` are gone.
- `SupervisedDaemon` is now backed by a single `DaemonSpawn`; the public
  surface — `client`, `allClients()`, `clientForRender(previewId)`,
  `replicaCount()` — is unchanged. `clientForRender` is a degenerate
  no-op (always returns the single client) but `previewId` is preserved
  for future affinity routing.
- `DaemonMain` reads `composeai.daemon.sandboxCount` (default 1) and
  constructs `RobolectricHost(sandboxCount = N)`. When the user-class
  loader holder is wired (gradle plugin's hot-reload path), sandboxCount
  falls back to 1 with a stderr warning.
- Wire-protocol-visible behaviour (`initialize`, `renderNow`,
  `fileChanged` fan-out, `classpathDirty` respawn) is unchanged.

`replicasPerDaemon == 0` keeps a single sandbox in a single JVM —
bit-identical with pre-pool behaviour (the sysprop is omitted from the
descriptor entirely at count=1).

## Per-slot child loaders for hot reload

The disposable child URLClassLoader is single-instance today; per-slot
child loaders are layered work for the `fileChanged` hot-reload path.
The supervisor's production daemon path stays at `sandboxCount = 1`
until that lands. Per-slot recycle
is the other main remaining item — heap-driven recycle could restart
one sandbox instead of the whole daemon JVM.

## Background pool boot (cold-start fast path)

`-Dcomposeai.daemon.backgroundSandboxBoot=true` (default **off**) changes
`RobolectricHost.start()`'s contract for a pool: it blocks only until
**slot 0** is registered, then boots slots 1..N-1 sequentially on a
background daemon thread. Sandbox boot is the dominant cold-start cost
(~11.6 s measured per sandbox on a warm `android-all` cache), so a
3-sandbox pool otherwise holds `initialize` — and therefore serve's first
live render — for ~35 s of capacity nothing needs yet.

Semantics under background boot:

- **Dispatch routes across the ready prefix.** `chooseSlotIndex` mods by
  the ready-slot count, not `sandboxCount`, so a render never queues on a
  still-bootstrapping sandbox. Affinity re-keys as slots come up (mod 1 →
  mod 2 → …) — a cache-warmth cost only; once the pool completes,
  dispatch is bit-identical with the eager path.
- **Interactive waits for slot 1.** `acquireInteractiveSession` awaits
  the interactive slot's readiness (bounded by the existing 30 s start
  timeout) and throws the usual clean `Unsupported` if it isn't up yet —
  callers already handle that with the v1 fallback.
- **A permanent background slot failure caps the pool** at the slots
  already booted (loudly logged) instead of aborting the daemon — the
  eager path's whole-host abort only applies to slot 0. Boot-retry
  behaviour per slot is unchanged.
- **Boot-time warm render.** Each background-booted slot renders the
  daemon-shipped `DaemonWarmupPreview` once (disable with
  `-Dcomposeai.daemon.warmRenderOnBoot=false`), paying first-composition
  + `HardwareRenderer` + font-stack + PNG-encode init off the request
  path. The slot is published for dispatch only **after** its warm render
  completes, so a live render can never queue behind the cold warm-up.
  Slot 0 is left to serve's own `prewarm()` throwaway render.

`ServeBundleDaemon` opts serve-spawned Android daemons in by default
(serve fronts the daemon with baked PNGs while it warms, so nothing
needs the strict all-ready `initialize`); the Gradle-plugin / VS Code
launch keeps the eager contract. The deploy image also carries the flag
in `JAVA_TOOL_OPTIONS` for the source-build catalog path.

## atrace sections (`androidx.tracing`)

`-Dcomposeai.daemon.atrace=true` (default **off**) installs
`AndroidxTraceSections` inside each sandbox: every render-phase span the
daemon's chrome-trace recorder already times (`compose:setContent`,
`render:captureRoboImage`, `dataArtifact:*`, …) plus a whole-render
`composeai:render:<previewId>` parent span is mirrored onto
`android.os.Trace` via `androidx.tracing`, lining the daemon's phases up
with the framework's and Compose's own sections in an atrace-level
capture. Opt-in because Robolectric's `ShadowTrace` accumulates section
history in static state with no test-lifecycle resets in a long-lived
daemon.

## What this does NOT solve

- **Cross-module sharing.** Different Compose / Kotlin / AGP versions
  still need separate JVMs.
- **OOM blast radius.** A leak in one pooled sandbox takes down its
  peers. v1 keeps the whole-JVM recycle.
- **Desktop replicas.** AWT EDT and Skiko's GPU context are
  process-global; out of scope.

## Cross-references

- DESIGN.md § 9 — sandbox bootstrap and recycle policy.
- CLASSLOADER.md — parent/child classloader split per slot.
- ROBOLECTRIC-PRIMER.md § "Native library loading" — load-once-per-JVM
  constraint.
