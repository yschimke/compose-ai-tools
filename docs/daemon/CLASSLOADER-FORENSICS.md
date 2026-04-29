# Classloader forensics — what loads what, from where

> **Status:** design proposal for a diagnostic tool. No implementation
> shipped yet. Born out of the B2.0 Android S3.5 follow-up failures —
> two distinct attempts (ASM bytecode swap; dual-sourceset pre-compile)
> both tripped on Compose-Android reflection in different ways, and we
> don't have ground truth on *what's actually loaded by which
> classloader* in either path. This doc spec's the dump that gives us
> ground truth.

## Why

The Android save-loop story (B2.0) works on desktop. The classloader
split, the child-first delegation, the bridge ferry — all proven on
the desktop daemon. Android skips with a `NoSuchMethodException` on
`getDeclaredComposableMethod` whose root cause we *suspect* is
classloader-identity skew but have not directly observed. Two prior
attempts at the Android-side test fixture failed for *different*
reasons:

- ASM bytecode swap (Option 2): hashed method names emitted by the
  Compose compiler plugin defeated `ClassRemapper`'s descriptor
  rewrite.
- Dual-sourceset pre-compile (Option 1): byte-shape-equivalent
  bytecode still failed `getDeclaredComposableMethod` lookup.

Without empirical data on the loaded-class graph, we're guessing. The
fix for the survey-shaped question — *what is each Robolectric +
Compose + Android dependency, where does it come from, who loaded
it?* — is a small forensic tool that runs in two configurations
(working standalone Gradle Test, broken daemon path) and dumps a
diffable manifest.

## What to capture per "interesting" class

For each class in the survey set, record:

```jsonc
{
  "fqn": "androidx.compose.runtime.Composer",
  "classloader": {
    "type": "org.robolectric.internal.bytecode.InstrumentingClassLoader",
    "identityHashCode": "0x12ab34cd",
    "name": "robolectric-sandbox-1",       // or null if unset
    "parent": {
      "type": "jdk.internal.loader.ClassLoaders$AppClassLoader",
      "identityHashCode": "0x55667788",
      "name": "app",
      "parent": { "...": "..." }
    }
  },
  "codeSource": {
    "location": "file:/.../caches/compose-runtime-1.10.0.jar",
    "checksum": "sha256:abcd…",            // optional; bounded I/O cost
    "isJar": true,
    "isDirectory": false
  },
  "instrumentation": {
    "robolectricInstrumented": true,        // detected via known marker
    "robolectricShadowed": false,
    "method": "InstrumentingClassLoader applied bytecode rewrite"
  },
  "package": {
    "specificationVersion": "1.10.0",
    "implementationVersion": "1.10.0",
    "implementationVendor": "JetBrains s.r.o."
  },
  "moduleHash": "sha256:abcd…"               // for redefineClasses-style
                                            // identity comparison
}
```

The `instrumentation` block is the key field for Android. If the
working standalone path shows `robolectricInstrumented: true` for the
same `Composer` class that the daemon path shows
`robolectricInstrumented: false` (or different parent classloader),
that's the load-bearing finding.

## Survey set — which classes to interrogate

**Mandatory** (any of these differing between the two paths is
diagnostic):

- Compose runtime: `androidx.compose.runtime.Composer`,
  `androidx.compose.runtime.Composition`,
  `androidx.compose.runtime.Recomposer`,
  `androidx.compose.runtime.LaunchedEffect`.
- Compose UI: `androidx.compose.ui.platform.LocalContext`,
  `androidx.compose.ui.platform.LocalConfiguration`,
  `androidx.compose.ui.platform.LocalView`.
- Compose UI test: `androidx.compose.ui.test.junit4.AndroidComposeTestRule`
  (the type returned by `createAndroidComposeRule`),
  `androidx.compose.ui.test.junit4.ComposeTestRule`.
