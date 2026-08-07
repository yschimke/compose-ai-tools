# Renderer compatibility notes

The renderer ships as `ee.schimke.composeai:renderer-android` (an AAR) and is
resolved into each consumer project's `composePreviewAndroidRenderer<Variant>`
configuration. AGP's `process<Variant>Resources` builds the unit-test merged
resource APK (`apk-for-local-test.ap_`) from the consumer's **own** dep graph,
so if a transitive pulls a newer AndroidX AAR into the test classpath than the
consumer's main variant declares, classes and resources disagree at runtime.

**Don't catalogue the specific failure modes here anymore — run
`compose-preview doctor --explain` in the consumer project.** The plugin's
`CompatRules` owns the current list of known AAR/R.id mismatches, and `doctor`
prints the rationale, the triggering library, and the remediation per finding.
The VS Code extension surfaces the same findings in the Problems panel (via
`:<module>:composePreviewDoctor` → `build/compose-previews/doctor.json`).

## What still lives here

- **When to add a rule.** A new rule goes into
  [`CompatRules.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/tooling/CompatRules.kt)
  when a new AndroidX AAR adds an R.id field that older transitives don't
  have, when Gradle can select a platform sibling whose bytecode shape does
  not match the Android renderer's expectations, or when an AGP step the
  renderer forces (e.g. the unit-test manifest merge) fails with an opaque
  message we can pre-empt with a clear finding. Add a test in
  `CompatRulesTest.kt` with both the triggering and non-triggering paths.
- **The four mitigation mechanisms** in the renderer/plugin that must move
  together — remove any one and the compat matrix re-opens:
  1. `compileOnly` for Compose / Activity / UI-test libs in
     [`renderers/android/build.gradle.kts`](../renderers/android/build.gradle.kts).
     Consumer's versions win at runtime, so classes match their APK.
  2. `rendererConfig.extendsFrom(testConfig)` in
     [`AndroidPreviewSupport.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt).
     Renderer transitives resolve in the same Gradle graph as consumer test
     deps — one coherent max-version set, no per-JAR classpath-ordering
     hazards.
  3. Unconditional injection of `androidx.compose.ui:ui-test-manifest` into
     `testImplementation` (same file). AGP's manifest merger only walks the
     consumer's declared deps — the renderer AAR transitively carrying the
     activity entry isn't enough.
  4. KMP sibling substitution on the Android renderer configuration (same
     file). AndroidX and Compose Multiplatform publish platform-specific
     coordinates such as `foo-android`, `foo-desktop`, and `foo-jvmstubs`.
     Consumers without the Kotlin Android plugin's platform-type compatibility
     rule can resolve desktop/JVM-stub siblings on an Android unit-test
     classpath. The renderer config rewrites scoped `androidx.*` and
     `org.jetbrains.compose.*` `-desktop` / `-jvmstubs` requests to the matching
     `-android` coordinate; `compose-preview doctor` reports the same skew for
     the consumer's own test tasks.

## Desktop (Compose Multiplatform) renderer

