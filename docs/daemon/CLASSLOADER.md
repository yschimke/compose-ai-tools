# Disposable user classloader

The daemon splits its classloader hierarchy so the user's compiled
classes can be discarded and reloaded between renders without paying
the Robolectric sandbox bootstrap cost on every recompile.
Cross-referenced from
[DESIGN.md § 8](DESIGN.md#8-staleness-cascade--when-do-we-re-render).

## Parent/child classloader split

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
  AndroidX) flow up via parent-first delegation.
- **`fileChanged({ kind: "source" })` arrives:** the daemon drops the
  strong reference to the current child loader, then allocates a new
  one. The next render goes through the new loader, which reads the
  current bytecode off disk on demand. Old child loader becomes
  GC-able once any retained Compose state is cleared.
- **Daemon shutdown:** drop both loaders. Parent's
  `InstrumentingClassLoader` releases its sandbox; child is just a
  `URLClassLoader`, trivially closeable.

Cost per recompile cycle: tens of ms — `URLClassLoader` allocation is
free; first lookup pays the `.class` file read; subsequent lookups in
the same render are cached. The 4–5s Robolectric sandbox bootstrap is
paid **once at daemon spawn**.

## Implementation seams

**Android (`:daemon:android`):**

- `SandboxHoldingRunner` already overrides
  `RobolectricTestRunner.createClassLoaderConfig()` for the bridge
  package. Extended to **also exclude** the user's
  `build/intermediates/built_in_kotlinc/<variant>/classes/` from the
  parent classpath. Path list comes from the launch descriptor (see
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

- No `InstrumentingClassLoader`; the parent is the daemon process's
  own app classloader. `DaemonMain` constructs the initial child
  `URLClassLoader` from the user-class directories in the launch
  descriptor.
- Same `RenderEngine.render` change; same fileChanged → recycle path.
- Skiko / Compose Desktop runtime is on the parent — no special
  handling needed for native libs.

**Shared infrastructure (`:daemon:core`):**

- `UserClassLoaderHolder` owns the `currentChildLoader` lifecycle.
  Both hosts implement against it.
- The `fileChanged` notification handler in `JsonRpcServer` routes the
  recompile signal into the holder.

## Fresh-Recomposer-per-render invariant

Both backends construct Compose state **per-render**, so cross-render
`Recomposer` retention is not a problem:

- Desktop's
  [`RenderEngine`](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
  allocates `ImageComposeScene(width, height, density)` inside `render`
  and `try/finally`-closes it. `ImageComposeScene.close()` disposes
  the scene's internal `Recomposer` + `Composition`.
- Android's
  [`RenderEngine`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
  builds a `createAndroidComposeRule<ComponentActivity>()` inside the
  per-render `Statement`. The rule's outer wrapper closes the
  `ActivityScenario` on `evaluate()` return; `Activity.onDestroy()`
  triggers `ComposeView` composition disposal which drops the
  composition's `Recomposer`.

Each render's `Recomposer` is scoped to that render. After the render
returns, no live `Recomposer` retains a strong reference to user-class
objects from the disposed child classloader. The classloader becomes
GC-able as soon as the next major collection runs.

## Risks and mitigations

### `@PreviewParameter` provider classes

Compose runtime sometimes reflects on user-defined classes — most
notably `PreviewParameterProvider` implementations. **Fix:** install
the child classloader as the
`Thread.currentThread().contextClassLoader` for the duration of the
render dispatch. Compose's reflection paths use the context
classloader by default; this redirects them. The host's render thread
restores the original context classloader in `try/finally`.

### `static` field state in user classes

Re-reading the user's `.class` from a fresh classloader means
re-running the class's `<clinit>`. Any user `object` declarations or
`companion object`s with side-effecting initialisers will run again on
each recompile. For a render-only daemon this is the right behaviour —
the user just edited the source; they want the new behaviour, including
new static state.

### Bridge package and `doNotAcquirePackage`

`SandboxHoldingRunner` adds `ee.schimke.composeai.daemon.bridge` to
Robolectric's `doNotAcquirePackage` so the cross-classloader handoff
queues are loaded once. Bridge classes resolve via the parent's parent
(the system classloader), unaffected by the child. Verified with a
unit test that the bridge queues stay shared across child swaps.

### Compose compiler plugin's per-class metadata

The Compose compiler emits per-`@Composable` synthetic metadata
classes (`*$$composable_*`) referenced by name from the runtime. After
a child swap, those metadata classes are re-loaded by the new child.
The runtime's name-based lookups resolve them via the child (parent-first
up to the `androidx.compose.runtime.*` types on the parent; the
user-emitted metadata classes on the child).

### Native libraries loaded by the parent

Skiko's native bundle and Robolectric's `android-all` native libraries
are loaded once by the parent classloader. Child-loader swaps don't
affect them. Skiko stays on the parent.

## WeakReference soak probe

Belt-and-braces leak detection: forced GC + `WeakReference` probe
after each recycle. If a recycled loader doesn't collect within 2
GCs, log `userClassloaderLeaked`. After N events, recycle the whole
sandbox.

## State of B2.0 (implemented)

The parent/child classloader split landed in `:daemon:core`'s
[`UserClassLoaderHolder`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/UserClassLoaderHolder.kt)
and both backends. Per-render fresh-`Recomposer` invariant verified;
the soak `WeakReference` probe is live.

DoD met:
[`S3_5RecompileSaveLoopRealModeTest`](../../daemon/harness/src/test/kotlin/ee/schimke/composeai/daemon/harness/S3_5RecompileSaveLoopRealModeTest.kt)
un-`@Ignore`d for desktop and passes. Sandbox-classloader-leak
detection extended to also cover child loaders.

Outstanding:
- B2.0c (per-preview resource-read tracking) is still open — see
  Resource changes below.
- Android `S3_5` is gated on a Compose-Android compiler-mangled-method-name
  fix.

## `fileChanged` semantics

- `kind: "source"` → child-loader recycle (this doc).
- `kind: "classpath"` → full sandbox recycle (Tier 1, see DESIGN § 8).
- `kind: "resource"` → see Resource changes below.

## Resource changes

**v1 (current behaviour).** A `fileChanged({ kind: "resource", path:
... })` notification triggers **all previews in the module marked
stale** (the Tier-3 conservative fallback from DESIGN § 8). Combined
with the Tier 4 visibility filter, the user only ever pays for
re-rendering what they're looking at.

**v2 follow-up — per-preview resource-read tracking.** Instrument the
Resources lookup path during a render to record which resource IDs
the composition actually read; resolve the changed file's resource IDs
via the merged `R.txt` and look them up in a reverse index. Tracked in
[ROADMAP.md](ROADMAP.md). Not blocking.