- Compose runtime reflect: `androidx.compose.runtime.reflect.ComposableMethod`
  (the type that throws the `NoSuchMethodException` we're chasing).
- Roborazzi: `com.github.takahirom.roborazzi.RoborazziKt`,
  `com.github.takahirom.roborazzi.RoborazziOptions`.
- Robolectric: `org.robolectric.RuntimeEnvironment`,
  `org.robolectric.shadows.ShadowApplication`,
  `org.robolectric.shadows.ShadowResources`,
  `org.robolectric.RobolectricTestRunner`,
  `org.robolectric.internal.bytecode.InstrumentingClassLoader`,
  `org.robolectric.internal.SandboxFactory` (the construction site).
- Android framework: `android.app.Activity`, `android.content.res.Resources`,
  `androidx.activity.ComponentActivity`, `android.os.Looper`.
- JUnit: `org.junit.runner.RunWith`, `org.junit.runners.model.Statement`,
  `org.junit.runners.JUnit4`.
- The user's preview class. For the working standalone test:
  whatever the existing `:renderer-android` test fixture exposes
  (e.g. a `RedSquare` `@Preview`). For the daemon path: same FQN,
  loaded via the harness fixture path.

**Daemon-only** (only meaningful in the daemon path):

- `ee.schimke.composeai.daemon.bridge.DaemonHostBridge`
- `ee.schimke.composeai.daemon.RobolectricHost`
- `ee.schimke.composeai.daemon.RenderEngine`
- `ee.schimke.composeai.daemon.SandboxHoldingRunner`
- `ee.schimke.composeai.daemon.UserClassLoaderHolder`
- The current child `URLClassLoader` instance the holder owns.

**Sample of opportunity** (cheap to add; might surface unexpected
divergence):

- `kotlinx.coroutines.CoroutineDispatcher`
- `kotlin.reflect.KClass`
- `androidx.lifecycle.ViewModel`
- The Compose compiler plugin's runtime helper:
  `androidx.compose.runtime.internal.ComposableLambdaImpl`

## Robolectric runtime-config dump

In the same forensic pass, capture the active Robolectric
configuration:

```jsonc
{
  "robolectric": {
    "apiLevel": 34,
    "qualifiers": "+w360-h640-port-mdpi",
    "fontScale": 1.0,
    "applicationClass": "android.app.Application",
    "graphicsMode": "NATIVE",
    "looperMode": "PAUSED",
    "instrumentationConfiguration": {
      "instrumentedPackages": ["android.*", "androidx.*", "..."],
      "doNotInstrumentPackages": ["java.*", "kotlin.*", "..."],
      "doNotAcquirePackages": [
        "java.*", "kotlin.*",
        "ee.schimke.composeai.daemon.bridge",      // daemon path only
        "..."
      ]
    },
    "sandbox": {
      "factoryClass": "org.robolectric.internal.SandboxFactory",
      "instrumentingClassLoaderIdentity": "0x12ab34cd",
      "ageMs": 12345,
      "renderCount": 7
    }
  },
  "system": {
    "javaVersion": "17.0.10",
    "javaVendor": "Eclipse Adoptium",
    "vmName": "OpenJDK 64-Bit Server VM",
    "osArch": "aarch64",
    "osName": "Mac OS X"
  }
}
```

Sources of these values:

- `RuntimeEnvironment.getApiLevel()` / `RuntimeEnvironment.getQualifiers()`
- `Looper.getMainLooper().getThread().getName()` — should be
  `Test worker` (working standalone) or `compose-ai-daemon-host`
  (daemon path)
- `RuntimeEnvironment.getApplication().getClass().getName()`
- The bytecode-rewrite policy can be inspected by reflecting into
  `InstrumentingClassLoader.getInstrumentationConfiguration()` if the
  field is reachable; otherwise fall back to the API-visible filters.

## Where the forensic test runs

Two configurations, **same survey set**, both writing to disk:

### Configuration A — working standalone

Lives at
`renderer-android/src/test/kotlin/.../ClassloaderForensicsTest.kt`.
A JUnit `@Test` annotated `@RunWith(RobolectricTestRunner::class)`
that:

1. Walks the survey set, captures per-class data.
2. Captures Robolectric runtime config.
3. Writes JSON to
   `renderer-android/build/reports/classloader-forensics/standalone.json`.

This is the *control* dump — what we get when `getDeclaredComposableMethod`
works. Run via `./gradlew :renderer-android:test --tests
"ClassloaderForensicsTest"`.

### Configuration B — daemon path

Lives at
`daemon/android/src/test/kotlin/.../ClassloaderForensicsDaemonTest.kt`.
A JUnit `@Test` annotated `@RunWith(SandboxHoldingRunner::class)` (the
daemon's existing variant of `RobolectricTestRunner` that holds the
sandbox open via the dummy-`@Test` pattern). Submits a single render
request whose body is "dump the survey set" instead of running the
real RenderEngine. Writes JSON to
`daemon/android/build/reports/classloader-forensics/daemon.json`.

Reuses `RobolectricHost`'s render-thread + bridge dispatch; the dump
runs in the sandbox classloader, with the daemon-side `UserClassLoaderHolder`
instantiated and a child `URLClassLoader` active so the survey can
include the user's preview class loaded via the child.

### Configuration C (optional, future) — VS-Code-spawned daemon

Same dump but driven via the harness's `RealAndroidHarnessLauncher`
spawning the daemon as a real subprocess. Goal: catch any
configuration drift between the in-test-JVM daemon and the actually-
spawned daemon. **Skip for v1**; add only if A/B diff doesn't surface
the answer.

## Invocation (v0)

End-to-end:

```
./gradlew :renderer-android:test \
          --tests "ee.schimke.composeai.renderer.ClassloaderForensicsTest" \
          :daemon:android:test \
          --tests "ee.schimke.composeai.daemon.ClassloaderForensicsDaemonTest" \
          :daemon:harness:dumpClassloaderDiff
```

(`:renderer-android` and `:daemon:android` are AGP `library` modules
whose top-level `:test` lifecycle delegates to `:testDebugUnitTest`.)

The diff task lives in `:daemon:harness:dumpClassloaderDiff` and writes
`docs/daemon/classloader-forensics-diff.{md,json}`. Per Decision 5 below, v0
is a developer-invoked diagnostic, not a CI gate.

**Forensic-dump payload routing.** Per the "don't widen the core's sealed
hierarchy" constraint, the daemon-side test routes its forensic-dump request
through the existing `RenderRequest.Render.payload` field with a sentinel
prefix (`forensic-dump=…;survey=…`). `RobolectricHost.SandboxRunner.dispatchRender`
detects the prefix and dispatches to a dedicated `runForensicDump` branch
that calls `ClassloaderForensics.capture(...)` reflectively (so the host
module's main classpath doesn't need a compile-time link to the forensics
library). Constants on `RobolectricHost`'s companion (`FORENSIC_DUMP_PREFIX`,
`FORENSIC_DUMP_KEY`, `FORENSIC_SURVEY_KEY`) define the wire shape.

