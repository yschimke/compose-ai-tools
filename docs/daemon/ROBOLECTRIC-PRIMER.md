# How Robolectric runs

A primer on Robolectric's architecture for debugging classloader behaviour,
shadow dispatch, or sandbox lifecycle while working on the daemon. Daemon
specifics live in [DESIGN.md](DESIGN.md), [CLASSLOADER.md](CLASSLOADER.md),
and [CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md). Canonical
upstream pages: [robolectric.org/architecture/](https://robolectric.org/architecture/)
and [robolectric.org/extending/](https://robolectric.org/extending/).

## Why Robolectric exists

Stock `android.jar` on the build classpath ships as **stubs** — every
method body is `throw new RuntimeException("Stub!")`. Robolectric puts
**real Android framework code** on the classpath at *test time* without
touching the build-time `android.jar`. That single goal — swap stubs for
real implementations at test time — is the source of every architectural
choice below.

## The mental model

For each `@Test` method, Robolectric creates a **`Sandbox`** with a custom
**`SandboxClassLoader`** (`AndroidSandbox$SdkSandboxClassLoader` in 4.13+,
historically `InstrumentingClassLoader`). That classloader loads classes
from a version-specific **`android-all` jar** containing real Android
framework code (lazily downloaded via `MavenArtifactFetcher`), **rewrites
their bytecode at load time** to redirect every method call through an
`invokedynamic` indirection, and via that indirection allows **shadow
classes** registered with `@Implements(targetClass)` to intercept and
replace specific framework method bodies with JVM-friendly fakes. The
`@Test` runs *inside* the sandbox classloader.

## Classloader hierarchy

```
[bootstrap classloader]                                      ← java.* / sun.* / kotlin.*
        ↑
[system / app classloader]                                   ← Robolectric's own classes,
        ↑                                                      JUnit, your test classpath
[SandboxClassLoader]    (per-@Test, disposable)              ← android.jar replacement, your
                                                               app-under-test classes (mostly),
                                                               framework + shadow bytecode
```

Java's classloader delegation is **parent-first by default**.
`SandboxClassLoader` overrides this for specific package prefixes — most
`android.*`, `androidx.*`, and the user's under-test classes get loaded
by the sandbox itself, *not* delegated up — so the sandbox can apply its
bytecode rewrite. `java.*` / `kotlin.*` / JUnit always delegate up.

**Which loader resolves which class is configured by package-prefix
rules on `SandboxClassLoader`**, not by JVM defaults. If a class you
expected to load via the sandbox loads via the app loader instead, the
sandbox's prefix list isn't matching.

The package filters are configurable via
`InstrumentationConfiguration`:

| Filter | Meaning | Default |
|---|---|---|
| `instrumentedPackages` | Bytecode-rewrite these package prefixes | `android.*`, `androidx.*`, `com.android.*`, etc. |
| `doNotInstrumentPackages` | Load locally but skip rewrite | Generally empty |
| `doNotAcquirePackages` | Don't load locally — defer to parent | `java.*`, `kotlin.*`, JUnit |

The daemon abuses `doNotAcquirePackages`: the bridge package between the
sandbox classloader and the host classloader is in `doNotAcquirePackages`
so a `LinkedBlockingQueue` created on the host is identity-equal to one
observed inside the sandbox.

## What "instrumentation" does to a class

When `SandboxClassLoader` decides to instrument a class, it rewrites
every `INVOKEVIRTUAL` / `INVOKESPECIAL` / `INVOKESTATIC` instruction into
an `INVOKEDYNAMIC` that goes through Robolectric's
`InvokeDynamicSupport` bootstrap. That bootstrap calls
`ShadowWrangler.findShadowMethodHandle(target, method)` and gets back:

- A `MethodHandle` to the real Android framework method (no shadow).
- A `MethodHandle` to a `@Implementation`-annotated method on a shadow.
- A guard that throws `Stub!` if there's no shadow and the real one
  would crash on a plain JVM.

`ShadowWrangler` caches the result per (class, method) pair. After the
first call, dispatch is a single indirected `MethodHandle.invoke` —
roughly an interface method call.

The `final` keyword is stripped from instrumented classes so shadows can
subclass them. Native methods get re-bound to either a real JNI
implementation (most graphics, audio, and SQL paths ship one) or a no-op
stub.

## How shadows get registered

1. **Built-in shadows**: `org.robolectric:shadows-framework` ships
   hundreds of `@Implements`-annotated classes. The Robolectric
   Annotation Processor generates a `ShadowProvider`; Robolectric loads
   them via `ServiceLoader` at sandbox construction.
2. **`@Config(shadows = [...])`** on a `@Test` or test class: registers
   custom shadows for that test only. Project-specific shadows (e.g.
   `ShadowFontsContractCompat` in `:renderer-android`) plug in here.
3. **`@Implements` directly on a shadow class file**: detected via
   classpath scan; less common.

All three feed into the same `ShadowWrangler` registry. The shadow can
also access the *real* framework instance via `@RealObject`.

## Per-`@Test` lifecycle

1. JUnit invokes `RobolectricTestRunner.runChild(...)` for the next `@Test`.
2. The runner picks an SDK level (`@Config(sdk = …)` or
   `targetSdkVersion`) and looks up or constructs a `Sandbox` for that
   SDK.
3. The runner reflectively re-loads the test class through the sandbox's
   classloader. **The `Class<?>` the runner now holds is *not* the same
   one your IDE sees.**
4. `AndroidTestEnvironment.setUpApplicationState(...)` boots `Looper`,
   sets up `ApplicationContext`, parses `AndroidManifest.xml`, creates
   `Resources`, applies qualifiers / SDK level / font scale.
5. The `@Test` method runs inside the sandbox.
6. `finallyAfterTest()` resets shadows via `@Resetter`-annotated methods,
   drains the looper, etc.

**Sandbox reuse.** Robolectric reuses sandboxes when SDK level /
qualifiers / `@Config` shape matches — building a fresh
`SandboxClassLoader` is multi-second cold (load + instrument the
`android-all` jar). A test class with 10 `@Test` methods all under the
same `@Config(sdk = [35])` runs all 10 in the same sandbox.

This is what the daemon exploits: a *single* `@Test` method blocking on
a queue is exactly the same cost as the first `@Test` of a normal
Robolectric run — but instead of returning, it hangs around indefinitely
processing render requests, never paying sandbox-creation cost again.

## SDK level, qualifiers, and `@Config`

`@Config(sdk = [N])` selects which `android-all-N-…jar` Robolectric
fetches and instruments. Switching SDK between tests forces a different
sandbox.

`@Config(qualifiers = "+w360-h640-port-mdpi-en")` configures the runtime
`Configuration` — read on each test, applied via
`RuntimeEnvironment.setQualifiers(...)`. Same sandbox.

`@Config(application = SomeApp::class)` swaps the `Application` class.
Same sandbox.

SDK is sandbox-bound; everything else is per-test.

## `RuntimeEnvironment` — the inside-the-sandbox singleton

The canonical handle once inside the sandbox:

- `getApiLevel(): Int` — current SDK
- `getApplication(): Application` — the real Application instance
- `getQualifiers(): String` — current qualifier string
- `setQualifiers(qualifierString)` — re-apply mid-test
- `setFontScale(scale)` — re-apply font scale
- `getMasterScheduler() / getMainLooper()` — looper / scheduler control

These are per-sandbox global state. Mutating them from one test affects
the next test in the same sandbox unless `@Resetter` runs between them.

## Native code and graphics

Some Android APIs ultimately call native code (Skia rendering, SQLite,
audio). Robolectric ships JNI implementations for many — `libandroidmedia`,
`libsqlite`, `libui` — extracted to a temp dir at sandbox construction.
Native libs are version-bound to the `android-all` artefact.

For the daemon, the `NATIVE` graphics mode (`@GraphicsMode(NATIVE)` or
`robolectric.graphicsMode=NATIVE`) is what makes `captureRoboImage`
actually produce pixels — it routes `HardwareRenderer` calls through
Robolectric's shipped Skia-via-JNI rather than no-op'ing them. Without
`NATIVE`, the test "succeeds" with empty pixels.

## When something goes wrong

- **`NoSuchMethodException` on `getDeclaredComposableMethod`**: parameter
  type's `Class<?>` is from a different classloader than the one Compose
  runtime uses inside the sandbox. A class expected to be in the sandbox
  loaded via the parent loader instead. See
  [CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md).
- **`UnsatisfiedLinkError`**: native lib not extracted or wrong SDK's
  native libs in scope. Usually a `@Config(sdk = …)` / native artefact
  mismatch.
- **`Stub!` `RuntimeException`**: the bytecode rewrite *didn't* apply to
  the calling class — call is going to the build-classpath `android.jar`
  stubs, not the instrumented `android-all`. Same classloader-routing
  bug as the `NoSuchMethodException` shape.
- **`Application` not initialised**: `setUpApplicationState` hasn't run.
  Usually trying to use Robolectric APIs outside a
  `@RunWith(RobolectricTestRunner)` test.
- **Shadow not applying**: `@Config(shadows = …)` missing, or shadow
  loaded by a different classloader than the framework class it shadows.
- **Sandbox reuse breaking state assumptions**: a test mutates
  `RuntimeEnvironment` state that the next test expected fresh.
  `@Resetter` is per-shadow; ad-hoc state outside a shadow needs manual
  reset.

## What this project does on top of Robolectric

Covered in [DESIGN.md](DESIGN.md) and [CLASSLOADER.md](CLASSLOADER.md).
The short version: hold one sandbox open across many "renders" via a
long-running `@Test` method that blocks on a queue, and load the user's
compiled classes through a disposable child classloader so an
edit-and-recompile cycle swaps user bytecode without losing the
expensive sandbox boot.

## Further reading

- [robolectric.org/architecture/](https://robolectric.org/architecture/)
- [robolectric.org/extending/](https://robolectric.org/extending/)
- [robolectric.org/configuring/](https://robolectric.org/configuring/)
- The Robolectric source's `Sandbox`, `SandboxClassLoader`,
  `InstrumentationConfiguration`, `ShadowWrangler`, and
  `InvokeDynamicSupport` classes — when the docs are insufficient, these
  five are where the truth lives.
