# Preview daemon — sandbox-pool follow-ups

> **Status:** design only. Captures the three follow-ups [SANDBOX-POOL.md](SANDBOX-POOL.md)
> deferred from the v1 in-JVM-pool work, in roughly the order they're worth tackling.

The in-JVM sandbox pool ([SANDBOX-POOL.md](SANDBOX-POOL.md)) collapses N+1 daemon JVMs into one
JVM with N+1 Robolectric sandboxes, saving ~2.7 GB on the default `replicasPerDaemon = 3`. Three
constraints from that work remain:

| # | Follow-up | Why deferred | Cost of doing nothing |
|--:|-----------|--------------|-----------------------|
| 1 | Per-slot user-class child loaders | Hot-reload uses a single shared `URLClassLoader`; per-slot needs design + invalidation discipline | Daemons that opt into hot-reload silently fall back to `sandboxCount = 1` and lose pool concurrency |
| 2 | Per-slot sandbox recycle | Recycle thresholds (heap drift, render-time drift) attribute to "the sandbox" not "this sandbox among N"; pool muddies the math | Whole pool tears down on any heap event — bigger user-visible pause than necessary |
| 3 | Affinity-aware dispatch (previewId-keyed) **— landed** | Today's dispatch is `Math.floorMod(requestId, N)`; same preview hits a different sandbox each render | Compose state cache + Robolectric shadow caches don't carry across renders of the same preview |

This doc sketches each. Implementation lands in separate PRs once the sketches survive review.

---

## 1. Per-slot user-class child loaders

### Problem

[CLASSLOADER.md](CLASSLOADER.md) describes the parent/child split: the sandbox classloader holds
framework classes (Compose, Robolectric, instrumented `android.*`); a disposable child
`URLClassLoader` holds the user's compiled `@Preview` code, parented to the sandbox loader.
`fileChanged({ kind: "source" })` swaps the child loader so the next render sees the recompiled
bytecode. This is the hot-reload path — the reason a save-then-render round-trip stays under a
second.

`UserClassLoaderHolder` is a single object owned by the daemon. Its `currentChildLoader()`
returns the one live `URLClassLoader`; `swap()` drops it. The host mirrors that loader into
`DaemonHostBridge.childLoaderRef` so the sandbox-side `RenderEngine` reads it on every render.

The pool's constraint is one line in `RobolectricHost`:

```kotlin
require(sandboxCount == 1 || userClassloaderHolder == null) {
  "sandboxCount > 1 with a non-null userClassloaderHolder is not supported in v1: per-slot " +
    "child URLClassLoaders need separate work (SANDBOX-POOL.md). …"
}
```

The reason it can't just work today: the child loader's **parent** is *one specific* sandbox
classloader. Sandbox A's framework classes are not `.equals()` to sandbox B's (different
`InstrumentingClassLoader` instances → different `Class<?>` objects). A child parented to A
would mis-resolve `androidx.compose.runtime.Composer` when its render dispatch lands in B —
classloader-identity skew, the same shape that broke the original B2.0 Android save loop and
needed [classloader-forensics-diff.md](classloader-forensics-diff.md) to debug.

### Sketch