## Output format

JSON, both for human readability and for `jq`-able diffing.
File layout matches the schema sketched above. One file per
configuration; sibling `diff.json` and human-readable
`diff.md` produced by a small Kotlin diff tool (or a `jq` script):

- Per-class diffs: classloader-identity / codeSource / instrumentation
  fields where A and B differ.
- Robolectric-config diffs: per-key A vs B.
- "Classes only in A" / "classes only in B" sets.
- A concise summary of "the suspected divergence":
  same FQN, different classloader-identity, different codeSource —
  the smoking gun shape.

## Implementation seam

A single shared `ClassloaderForensics` library class in
`:daemon:core` exposing:

```kotlin
object ClassloaderForensics {
    fun capture(
        surveySet: List<String>,            // class FQNs to interrogate
        robolectricConfig: RobolectricConfigSnapshot? = null,
        out: File,
    )
    fun diff(a: File, b: File, out: File)
}
```

Both Configuration A and B call `capture(...)` with their respective
context. The test bodies are tiny (~30 lines each); the heavy lifting
is in the library. Living in `:daemon:core` means it has no
Robolectric/Compose dependency at the type level — it works against
`Class<?>` and `ClassLoader` reflection only — and both
`:renderer-android` (the working standalone path) and
`:daemon:android` can depend on it without circular deps.

The library is renderer-agnostic; desktop could call it too if we ever
want forensic dumps on the desktop side (e.g. to validate the
classloader split there). But the primary use case is the Android
mystery.

## Sanity checks

Before believing the diff, verify the dump itself:

1. Run Configuration A twice in a row. The two dumps should be
   byte-identical (modulo `ageMs`/timestamps; exclude those fields
   from the diff). If not, the dump is non-deterministic and the
   diff is meaningless.
2. Run Configuration A and B in *the same* JVM (e.g. the same Gradle
   Test fork). Survey classes loaded by both paths via the *same*
   classloader should be identical in both dumps. If not, we have a
   bigger problem than the daemon path.
3. Run the dump on a class we *know* is loaded the same way in both
   (`java.lang.String`, `kotlin.Unit`). Should always match.

If sanity checks pass: trust the diff.

