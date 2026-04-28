# How Robolectric runs

> A primer on Robolectric's architecture, kept deliberately separate
> from this project's design docs. Read this when you need to reason
> about classloader behaviour, shadow dispatch, or sandbox lifecycle
> while debugging the daemon — but the daemon's specifics live in
> [DESIGN.md](DESIGN.md), [CLASSLOADER.md](CLASSLOADER.md), and
> [CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md). This doc is
> the "what does Robolectric *itself* do" reference.
>
> Aimed at someone who can read Robolectric's source if they need to,
> but wants the architecture mental model first. Cites the canonical
> upstream pages: [robolectric.org/architecture/](https://robolectric.org/architecture/)
> and [robolectric.org/extending/](https://robolectric.org/extending/).

## Why Robolectric exists

Stock Android framework code on the build classpath ships as `android.jar`
**stubs** — every method body is `throw new RuntimeException("Stub!")`.
That's enough for the IDE / kotlinc to compile against the public API but
won't run. Real-device tests call the real framework on a phone or
emulator; Robolectric's pitch is "run your Android tests on your build
JVM, fast, without an emulator". To do that, it needs to put **real
Android framework code** on the classpath at *test time*, in a way that
keeps your build-time `android.jar` untouched.

That single goal — swap out stubs for real implementations at test time
— is the source of every architectural choice below.

## The big picture in one paragraph

For each `@Test` method, Robolectric creates a **`Sandbox`**. The
sandbox owns a custom **`SandboxClassLoader`** (called
`AndroidSandbox$SdkSandboxClassLoader` in 4.13+ — historically
`InstrumentingClassLoader`). That classloader loads classes from a
version-specific **`android-all` jar** containing real Android
framework code (lazily downloaded via `MavenArtifactFetcher`),
**rewrites their bytecode at load time** to redirect every method call
through an `invokedynamic` indirection, and via that indirection allows
**shadow classes** registered with `@Implements(targetClass)` to
intercept and replace specific framework method bodies with JVM-friendly
fakes. Your `@Test` method runs *inside* the sandbox classloader; from
its point of view, `Activity` / `Resources` / etc. are real classes
with real methods, but most of those method bodies route to a shadow
that knows how to behave on a plain JVM.

That's the whole game. Everything else is implementation detail.

## Classloader hierarchy

A `@Test` method inside Robolectric runs under a stack of three loaders:

```
[bootstrap classloader]                                      ← java.* / sun.* / kotlin.*
        ↑
[system / app classloader]                                   ← Robolectric's own classes,
        ↑                                                      JUnit, your test classpath
[SandboxClassLoader]    (per-@Test, disposable)              ← android.jar replacement, your
                                                               app-under-test classes (mostly),
                                                               framework + shadow bytecode
```

Java's classloader delegation is **parent-first by default**. When
`SandboxClassLoader.loadClass("foo.Bar")` is called, it asks its parent
(the app classloader) first, and only loads locally if the parent says
"not found". `SandboxClassLoader` overrides this for specific package
prefixes — *most* `android.*`, `androidx.*`, and the user's
under-test classes get loaded by the sandbox itself, *not* delegated up
— so the sandbox can apply its bytecode rewrite. `java.*` / `kotlin.*` /
JUnit always delegate up, because instrumenting them would be
catastrophic.

This is the load-bearing detail you need to understand to debug
classloader bugs in the daemon: **which loader resolves which class is
configured by package-prefix rules on `SandboxClassLoader`**, not by
the JVM defaults. If a class you expected to load via the sandbox loads
via the app loader instead, the sandbox's prefix list isn't matching.

The package filters are configurable via
`InstrumentationConfiguration`:

| Filter | Meaning | Default |
|---|---|---|
| `instrumentedPackages` | Bytecode-rewrite these package prefixes | `android.*`, `androidx.*`, `com.android.*`, etc. |
| `doNotInstrumentPackages` | Load locally but skip rewrite | Generally empty; carve-outs for specific classes |
| `doNotAcquirePackages` | Don't load locally — defer to parent | `java.*`, `kotlin.*`, JUnit, sometimes test-specific |

The third one (`doNotAcquirePackages`) is the one our daemon abuses:
the bridge package between the sandbox classloader and the host
classloader is in `doNotAcquirePackages` so that a `LinkedBlockingQueue`
created on the host is identity-equal to one observed inside the
sandbox.

## What "instrumentation" actually does to a class

When `SandboxClassLoader` decides to instrument a class (per the
prefix rules), it doesn't just load the bytes verbatim. It rewrites
every `INVOKEVIRTUAL` / `INVOKESPECIAL` / `INVOKESTATIC` instruction
into an `INVOKEDYNAMIC` that goes through Robolectric's own
`InvokeDynamicSupport` bootstrap method. That bootstrap, on first
invocation, calls `ShadowWrangler.findShadowMethodHandle(target,
method)` and gets back one of:

- A `MethodHandle` to the real Android framework method (no shadow
  applied).
- A `MethodHandle` to a `@Implementation`-annotated method on a
  registered shadow class.
- A guard that throws `Stub!` if the framework method has no shadow
  and trying to call the real one would crash on a plain JVM (e.g.
  most native-method paths).

`ShadowWrangler` caches the result per (class, method) pair. After the
first call, the dispatch is a single indirected `MethodHandle.invoke` —
roughly the cost of an interface method call.

The `final` keyword is also stripped from instrumented classes so
shadows can subclass them. Native methods get re-bound to either a real
JNI implementation (if Robolectric ships one — most graphics, audio,
and SQL paths do) or a no-op stub.

## How shadows get registered

Three mechanisms, in order of who-uses-them:

1. **Built-in shadows** (the bulk of Robolectric's value): `org.robolectric:shadows-framework`
   ships hundreds of `@Implements`-annotated classes covering everything
   from `ShadowApplication` to `ShadowResources` to `ShadowChoreographer`.
   The Robolectric Annotation Processor (RAP) generates a
   `ShadowProvider` for the package at compile time; Robolectric loads
   them via `ServiceLoader` at sandbox construction.
2. **`@Config(shadows = [...])`** on a `@Test` or test class: registers
   custom shadows for that test only. This is how project-specific
   shadows (e.g. `ShadowFontsContractCompat` in `:renderer-android`)
   plug in.
3. **`@Implements` directly on a shadow class file**: detected via the
   classpath scan; less common in practice.

All three feed into the same `ShadowWrangler` registry that
`InvokeDynamicSupport` consults. From the shadow's point of view, the
hook is an annotated method:

```kotlin
@Implements(SomeAndroidClass::class)
class ShadowSomeAndroidClass {
    @Implementation
    fun someMethod(): Int = 42
}
```

The shadow can also access the *real* framework instance via
`@RealObject`, which is how shadows that mostly delegate to the real
implementation (with one or two patched methods) work.

## Per-`@Test` lifecycle

Robolectric's default lifecycle:

1. JUnit invokes `RobolectricTestRunner.runChild(...)` for the next
   `@Test`.
2. The runner picks an SDK level (`@Config(sdk = …)` or
   `targetSdkVersion`) and looks up or constructs a `Sandbox` for that
   SDK.
3. The runner reflectively re-loads the test class through the
   sandbox's classloader. **The test class object the runner is now
   holding is *not* the same `Class<?>` as the one your IDE sees.**
4. `AndroidTestEnvironment.setUpApplicationState(...)` runs: boots
   `Looper`, sets up `ApplicationContext`, parses `AndroidManifest.xml`
   (or the test's `@Config(application = …)`), creates a fresh
   `Resources` instance, applies the qualifiers / SDK level / font
   scale.
5. The `@Test` method runs inside the sandbox.
6. `finallyAfterTest()` resets shadows via `@Resetter`-annotated
   methods (e.g. `ShadowApplication.reset()`), drains the looper, etc.

**Sandbox reuse across `@Test` methods.** Robolectric reuses sandboxes
when the SDK level / qualifiers / `@Config` shape matches — building a
fresh `SandboxClassLoader` is expensive (it's a multi-second operation
on cold; mostly the cost is loading + instrumenting the `android-all`
jar). So a test class with 10 `@Test` methods all under the same
`@Config(sdk = [35])` runs all 10 in the same sandbox; only `@Resetter`
state-reset runs between them.

This is what the daemon exploits: a *single* `@Test` method in the
sandbox, blocking on a queue, is exactly the same cost as the first
`@Test` of a normal Robolectric run — but instead of returning, it
hangs around indefinitely processing render requests, never paying
sandbox-creation cost again.

## SDK level, qualifiers, and `@Config`

`@Config(sdk = [N])` selects which `android-all-N-…jar` Robolectric
fetches and instruments. Within a single JVM run, you can have multiple
SDK-versioned sandboxes coexisting; switching `@Config(sdk = …)`
between tests forces a different sandbox.

`@Config(qualifiers = "+w360-h640-port-mdpi-en")` configures the
runtime `Configuration` — screen size, orientation, density, locale.
It's read on each test by `AndroidTestEnvironment` and applied via
`RuntimeEnvironment.setQualifiers(...)`. Changing qualifiers between
tests does *not* require a new sandbox — same classloader, different
`Configuration` instance.

`@Config(application = SomeApp::class)` swaps which `Application` class
gets instantiated. Same SDK, same qualifiers, but a different real
`Application` class — Robolectric reuses the sandbox.

The hierarchy matters: SDK is sandbox-bound; everything else is
per-test. Robolectric optimises sandbox reuse aggressively because that
SDK-bound classloader is the expensive thing.

## `RuntimeEnvironment` — the inside-the-sandbox singleton

Once your code is running inside the sandbox, the canonical handle to
"the current Robolectric state" is `RuntimeEnvironment`:

- `getApiLevel(): Int` — current SDK
- `getApplication(): Application` — the real Application instance
- `getQualifiers(): String` — current qualifier string
- `setQualifiers(qualifierString)` — re-apply qualifiers mid-test
- `setFontScale(scale)` — re-apply font scale
- `getMasterScheduler() / getMainLooper()` — looper / scheduler
  control

These are essentially per-sandbox global state. Mutating them from one
test affects the next test in the same sandbox unless `@Resetter` runs
between them.

## Native code and graphics

Some Android APIs ultimately call into native code (Skia rendering,
SQLite, audio). Robolectric ships with native implementations for many
of these via JNI — `libandroidmedia`, `libsqlite`, `libui`, etc. — that
get extracted to a temp directory and loaded by the sandbox at
construction. The native libs are version-bound to the `android-all`
artefact, so they implicitly track SDK level.

For the daemon's purposes, the `NATIVE` graphics mode (set via
`@GraphicsMode(NATIVE)`, or via `robolectric.graphicsMode=NATIVE`
system property) is what makes `captureRoboImage` actually produce
pixels — it routes `HardwareRenderer` calls through Robolectric's
shipped Skia-via-JNI rather than no-op'ing them. Without `NATIVE`, the
test "succeeds" with empty pixels.

## What happens when something goes wrong

Common failure modes, with their loud symptoms:

- **`NoSuchMethodException` on `getDeclaredComposableMethod`**: parameter
  type's `Class<?>` is from a different classloader than the one
  Compose runtime running inside the sandbox uses. Means a class
  expected to be in the sandbox loaded via the parent loader instead.
  See [CLASSLOADER-FORENSICS.md](CLASSLOADER-FORENSICS.md) — the dump
  shows which classes are misrouted.
- **`UnsatisfiedLinkError`**: native lib not extracted or the wrong
  SDK's native libs in scope. Usually a `@Config(sdk = …)` / native
  artefact mismatch.
- **`Stub!` `RuntimeException`**: the bytecode rewrite *didn't* apply
  to the calling class — i.e. the call is going to the build-classpath
  `android.jar` stubs, not the instrumented `android-all`. Means the
  caller didn't load via the sandbox's `SandboxClassLoader`. Same
  classloader-routing bug as the `NoSuchMethodException` shape.
- **`Application` not initialised** / `RuntimeEnvironment.getApplication()`
  returns null: `AndroidTestEnvironment.setUpApplicationState` hasn't
  run. Usually means you're trying to use Robolectric APIs outside a
  `@RunWith(RobolectricTestRunner)` test, or before Robolectric's
  test-lifecycle hooks fired.
- **Shadow not applying**: `@Config(shadows = [MyShadow::class])`
  missing, or the shadow class is loaded by a different classloader
  than the framework class it shadows (so `ShadowWrangler` doesn't
  match them). Usually surfaces as the real framework method running
  when you expected your shadow to.
- **Sandbox reuse breaking state assumptions**: a test mutates
  `RuntimeEnvironment` state that the next test expected to be fresh.
  `@Resetter` is per-shadow and only resets that shadow's state; if
  you have ad-hoc state outside a shadow, you have to reset it
  yourself.

## What this project specifically does on top of Robolectric

That's the daemon's design — covered in [DESIGN.md](DESIGN.md) and
[CLASSLOADER.md](CLASSLOADER.md). The short version: we hold one
sandbox open across many "renders" via a long-running `@Test` method
that blocks on a queue, and we load the user's compiled classes
through a disposable child classloader so an edit-and-recompile cycle
swaps user bytecode without losing the expensive sandbox boot.

## Further reading

- [robolectric.org/architecture/](https://robolectric.org/architecture/)
  — canonical upstream architecture page.
- [robolectric.org/extending/](https://robolectric.org/extending/) —
  shadow authoring + extending Robolectric's behaviour.
- The Robolectric source's `Sandbox`, `SandboxClassLoader`,
  `InstrumentationConfiguration`, `ShadowWrangler`, and
  `InvokeDynamicSupport` classes — when the docs are insufficient,
  these five are where the truth lives.
- [robolectric.org/configuring/](https://robolectric.org/configuring/)
  — the `@Config` annotation's surface area.
