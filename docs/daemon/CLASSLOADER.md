# Disposable user classloader (B2.0)

> **Status:** design proposal. Captures the architecture for fixing the
> daemon's actual save-loop blocker. Implementation lands as
> [TODO.md B2.0](TODO.md#b20--disposable-user-classloader-shared-seam).
> Cross-referenced from
> [DESIGN.md § 8](DESIGN.md#8-staleness-cascade--when-do-we-actually-re-render).

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
[`S3_5RecompileSaveLoopRealModeTest`](../../tools/daemon-harness/src/test/kotlin/ee/schimke/composeai/daemon/harness/S3_5RecompileSaveLoopRealModeTest.kt)
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

**Android (`:renderer-android-daemon`):**

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

**Desktop (`:renderer-desktop-daemon`):**

- Simpler. No `InstrumentingClassLoader`; the parent is the daemon
  process's own app classloader. `DaemonMain` constructs the initial
  child `URLClassLoader` from the user-class directories in the launch
  descriptor.
- Same `RenderEngine.render` change; same fileChanged → recycle path.
- Skiko / Compose Desktop runtime is on the parent — no special
  handling needed for native libs.

**Shared infrastructure (`:renderer-daemon-core`):**

- A small `RenderHost` extension or sibling — `UserClassLoaderHolder`
  or similar — that owns the `currentChildLoader` lifecycle. Both
  hosts implement against it. Keeps the classloader-swap logic out
  of host-specific code.
- The `fileChanged` notification handler in `JsonRpcServer` routes the
  recompile signal into the holder. Today `fileChanged` is a no-op
  (per the `S3` gap-flag); B2.0 makes it the trigger for child-loader
  recycle.

## Risks

### 1. Cross-classloader Compose state retention

Compose's `Recomposer` keeps strong references to composition slots
that hold function references. After a child-loader swap, those slots
reference the **old** classloader's class objects. Until the
`Recomposer` is told to drop those slots, the old classloader can't
GC, and we leak one classloader's worth of bytecode + metadata per
recompile.

**Mitigations to evaluate:**
- Per-render **fresh `Recomposer`** — both backends already construct
  per-render Compose state (`createAndroidComposeRule` for Android,
  `ImageComposeScene` for desktop). If neither retains a `Recomposer`
  beyond a single render, the issue is moot. **Verify by reading the
  current `RenderEngine` impls before locking the design.**
- If a `Recomposer` *is* retained: lift Compose Hot Reload's
  `transformForStaticsInitialization` pattern (their `compose.kt` in
  `hot-reload-agent`) — invalidate the `Composer`'s applier slots
  whose owning class is loaded by the to-be-disposed child. Heavier
  work; only justified if measurement shows leaks.
- **Forced GC + WeakReference probe** after each recycle (DESIGN § 9
  has this for sandbox-leak detection; B2.0 reuses the pattern for
  child-classloader-leak detection). If a recycled loader doesn't
  collect within 2 GCs, log `userClassloaderLeaked`. After N events,
  recycle the whole sandbox (the existing escape valve).

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

## Decisions required

Surface these to the user before any implementation lands:

1. **Per-render fresh `Recomposer`?** Need to verify whether the
   current `RenderEngine` impls already construct one per render
   (suspected yes, both backends). If yes, § Risks 1 collapses to
   "verify and document". If no, the recompose-graph invalidation path
   is non-trivial and B2.0 grows considerably.

2. **`UserClassLoaderHolder` in `:renderer-daemon-core`** — or in each
   per-target module separately. DESIGN § 4's renderer-agnostic
   surface invariant says core hosts the protocol + `RenderHost` and
   nothing renderer-specific. A `UserClassLoaderHolder` is technically
   renderer-agnostic (it's just `URLClassLoader` lifecycle), so it
   belongs in core. But desktop's child-loader story is simpler than
   Android's; promoting forces the simpler path to carry Android's
   complexity in its public surface.

3. **`fileChanged` semantics for non-source changes.** The current
   `S3` gap flag is "fileChanged is a no-op". B2.0 wires
   `fileChanged({ kind: "source" })` to recycle. What about `kind:
   "resource"` (resources changed) or `kind: "classpath"` (deps
   changed)? Resources probably also need a child-loader recycle (or
   re-bake the resource APK — out of scope here). Classpath dirties
   the parent, requiring a full sandbox recycle (Tier 1, B2.1).
   Confirm or adjust.

4. **Hot-reload-equivalent for Android UI development?** This doc is
   scoped to the daemon's *render-to-PNG* workflow. A future
   consideration: could the same parent/child machinery be the basis
   for a Compose Hot Reload-style live-UI mode for Android, given that
   Compose Hot Reload itself is desktop-only? Probably yes, but
   genuinely out of scope for the daemon and explicitly noted as
   future work, not part of this design.

5. **Mandate JBR/DCEVM?** Compose Hot Reload requires it; B2.0 does
   not. Confirm we want to stay on stock HotSpot (Temurin / Corretto /
   AGP's default toolchain) so the daemon remains a drop-in for
   any Android project.