## Diff interpretation guide

Possible findings, in order of likelihood:

- **`Composer` loaded by different classloaders.** The smoking gun for
  classloader-identity skew. Working A loads via
  `InstrumentingClassLoader`; B loads via the user's child
  `URLClassLoader` (then delegates to parent, but the child is the
  declared classloader). `getDeclaredComposableMethod`'s parameter
  type comparison sees mismatched `Class<Composer>` instances → throws.
- **Different Robolectric `instrumentationConfiguration`.** The
  daemon path's `SandboxHoldingRunner` extends
  `createClassLoaderConfig()` to add the bridge package to
  `doNotAcquirePackage`. If that extension accidentally also changed
  `instrumentedPackages` or filters out `androidx.compose.*`, the
  Compose runtime classes wouldn't get instrumented and the working
  vs broken codeSource would diverge.
- **Same FQN loaded twice.** A class that should be a singleton at the
  classloader level is reachable via two different loaders. Surfaces
  in the diff as "this class is in both dumps but with different
  loader identity *within the same configuration*."
- **Different versions of a JAR.** If the daemon path's classpath
  resolves a different version of the Compose runtime than the
  standalone test, the instrumented vs non-instrumented bytecode is
  the side-effect. Surfaces as different `codeSource.location` for
  the same FQN.
- **The user's preview class loaded by an unexpected loader.** If
  `MutableSquare` resolves via the parent's classpath rather than the
  child URLClassLoader, the child-first delegation isn't actually
  child-first. Diagnostic: dump shows the user class in both A and B
  as loaded by `InstrumentingClassLoader` directly (not via child).

## Phasing

**Forensics.v0** — Configurations A and B both implemented; diff
script ships; sanity checks pass. Land as a small standalone task,
not part of any larger feature work. Marked done when running the
two dumps actually produces a diff that points at *something*.

**Forensics.v1** — once v0 surfaces the actual issue, we'd extend
based on what we found. Possible follow-ups:

- A real-mode Configuration C (subprocess daemon) if A vs B diff is
  inconclusive.
- An automated CI gate that runs the dump on every change to
  `RobolectricHost` / `SandboxHoldingRunner` / `UserClassLoaderHolder`
  and asserts no unexpected divergence between the standalone and
  daemon paths.
- A "live" version of the dump exposed via a JSON-RPC `daemon →
  client` notification, so the harness can call it during a real
  scenario.

## Decisions to surface

1. **Library placement: `:daemon:core` vs a new top-level
   `:tools:classloader-forensics`.** Core is renderer-agnostic and
   the right home if we want desktop dumps too. A standalone tool is
   cleaner if this is purely Android-bug-hunting and we never need
   the desktop dump. Recommend core; revisit if the renderer-agnostic
   surface invariant gets uncomfortable.

2. **Dump format JSON vs structured plaintext.** JSON is `jq`-able and
   diff-friendly. Plaintext is easier to read at a glance. Recommend
   JSON for the canonical dump + an optional `diff.md` markdown
   rendering for human review.

3. **Survey set scope.** Mandatory + optional split above. Errs on the
   side of more — a 30-class dump is still ~10KB JSON, dump cost is
   negligible. Confirm the list (or extend) before implementation.

4. **Code-source checksums.** Including SHA-256 of each JAR/dir
   surfaces "same FQN, different bytes" cases that would otherwise
   look identical. Cost: tens of ms for the working standalone path's
   ~30 classes. Recommend on by default; flag-controllable if it
   becomes a measurable test-time cost.

5. **Configuration C (subprocess) ship in v0 or v1?** The harness
   already spawns the real Android daemon for `S3*AndroidRealModeTest`;
   wiring a third config would be additive but adds CI surface. v0
   should be enough unless the in-test-JVM daemon and the
   subprocess-spawned daemon turn out to differ — which itself would
   be an interesting finding.

## What this isn't

- **Not a fix for Android S3.5.** It's a *tool* for finding the fix.
  Until we run the dumps and look at the diff, we don't know which
  of the five interpretations above is correct.
- **Not a permanent runtime feature.** `ClassloaderForensics.capture(...)`
  is a diagnostic library; the test bodies that call it are
  development-time artifacts, not part of the daemon's production
  surface. The daemon doesn't dump on every render.
- **Not a replacement for B2.0c or any other planned task.** Strictly
  forensics for the open Android question.