The desktop backend has the mirror-image hazard. `renderer-desktop` (and
`daemon-desktop`) bundle their own JetBrains Compose + Skiko, pinned to the
repo's `compose-multiplatform` (currently 1.10.3). A consumer module tracks
*its own* Compose Multiplatform version, which can be newer. The plugin builds
the render subprocess classpath from two configurations — the renderer tool
config (`composePreviewRenderer` / `composePreviewDesktopDaemon`) and the
consumer's runtime classpath — so if those resolve as **separate** graphs, both
Skikos land on the classpath. Skiko's Java bindings and its native library must
be the same version; the newer bindings (e.g. CMP 1.11's
`TextStyleKt._nSetFontEdging`) against the renderer's older native `.so` fail at
render time with `UnsatisfiedLinkError`, not a clean resolution error (issue
#1844).

The mitigation mirrors Android's #2 above:
`ComposePreviewTasks.alignDesktopToolWithConsumerGraph` makes the renderer tool
config `extendsFrom` the consumer's runtime classpath and copies its
JVM/Kotlin platform attributes, so the two resolve in **one** graph. Gradle's
conflict resolution then picks a single coherent max version of Skiko (and the
rest of the JetBrains Compose stack) — the consumer's, whose bindings and
native library agree. The renderer's own code is compiled against the pinned
floor (1.10.3) but runs against whatever coherent version the consumer pulls;
because Skiko/Compose minor bumps are additive at the API surface the renderer
uses, the consumer's newer stack satisfies it. This is scoped to genuinely
JVM/desktop consumer classpaths — a pure-Android KMP fallback
(`androidRuntimeClasspath`) is left alone (no `androidJvm` renderer variant
exists, and that classpath can't feed the JVM renderer anyway). Covered by
`DesktopRendererGraphAlignmentFunctionalTest`.

### The serve host is a version FLOOR for every catalog it renders live

The graph-alignment mitigation above works because Gradle resolves one graph and
picks a coherent max version. **A published bundle rendered by `compose-preview
serve` has no such resolution step**, and that is the failure mode of issue
#3447.

The rule it implies:

> The serve host's `compose-multiplatform` must be **>= the newest
> `compose-multiplatform` of any catalog served from it.**

A catalog that upgrades *ahead* of the host breaks its own live lane, and it
does so in the worst possible way — **silently, at regeneration time**, with no
build failure anywhere. Baked PNGs keep serving, so the catalog looks healthy
until someone requests an override; only then does every live render 503.

What #3447 looked like concretely:

| | compose-multiplatform | skiko |
|---|---|---|
| serve host (compose-ai-tools) | 1.10.3 | 0.9.37.4 |
| `m3-catalog` | 1.11.1 | **0.144.6** |

`org.jetbrains.skia.PathBuilder` is new in the 1.11 line. The bundle's newer
`org.jetbrains.skia.*` bindings paired with the host sidecar's older `libskiko`,
whose native exports no `PathBuilder_*` symbol at all, so every render touching
a path — which for Material 3 is nearly all of them — died with:

```
UnsatisfiedLinkError: 'long org.jetbrains.skia.PathBuilderKt.PathBuilder_nMakeFromPath(long)'
```

3794 of 3990 renders (95%) on the largest published catalog.

Note this is **not** a hole in `ServeBundleDaemon.shouldPrecedeDaemonSidecar` —
that rule already promotes `org.jetbrains.skiko` alongside `org.jetbrains.compose`
precisely so bindings and native move together. Promotion can only reorder what
the bundle actually carries; it cannot conjure a newer native than the host has
ever shipped. **The fix is releasing the host, not editing the classpath rules.**

Two practical consequences:

1. **Bumping `compose-multiplatform` in this repo is a serve-host release
   concern.** Cutting a release that does *not* move it ships the identical
   skiko and fixes nothing — the host must be re-released *and redeployed*.
2. **A catalog's CMP bump is a cross-repo change.** Land the host bump, release,
   and redeploy *before* the catalog regenerates against a newer line.

## Known findings that still warrant a note

### A library declares a higher minSdk than the module

compose-preview renders inside a Robolectric **unit test**, so applying the
plugin flips `testOptions.unitTests.isIncludeAndroidResources = true` and the
render/daemon tasks depend on AGP's unit-test manifest merge
(`process<Variant>UnitTestManifest`). That merge enforces `uses-sdk`: a
transitive library whose `minSdkVersion` is higher than the consumer module's
fails it with

```
uses-sdk:minSdkVersion 26 cannot be smaller than version 35 declared in library [ai.koog:koog-agents-android]
```

The conflict is the consumer's (`./gradlew :module:testDebugUnitTest` with
resources included fails identically), but absent the plugin a module may never
trigger the merge — so compose-preview is what surfaces it. The
`library-minsdk-exceeds-module` rule in `CompatRules` reads each AAR's declared
`minSdkVersion` (via the `android-manifest` artifacts on the unit-test
classpath, parsed by `AarManifestReader`) and compares it against
`defaultConfig.minSdk`, turning the opaque AGP failure into a doctor finding.

minSdk is meaningless for a host-side unit test, so the recommended fix is the
unit-test-only `tools:overrideLibrary` escape hatch (the finding names the exact
library packages it parsed):

```xml
<!-- src/test/AndroidManifest.xml (or src/androidUnitTest/ for a KMP/CMP module) -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-sdk tools:overrideLibrary="ai.koog.agents" />
</manifest>
```

Raising the module's `minSdk` is the alternative, correct only when you intend
to ship the higher floor on-device. We deliberately do **not** override this
silently — that would mask the conflict for the consumer's own unit tests and
let a genuinely API-35 library link against a lower-minSdk module unnoticed.

## Tile-rendering defaults

Tile previews render on an opaque black background and pick up the round
device crop automatically — both happen unconditionally for
`params.kind == PreviewKind.TILE` in `TilePreviewRenderer` and
`RobolectricRenderTest` respectively. Consumers that want something other
than black can paint inside the tile itself; the hosting FrameLayout's
background is intentionally not exposed as a knob since it mirrors the
watchface substrate, not the tile's own surface.