Move `UserClassLoaderHolder` from "one per host" to "one per slot." Each slot's holder allocates
a child `URLClassLoader` parented to **that slot's** sandbox classloader, populated from the same
URLs (the user's `build/intermediates/...` dirs).

```kotlin
class RobolectricHost(
  override val userClassloaderHolderFactory: ((sandboxClassLoader: ClassLoader) -> UserClassLoaderHolder)? = null,
  val sandboxCount: Int = 1,
) : RenderHost {
  // One holder per slot; index matches DaemonHostBridge slot index.
  private val perSlotHolders: Array<UserClassLoaderHolder?> = arrayOfNulls(sandboxCount)
  // …
}
```

`SandboxRunner.holdSandboxOpen` (per-slot prologue inside the sandbox) calls back into the host
with `(slotIndex, sandboxClassLoader)` so the host allocates the slot's holder lazily — at the
point we know the parent. The slot's holder's child loader is mirrored into
`DaemonHostBridge.slot(i).childLoaderRef` (which already exists from Layer 1).

`RenderEngine.render` already reads `Thread.currentThread().contextClassLoader` (or the bridge's
child-loader ref), so the sandbox-side render path doesn't change — just sees the right child
loader for its slot.

### `fileChanged` semantics

The current contract: a single `swap()` invalidates the one child loader. In the pool this
becomes "broadcast `swap()` to every slot's holder." The next render on each slot allocates a
fresh child loader on first use. Round-trip cost: still one `URLClassLoader` allocation per
slot, paid at the next render to that slot — not on the file-changed call itself (which stays
sub-millisecond).

Race to watch: `fileChanged` arrives mid-render-on-slot-N. The current single-slot semantics
already handle this via the "no mid-render cancellation" invariant (the in-flight render finishes
on the old loader; the next one allocates fresh). Per-slot keeps that property — slots don't
share a holder, so a mid-render swap on one slot can't trip another.

### Memory

Adds N copies of: the URL list (cheap, ~few KB), the strong reference to one `URLClassLoader`
(cheap), and *transitively* whatever user-loaded classes are alive at peak (~few MB for typical
preview modules; varies). Far below the per-sandbox classloader cost we already pay.

### Open questions

- **Parent-supplier evaluation timing.** Today's `parentSupplier = { DaemonHostBridge.currentSandboxClassLoader() ?: error("…") }` is evaluated lazily on first `currentChildLoader()` read. Per-slot, the parent is *the slot's* classloader — captured at slot-claim time. Cleaner if the host invokes a factory `(sandboxClassLoader) -> UserClassLoaderHolder` once per slot at boot, removing the global-bridge dependency.
- **Discovering URLs.** `composeai.daemon.userClassDirs` is a single sysprop. Same URLs feed every slot — no protocol change needed.
- **Recompile race.** If the user saves twice in quick succession, sandbox A's render might still be on the older child loader when sandbox B's slot allocates the newer one. Acceptable: each render captures its loader at dispatch time; in-flight stays consistent. The "no mid-render cancellation" invariant covers it.

### Test plan

- Unit: `RobolectricHost(sandboxCount = 2, userClassloaderHolderFactory = …)` — both slots boot, both render through their own child loader, swap on one slot doesn't affect the other.
- E2E: extend `S3_5RecompileSaveLoopRealModeTest` to run with `sandboxCount = 2` and verify that a `fileChanged`-triggered swap is reflected in renders dispatched to either slot.

### Lift the constraint

After this lands: drop the `require(sandboxCount == 1 || userClassloaderHolder == null)` check in
`RobolectricHost.init` and the matching fallback in `DaemonMain.kt` that forces `sandboxCount = 1`
when a holder is wired. CONFIG.md's caveat about hot-reload incompatibility goes away.

---

## 2. Per-slot sandbox recycle

### Problem

[DESIGN.md § 9](DESIGN.md#recycle-policy) lists six recycle triggers:

- Heap (post-GC) > `daemon.maxHeapMb`
- Heap drift > 30% over 50-render window
- Render-time drift > 50% over rolling window
- Class histogram delta over rolling window
- Render count > `daemon.maxRendersPerSandbox`
- `leakSuspected` from active detection

In the pre-pool model, "the sandbox" is unambiguous and any trigger means "tear down THE sandbox
and build a new one." With N sandboxes in one JVM, three of those triggers (heap-based, class-
histogram) read JVM-global state that doesn't attribute to a specific sandbox; the other three
(render-time, render count, `leakSuspected`) are sandbox-local already.

DESIGN.md § 9 already calls this out:

> With `sandboxCount > 1` the heap-based triggers fire on JVM-global heap, not per-sandbox —
> there's no straightforward way to attribute a specific sandbox classloader's contribution. v1
> recycles the whole pool when any heap signal fires; render-count and per-sandbox render-time
> stay sandbox-local.

The "tear down the whole pool" v1 behaviour is correct (sound) but coarse — a leak in one slot
forces a multi-second pause on every slot.

### Sketch

Two-axis recycle: per-slot for sandbox-local triggers, JVM-global for heap-based.

```
                    trigger                          recycle scope
   render count > maxRendersPerSandbox        →     just the slot that hit it
   render-time drift > 50% on slot N         →     just slot N
   leakSuspected({slot: N})                  →     just slot N
   ────────────────────────────────────────────────────────────────────────
   JVM heap (post-GC) > maxHeapMb            →     pool-wide
   JVM heap drift > 30% over 50-render window →    pool-wide
   class histogram delta > Y                 →     pool-wide (best-effort)
```

Per-slot recycle implementation: the bridge's `SandboxSlot` already has the structural pieces
(per-slot ready latch, per-slot request queue). Add:

- `SandboxSlot.recycleRequested: AtomicBoolean` — set by the host, read by the slot's
  `holdSandboxOpen` between renders. When set, the slot's loop drains its queue, exits cleanly,
  and the host re-spawns the worker thread + JUnit run for that slot only. Other slots stay live.
- Stable slot index across recycle: the new sandbox claims the same slot the old one vacated
  (via a "preferred slot index" parameter on `registerSandbox`).

Pool-wide recycle: emit `daemonWarming` once, drain the whole pool, rebuild via `start()`.

### Warm spare interaction

Today's [warm spare](DESIGN.md#warm-spare) keeps `active` and `spare` sandboxes; recycle = atomic
swap. Per-slot recycle in the pool: keep one warm spare *across the pool*, not per slot. A
recycle on slot N consumes the spare (slot N gets the spare's loader, spare rebuilds on a
background thread). Memory cost: still 1× sandbox extra, not Nx — keeps the pool's memory
advantage.

### Wire format

`sandboxRecycle({ reason, ageMs, renderCount, slot? })` — the existing message shape grows an
optional `slot` field. v1's "whole pool" recycle emits without `slot` (= "pool-wide"), the new
per-slot variant emits with the slot index. VS Code's existing handler ignores unknown fields, so
old clients keep working.

### Test plan

- Unit: simulate `renderCount > maxRendersPerSandbox` on slot 1 only; assert slot 1 rebuilds and
  slots 0/2/3 stay on their original classloaders.
- E2E (`:daemon:harness`): drive renders into a leak-shaped composable on one slot until heap
  drift triggers; assert pool-wide recycle (single `daemonWarming`, all slots rebuild).

### Risks

- **Per-slot rebuild concurrent with other slots' renders.** Robolectric's `SandboxManager` is
  internally synchronised on construction; a slot rebuild blocks new sandbox allocation
  briefly but doesn't block existing sandboxes' main-thread executors. Should be safe; verify
  with the same diagnostic instrumentation that surfaced the cache-key bug in Layer 2.
- **Spare allocation policy.** Single shared spare is simpler but means two near-simultaneous
  recycles serialise on rebuild. Multi-spare adds memory; v1 stays single-spare.

---

## 3. Affinity-aware dispatch (previewId-keyed) — landed

### Problem

`RobolectricHost.submit` dispatches via `Math.floorMod(typed.id, sandboxCount.toLong()).toInt()`,
where `id` is the monotonic request id from `RenderHost.nextRequestId()`. Same preview rendered
twice in a row gets two different request ids → two different slots → two cold composition
caches.

Compose's snapshot system caches recompositions per composition tree; Robolectric's
`ShadowPackageManager` accumulates registrations per sandbox; user-side static state (e.g.
process-wide `RuntimeEnvironment.application` modifications) survive across renders on the same
sandbox. A consistent "preview X always renders on sandbox Y" mapping makes those caches actually
useful.

### Sketch

Hash the preview id, not the request id:

```kotlin
val typed = request as RenderRequest.Render
val affinityKey = parsePreviewIdFromPayload(typed.payload) ?: typed.id.toString()
val slotIdx = Math.floorMod(affinityKey.hashCode(), sandboxCount)
val slot = DaemonHostBridge.slot(slotIdx)
slot.requests.put(typed)
```

`parsePreviewIdFromPayload` extracts `previewId=<id>` from the existing payload format; falls
back to request id when the payload is the legacy stub form (`render-N`) or missing the prefix.
The fallback keeps `RobolectricHostTest`'s legacy-payload assertions working.

### Wire format

No change. `previewId` is already in the payload (`PreviewManifestRouter` and `JsonRpcServer`
both encode it). Dispatch becomes deterministic from the id alone.

### Skew

If a preview's id hash collides with another, both renders queue on the same slot — same as
today's id-based dispatch under load. With `sandboxCount = 4` and N previews, expected
distribution is uniform for typical FQN-style preview ids; pathological collisions are rare.

### Test plan

- Unit: extend `RobolectricHostPoolTest` with a "same previewId → same slot" assertion. Submit
  the same preview twice with different request ids; assert both renders observe the same
  sandbox classloader.
- Microbench (optional): submit 100 renders for 4 distinct previews against `sandboxCount = 4`,
  measure render-time for the second-and-later renders (should beat first-render times because
  shadow cache is warm).

### Open question — supervisor-side affinity

The supervisor's `clientForRender(previewId)` is currently a no-op (always returns the single
client). With affinity-aware dispatch in the daemon, the supervisor's `previewId` hint is
unused there too — but if we ever support multiple **clients** per supervised daemon (e.g. for
cross-process replicas under different module classpaths), `previewId`-based affinity would
matter again. Keep the parameter on the wire-side method signature for that future.

---

## Suggested implementation order

1. **Affinity-aware dispatch (#3).** Smallest change, no protocol churn, pure win. ~30 lines + a
   test. Land first to validate the surface.
2. **Per-slot user-class child loaders (#1).** Lifts the biggest user-visible constraint
   (sandboxCount=1 forced when hot-reload is wired). Touches `UserClassLoaderHolder`,
   `RobolectricHost`, `DaemonMain`. Medium-sized.
3. **Per-slot sandbox recycle (#2).** Largest scope — needs `SandboxSlot` recycle plumbing, a
   wire-format extension, and warm-spare adaptation. Land last so the recycle policy lives on
   top of the stable hot-reload + affinity contract from #1 and #3.

Each is independent; the order is preference, not dependency.
