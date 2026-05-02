# Disposable user classloader

> **Status:** implemented. The parent/child classloader split landed in
> `:daemon:core`'s [`UserClassLoaderHolder`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/UserClassLoaderHolder.kt)
> and both backends. Per-render fresh-`Recomposer` invariant verified;
> the soak `WeakReference` probe is live. This doc is the architecture
> reference + risk register; the implementation now matches it.
> Cross-referenced from
> [DESIGN.md § 8](DESIGN.md#8-staleness-cascade--when-do-we-actually-re-render).
>
> Outstanding follow-ups: B2.0c (per-preview resource-read tracking) is
> still open — see § Resource changes below. Android `S3_5` is gated on
> a Compose-Android compiler-mangled-method-name fix.

## Why this exists

The daemon's "warm render" numbers from
[baseline-latency.csv](baseline-latency.csv) and the harness's S7
scenarios are real, but they answer a narrower question than the design
goals require. They measure **the same preview rendered repeatedly
against an unchanged classpath**. The save-loop a developer actually
hits — *edit `Foo.kt`, kotlinc recompiles, daemon renders the same
preview again* — is not exercised today and would silently produce
stale output:

1. Daemon spawns. `RobolectricHost`'s `InstrumentingClassLoader` (or
   `DesktopHost`'s app classloader) loads `com.example.app.RedSquare`
   on first render. The render produces red.
2. User edits `Foo.kt`, kotlinc recompiles. New `.class` file lands in
   `build/intermediates/built_in_kotlinc/<variant>/...`.
3. Daemon receives `fileChanged({ kind: "source" })` and re-renders
   `RedSquare`.
4. `RenderEngine.render` calls `Class.forName(spec.className, …,
   classloader)`. The classloader caches by name and **returns the
   already-loaded `Class<?>`** — bytecode from step 1, not the
   recompiled bytes from step 2.
5. The render produces red again. The user's edit is silently ignored.

The harness's existing `S3RenderAfterEdit*Test` scenarios don't catch
this — they swap *which* preview the spec payload references between
two pre-loaded composables (`RedSquare` → `BlueSquare`); both classes
were loaded once at daemon spawn and reflection just dispatches to a
different one. Genuine recompile-then-rerender is not tested.
[`S3_5RecompileSaveLoopRealModeTest`](../../daemon/harness/src/test/kotlin/ee/schimke/composeai/daemon/harness/S3_5RecompileSaveLoopRealModeTest.kt)
is the `@Ignore`d placeholder for the test that flips green when this
design lands.

## Prior art: Compose Hot Reload's approach

JetBrains' [`compose-hot-reload`](https://github.com/JetBrains/compose-hot-reload)
solves a related but distinct problem (live-running Compose Desktop UI
that reloads in place on edit). Its mechanism, as readable from the
public source, is:

- A **JVM agent** loaded via `-javaagent:hot-reload-agent.jar` at
  startup, capturing the `java.lang.instrument.Instrumentation` instance.
- On each Gradle continuous-build cycle, a list of changed `.class`
  files is shipped to the agent.
- The agent calls `Instrumentation.redefineClasses(ClassDefinition[])`
  to swap bytecode in place. The `Class<?>` object identity is
  preserved; only the bytes change.
- [Javassist](https://www.javassist.org/) is used to tweak the new
  bytecode before redefinition (notably to stitch fresh `static`
  initialiser blocks onto the existing class without re-running
  the original — `transformForStaticsInitialization` in the agent's
  source).
- The Compose runtime is patched to **invalidate scopes** that
  reference reloaded classes — `androidx.compose.runtime.Composer`'s
  recomposition graph drops slots holding old function references and
  re-runs the composition with the new bodies.
- State preservation across reloads is explicitly a goal; user
  `remember { … }` slots survive, ViewModels survive, the user can
  hook `staticHotReloadScope.invokeAfterHotReload { … }` for custom
  state resets.

**Required: JetBrains Runtime (JBR) or DCEVM.** Stock HotSpot's
`redefineClasses` allows only method-body changes (no field or method
add/remove). JBR ships an enhanced redefinition (DCEVM-derived) that
allows arbitrary class shape changes. Without JBR the approach falls
over the moment a developer adds a parameter to a composable.

## Why we don't adopt the same approach

Three reasons our case is different enough that a classloader split is
cleaner than `redefineClasses`:

1. **State preservation is anti-goal for us.** Compose Hot Reload's
   value proposition is "edit running UI, keep my scroll position".
   The daemon's value proposition is "render fresh PNG quickly".
   Every render already starts from a fresh `setContent { … }`; there
   is no `remember { … }` slot to preserve. Throwing away the
   user-class classloader between renders matches what we want.
2. **Robolectric instrumentation collides with `redefineClasses`.**
   Robolectric's `InstrumentingClassLoader` rewrites bytecode at load
   time (shadow installation, Android-stub class generation). The
   bytes the JVM holds for an instrumented class don't match the
   `.class` file on disk. `redefineClasses` would either redefine to
   the un-instrumented disk bytes (breaking Robolectric's shadows) or
   require us to re-run Robolectric's instrumentation in the agent
   before redefining (a fork of Robolectric internals, fragile across
   Robolectric upgrades). Neither is appealing.
3. **JBR mandate.** Compose Hot Reload requires JBR. We don't control
   the user's JVM; the launch descriptor inherits AGP's
   `JavaLauncher`. Mandating JBR would hard-fork the daemon from the
   project's existing toolchain story and exclude users on
   Temurin/Corretto/etc.

The classloader split below preserves the project's existing JVM
flexibility and sidesteps Robolectric's redefinition complications by
not redefining at all — it discards and rebuilds.

## Proposed design — parent/child classloader split

### The split

| Classloader | Lifetime | Loads |
|---|---|---|
| **Parent** (long-lived, expensive to bootstrap) | Per daemon JVM | `android-all` (Android) or system classes (desktop), AndroidX, Compose runtime / foundation / ui / tooling, kotlinx-*, Roborazzi, the daemon module's own helpers, the bridge package |
| **Child** (disposable, per-recompile) | Per `fileChanged({ kind: "source" })` cycle | The user's compiled-class output (`build/intermediates/built_in_kotlinc/<variant>/classes/` or `build/classes/kotlin/<variant>/main`) |

The user's `build/intermediates/...` directory is **excluded from the
parent's classpath**. Java's parent-first delegation means the child
must own the user-class lookup; if the parent could resolve them, the
child would never get a chance.

### Lifecycle

- **Daemon spawn:** parent classloader is constructed (Robolectric
  `InstrumentingClassLoader` for Android, `URLClassLoader` for
  desktop). The user's compiled-class directory is **not** on its URLs.
- **First render:** `RenderEngine` lazily allocates a child
  `URLClassLoader` whose parent is the long-lived classloader and
  whose URLs are the user's `build/intermediates/...` directory.
  `Class.forName(spec.className, true, currentChildLoader)` resolves
  the preview class via the child. Parent classes (Compose runtime,
  AndroidX) flow up via parent-first delegation as normal.
- **`fileChanged({ kind: "source" })` arrives:** the daemon drops the
  strong reference to the current child loader, then allocates a new
  one. The next render goes through the new loader, which reads the
  current bytecode off disk on demand. Old child loader becomes GC-able
  once any retained Compose state is cleared (see § Risks below).
- **Daemon shutdown:** drop both loaders. Parent's
  `InstrumentingClassLoader` releases its sandbox (existing
  `RobolectricHost.shutdown` path); child is just a `URLClassLoader`,
  trivially closeable.

Cost per recompile cycle: tens of ms — `URLClassLoader` allocation is
free; first lookup pays the `.class` file read; subsequent lookups in
the same render are cached. The 4–5s Robolectric sandbox bootstrap is
paid **once at daemon spawn** and never again per save-loop iteration.

### Implementation seams

**Android (`:daemon:android`):**

- `SandboxHoldingRunner` already overrides
  `RobolectricTestRunner.createClassLoaderConfig()` for the bridge
  package. Extend it to **also exclude** the user's
  `build/intermediates/built_in_kotlinc/<variant>/classes/` from the
  parent classpath. The exact list of paths comes from the launch
  descriptor (which already enumerates them — see
  `AndroidPreviewClasspath`).
- `RobolectricHost` gains a `currentChildLoader: URLClassLoader?`
  field protected by the existing render-thread invariant.
  `RenderEngine.render` is updated to `Class.forName(spec.className,
  true, currentChildLoader)`.
- The fileChanged → recycle path replaces `currentChildLoader` with a
  new `URLClassLoader` whose URLs are the same user-class directories.
  The render thread's pending queue is drained before the swap (no
  mid-render cancellation — DESIGN § 9 invariant).

**Desktop (`:daemon:desktop`):**

- Simpler. No `InstrumentingClassLoader`; the parent is the daemon
  process's own app classloader. `DaemonMain` constructs the initial
  child `URLClassLoader` from the user-class directories in the launch
  descriptor.
- Same `RenderEngine.render` change; same fileChanged → recycle path.
- Skiko / Compose Desktop runtime is on the parent — no special
  handling needed for native libs.

**Shared infrastructure (`:daemon:core`):**

- A small `RenderHost` extension or sibling — `UserClassLoaderHolder`
  or similar — that owns the `currentChildLoader` lifecycle. Both
  hosts implement against it. Keeps the classloader-swap logic out
  of host-specific code.
- The `fileChanged` notification handler in `JsonRpcServer` routes the
  recompile signal into the holder. Today `fileChanged` is a no-op
  (per the `S3` gap-flag); B2.0 makes it the trigger for child-loader
  recycle.

## Risks

### 1. Cross-classloader Compose state retention — verified mostly OK

**Verified.** Both backends construct Compose state **per-render**, so
cross-render `Recomposer` retention is not a problem in the current
design:

- Desktop's [`RenderEngine`](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
  allocates `ImageComposeScene(width, height, density)` inside `render`
  and `try/finally`-closes it. `ImageComposeScene.close()` disposes the
  scene's internal `Recomposer` + `Composition`.
- Android's [`RenderEngine`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
  builds a `createAndroidComposeRule<ComponentActivity>()` inside the
  per-render `Statement`. The rule's outer wrapper closes the
  `ActivityScenario` on `evaluate()` return; `Activity.onDestroy()`
  triggers `ComposeView` composition disposal which drops the
  composition's `Recomposer`.

Each render's `Recomposer` is scoped to that render. After the render
returns, no live `Recomposer` retains a strong reference to user-class
objects from the disposed child classloader. The classloader becomes
GC-able as soon as the next major collection runs.

**Remaining belt-and-braces:**
- **Forced GC + `WeakReference` probe** after each recycle (DESIGN § 9
  has this for sandbox-leak detection; B2.0 reuses the pattern for
  child-classloader-leak detection). If a recycled loader doesn't
  collect within 2 GCs, log `userClassloaderLeaked`. After N events,
  recycle the whole sandbox (the existing escape valve).
- Verify empirically as part of B2.0's DoD — a soak loop with N
  recompile-cycles asserting that the active-child-classloader count
  is bounded and old loaders collect.

The Compose-Hot-Reload-style "invalidate `Composer`'s applier slots
whose owning class is loaded by the to-be-disposed child" approach is
**not needed** given the per-render scoping — flagged for re-evaluation
only if a future change introduces cross-render Compose state.

### 2. `@PreviewParameter` provider classes

Compose runtime sometimes reflects on user-defined classes — most
notably `PreviewParameterProvider` implementations. If the reflection
goes through the **parent** classloader, it won't find user classes
(because they're not on the parent's classpath).

**Fix:** install the child classloader as the
`Thread.currentThread().contextClassLoader` for the duration of the
render dispatch. Compose's reflection paths use the context
classloader by default; this redirects them. The host's render thread
restores the original context classloader in `try/finally` so the
sandbox-init path keeps working.

### 3. `static` field state in user classes

Re-reading the user's `.class` from a fresh classloader means
re-running the class's `<clinit>`. Any user `object` declarations or
`companion object`s with side-effecting initialisers will run again on
each recompile. For a render-only daemon this is *probably* the right
behaviour — the user just edited the source; they want the new
behaviour, including new static state. But it's a behaviour change vs.
the current "everything caches" model and worth flagging in user-facing
docs once the feature ships.

### 4. Bridge package and `doNotAcquirePackage`

`SandboxHoldingRunner` adds `ee.schimke.composeai.daemon.bridge` to
Robolectric's `doNotAcquirePackage` so the cross-classloader handoff
queues are loaded once. The same rule extends to the new child
classloader's parent-first delegation: bridge classes resolve via the
parent's parent (the system classloader), unaffected by the child.
**No change needed** — but verify with a unit test that the bridge
queues stay shared across child swaps.

### 5. Compose compiler plugin's per-class metadata

The Compose compiler emits per-`@Composable` synthetic metadata
classes (`*$$composable_*`) referenced by name from the runtime. After
a child swap, those metadata classes are re-loaded by the new child.
The runtime's name-based lookups should resolve them via the same
child (parent-first up to the `androidx.compose.runtime.*` types,
which are on the parent; the user-emitted metadata classes are on the
child, found via the context classloader installed in § 2). **Verify
empirically with a `@Preview` that uses `@PreviewParameter` —
it's the easiest way to surface a metadata-class lookup gone wrong.**

### 6. Native libraries loaded by the parent

Skiko's native bundle and Robolectric's `android-all` native libraries
are loaded once by the parent classloader. They're shared across all
renders. Child-loader swaps don't affect them. **No risk** — flagged
to confirm the design doesn't accidentally trigger
`UnsatisfiedLinkError` because of duplicate native library
registration (`System.loadLibrary` is once-per-classloader; if a child
classloader ever tries to load Skiko itself, that fails). The fix is
in § 1's parent-classpath construction — Skiko stays on the parent.

## Phasing

### B2.0 — disposable user classloader (this doc)

Land the parent/child split for both backends. `fileChanged →
swap-child-loader` wired up. Compose `Recomposer` retention-leak
mitigation chosen based on measurement.

- DoD: `S3_5RecompileSaveLoopRealModeTest` un-`@Ignore`d for both
  desktop and android; both pass (assert the recompiled bytecode
  flows through). Sandbox-classloader-leak detection extended to
  also cover child loaders.

### B2.0a — bench the save-loop end-to-end

Extend the harness's S7 latency-record-only scenario or add a new
`S7_5SaveLoopLatency` that measures: cold daemon spawn → first render
→ recompile (real `gradle compileKotlin` via `ProcessBuilder`) →
`fileChanged` → second render. Headline number lands in
`baseline-latency.csv` under a new `target,scenario,mode` triple. The
goal: prove the daemon's value proposition — *the second render
after a save is fast*.

### B2.0b — `@PreviewParameter` round-trip

A scenario that uses a `PreviewParameterProvider` defined in the user
module. Verifies § 2 + § 5 mitigations work end-to-end. Skipped today
because the harness fixtures don't exercise this; lands alongside B2.0.

## Decisions still open

None as of this writing. New questions surfaced during implementation
move here first; they migrate to the "Decisions made" section once
resolved.

## Decisions made

1. **Per-render fresh `Recomposer`?** ✅ **Verified.** Both backends
   construct Compose state per-render — desktop's `ImageComposeScene`
   inside `render` (closed in `try/finally`); Android's
   `createAndroidComposeRule<ComponentActivity>()` inside the per-render
   `Statement` (disposed when `ActivityScenario` closes). No
   cross-render `Recomposer` retention. The `Composer`-slot
   invalidation work is not needed; B2.0 ships without it. See § Risks
   1 above for the verification details. Belt-and-braces: a soak
   `WeakReference` probe is still part of B2.0's DoD.

2. **`UserClassLoaderHolder` in `:daemon:core` first.** Land
   it in core; refactor per-target only if Android's child-loader
   complexity (the Robolectric `doNotAcquirePackage` + bridge
   discipline) leaks into core's API surface in a way that
   compromises desktop's simpler path. The principle stays "core hosts
   only renderer-agnostic things"; a `URLClassLoader` lifecycle holder
   is genuinely renderer-agnostic. If implementation reveals that
   cleanly factoring requires polluting core, retreat to
   per-target — captured as a B2.0 implementation guideline rather
   than a blocking decision.

3. **`fileChanged` semantics for non-source changes — distinct events
   per kind.** B2.0 wires `fileChanged({ kind: "source" })` to
   child-loader recycle. `kind: "classpath"` triggers a full sandbox
   recycle (Tier 1, B2.1 territory). `kind: "resource"` is its own
   event with its own handling — see § Resource changes below for the
   conservative v1 plan and the smarter v2 follow-up the user
   surfaced. Future contributors who add new `fileChanged` kinds
   should add a section here documenting the semantics.

4. **Mandate JBR/DCEVM?** ✅ **No.** B2.0 stays on stock HotSpot
   (Temurin / Corretto / AGP's default toolchain). Compose Hot
   Reload's `Instrumentation.redefineClasses` approach requires JBR's
   enhanced redefinition for non-trivial reloads; B2.0's classloader
   split needs no JVM-specific support and works on any HotSpot-derived
   runtime. Keeps the daemon a drop-in for any Android project's
   existing toolchain.

## Resource changes — conservative v1 + smart v2

### v1 (lands with B2.0 if scope permits, otherwise a B2.0c follow-up)

A `fileChanged({ kind: "resource", path: "res/values/colors.xml" })`
notification triggers **all previews in the module marked stale** (the
Tier-3 conservative fallback from DESIGN § 8). Combined with the Tier
4 visibility filter, the user only ever pays for re-rendering what
they're looking at, so the waste is bounded. No per-resource
invalidation precision; resources are re-baked into the merged
resource APK by AGP on the next build, which our daemon picks up on
the next render.

### v2 follow-up — per-preview resource-read tracking

User surfaced this idea: instead of the broad-stroke "any resource
changed → all previews stale", instrument the Resources lookup path
during a render to record which resource IDs the composition actually
read. Then a `fileChanged({ kind: "resource" })` event resolves the
changed file's resource IDs and looks them up in a reverse index
"which previews read these IDs" — only those previews are marked
stale.

Conceptually similar to the Tier-3 dependency-graph reachability index
in DESIGN.md, but for resources rather than classes.

Sketch:

- **Android**: Robolectric already routes `android.content.res.Resources`
  lookups through shadows. Add a `ShadowResources`-style instrumentation
  layer (or hook via the existing renderer helpers) that records
  per-render which resource IDs were `getColor` / `getDimensionPixelSize`
  / etc. Persist alongside `previews.json`.
- **Desktop**: Compose Multiplatform's `compose.resources.*` API is the
  equivalent. Same pattern — intercept the lookup path during render,
  record per-preview reads.
- **Reverse index**: rebuilt on first read after each `discoveryUpdated`
  notification. Cheap; a few hundred entries even on large modules.
- **Resource-file → resource-id resolution**: parse the changed XML,
  pull out the `@+id` / `<color name="...">` / `<string name="...">`
  declarations, map to the integer IDs via the merged `R.txt`. Already
  available at AGP build time; needs to be exposed to the daemon via
  the launch descriptor.

Tracked in [ROADMAP.md](ROADMAP.md) as per-preview resource-read tracking. Not blocking.

## Future work

- **Compose Hot Reload-style live-UI mode for Android.** Compose Hot
  Reload itself is desktop-only. The same parent/child machinery from
  this design could be the basis for a live-UI Android dev mode that
  doesn't require JBR — render once, keep the activity alive, swap
  the user classloader on save, and inject a recomposition trigger
  rather than tearing down the activity. Genuinely out of scope for
  the daemon's render-to-PNG workflow; flagged here so the design
  doesn't accidentally box out that future. Tracked as a long-term
  speculative item, not a near-term task.

- **Promotion of `unwrapInvocationTarget` to core**, if a third
  backend ever materialises (web? K/N?) — covered in commit `e7a0ee8`'s
  message.
