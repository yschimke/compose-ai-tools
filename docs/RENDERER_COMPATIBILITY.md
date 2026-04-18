# Renderer compatibility notes

Background reading for anyone extending `compose-preview doctor`, debugging a
consumer render failure, or bumping the renderer-side Compose / AndroidX
versions. Covers the failure modes we know about, the mitigations already in
place, and the checks a future doctor pass could surface.

## Why there's a mismatch risk at all

The renderer ships as `ee.schimke.composeai:renderer-android`, an AAR the
Gradle plugin resolves into a dedicated `composePreviewAndroidRenderer<Variant>`
configuration on the consumer project. That configuration is **not** one of
AGP's built-in configurations, so AGP's resource pipeline has no idea it
exists.

Consequence: `process<Variant>Resources` builds the merged unit-test APK
(`apk-for-local-test.ap_`) purely from the consumer's **own** dep graph. If
the renderer AAR transitively drags in a newer version of an AndroidX library
than the consumer declares, three things are true at the same time:

1. The consumer's resource APK contains the **older** version's resources.
2. Gradle's conflict resolution puts the **newer** version's classes on the
   test JVM classpath (max wins across rendererConfig + testConfig).
3. Robolectric loads the new classes, which reference resources
   (`R.id.*`) that only exist in the new version — and those resources are
   missing from the APK.

The test crashes at class-init time with some `NoClassDefFoundError` or
`NoSuchFieldError`. The failure surface is entirely about **classes and
resources coming from different versions of the same library**.

Three surfaces hit this in practice:

| Surface | What moves independently |
|---------|---------------------------|
| Renderer AAR → consumer | Compose BOM, activity-compose, androidx.core versions |
| Consumer test classpath | Pinned by the consumer's own deps |
| Consumer resource APK | Generated from the consumer's main-variant deps |

## Known failure signatures

All three failures are the same class of issue — classes on classpath at a
newer version than resources in the merged APK. Symptoms:

### activity-compose 1.11+ on a consumer with an older activity

```
java.lang.NoClassDefFoundError: androidx/navigationevent/R$id
    at androidx.navigationevent.ViewTreeNavigationEventDispatcherOwner.set
    at androidx.activity.ComponentActivity.initializeViewTreeOwners
    at androidx.activity.compose.ComponentActivityKt.setContent
```

- **Triggering change:** `androidx.activity:activity:1.11.0` pulled in
  `androidx.navigationevent:navigationevent:1.0.0` as a required dep. The
  `ComponentActivity.initializeViewTreeOwners` codepath now accesses
  `R.id.view_tree_navigation_event_dispatcher_owner` which lives in the new
  AAR's R class.
- **Who hits it:** consumers whose own `activity-compose` version is 1.10.x or
  older. Observed on `android/wear-os-samples/WearTilesKotlin` (activity-compose
  1.8.2 via Compose BOM ~2024.09).

### compose-ui 1.10+ on a consumer with older androidx.core

```
java.lang.NoSuchFieldError: Class androidx.core.R$id does not have member
field 'int tag_compat_insets_dispatch'
    at androidx.core.view.ViewCompat$Api21Impl.setOnApplyWindowInsetsListener
    at androidx.compose.ui.layout.InsetsListener.onViewDetachedFromWindow
```

- **Triggering change:** `androidx.core:core:1.16.0` added
  `R.id.tag_compat_insets_dispatch`. compose-ui 1.10+ transitively depends on
  core 1.16+ and calls `ViewCompat.setOnApplyWindowInsetsListener`, which
  reads that field via reflection.
- **Who hits it:** consumers with `androidx.core:core < 1.16.0`. Typically
  consumers using Compose BOM released before 2025-Q1.

### activity 1.13 vs lifecycle 2.x mismatch

```
java.lang.NoSuchFieldError: Class androidx.lifecycle.ReportFragment does not
have member field 'androidx.lifecycle.ReportFragment$Companion Companion'
    at androidx.activity.ComponentActivity.onCreate
```

- **Triggering change:** `activity:1.13` expects `ReportFragment.Companion`,
  added in `lifecycle:2.9.x`. On a classpath that has both activity-1.13
  (from our renderer) and lifecycle-runtime-2.3 (from older transitive
  deps), classloader order determines which wins per-class → crash.
- **Who hits it:** seen transiently while experimenting with classpath
  ordering before the `extendsFrom(testConfig)` fix. Shouldn't recur under
  the current architecture but is worth knowing about when debugging.

## Current mitigations

Three mechanisms work together — removing any one of them re-opens the
failure modes above.

### `compileOnly` in renderer-android

[`renderer-android/build.gradle.kts`](../renderer-android/build.gradle.kts)
declares Compose / Activity / Compose-UI-test libs as `compileOnly` +
matching `testImplementation`:

```kotlin
compileOnly(platform(libs.compose.bom))
compileOnly(libs.compose.ui)
compileOnly(libs.activity.compose)
compileOnly("androidx.compose.ui:ui-test-junit4")
compileOnly("androidx.compose.ui:ui-test-manifest")
// ... etc
```

Effect: these libs are **not** transitively pulled in from the renderer AAR at
consumer resolve time. The consumer's own versions flow through to the test
JVM classpath unchanged, matching their resource APK.

**Keep**: Roborazzi + `roborazzi-accessibility-check` as `implementation`.
They don't contribute AndroidManifest entries or R.id resources that the
consumer has to have merged.

### `extendsFrom(testConfig)` in the plugin

[`AndroidPreviewSupport.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt)
has `rendererConfig.extendsFrom(testConfig)`. Effect: Gradle resolves the
renderer's remaining transitives **together with** the consumer's
test-runtime deps as one dependency graph. Max-version wins once per module,
each class appears in exactly one JAR, and `FileCollection.from()` ordering
stops mattering for the combined classpath.

### Consumer-scope `ui-test-manifest` injection

Same file, `testImplementation` injection:

```kotlin
project.dependencies.add(
    "testImplementation",
    "androidx.compose.ui:ui-test-manifest",
)
```

AGP's manifest merger only walks the consumer's declared deps. The renderer
AAR transitively having ui-test-manifest is not enough — AGP won't pick it
up unless it lives in a test-scope consumer configuration. This injection
is **unconditional** (it used to gate on the a11y feature; that gate was
removed because any ComposeTestRule-backed path needs the manifest entry).

## Things a future `compose-preview doctor` could check

The integration test guards against regression in our own codebase, but it
doesn't help a user of a broken release diagnose what's wrong in their
project. `compose-preview doctor` runs inside the consumer, so it can
introspect the consumer's Gradle model and warn before `renderPreviews`
crashes.

Concrete checks, in rough order of usefulness:

### Hard errors (block rendering, explain fix)

- **Consumer missing `ui-test-manifest` on test classpath.** Resolve
  `debugUnitTestRuntimeClasspath` and look for `androidx.compose.ui:ui-test-manifest`.
  If absent, the plugin injection didn't take effect (misconfigured extension,
  old plugin version, extension opt-out). Point to `composePreview { enabled = true }`.

- **Consumer's `apk-for-local-test.ap_` missing an R class that the
  renderer's classpath references.** This is harder to check statically but
  the symptoms are predictable. At minimum: resolve
  `debugUnitTestRuntimeClasspath` and `${variantName}RuntimeClasspath`, and if
  a class that was added in a known AAR version is in the runtime classpath
  but the AAR version in the main-variant resource-contributing config is
  older, flag it. Candidates worth hard-coding:
  - `androidx.navigationevent:navigationevent` presence in test classpath →
    require `androidx.activity:activity >= 1.11.0` on the consumer's main
    variant.
  - `androidx.compose.ui:ui >= 1.10.0` on test classpath → require
    `androidx.core:core >= 1.16.0` on the consumer's main variant.
  - `androidx.lifecycle:lifecycle-runtime >= 2.9.0` class refs on test
    classpath → require `androidx.lifecycle:lifecycle-runtime >= 2.9.0` on
    the consumer's main variant.

### Warnings (the render will probably work but here be dragons)

- **Consumer's Compose BOM is significantly older than the renderer's
  build-time BOM.** Soft signal. Not guaranteed to break, but worth a
  "known-tested combination" nudge. The renderer records its build-time
  BOM in the jar manifest (see `PluginVersion`); doctor can compare.

- **Consumer declares `implementation(compose.ui)` without a BOM.** Makes
  version conflicts harder to reason about (no lockstep across compose-*
  artifacts). Suggest the BOM.

- **Consumer has `ui-test-manifest` on `debugImplementation` instead of
  `testImplementation`.** Works but leaks the test activity into the debug
  app. Suggest moving to `testImplementation`.

- **Consumer's `unit_test_config_directory` is missing from the classpath.**
  Already handled by the plugin, but if someone has customised their Test
  task and dropped it, `TileRenderer` will crash on `Unknown resource value
  type 0` and look utterly confusing. See the comment at
  [`AndroidPreviewSupport.kt`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt)
  around `unitTestConfigDir` for the full reason.

### Tile-specific checks

- **`@androidx.wear.tiles.tooling.preview.Preview` on the classpath but
  `params.kind == "TILE"` count in `previews.json` is zero.** Means
  discovery failed to recognise the annotation. Usually a plugin-version
  mismatch between what's in `pluginManagement` and what's in the renderer
  AAR resolution, but can also be a ClassGraph classpath issue.

- **Tile previews render to near-blank PNGs.** Usually user-authoring
  (white text on transparent background — we hit this in sample-wear before
  the Material3 rewrite). Not a renderer bug, but doctor could sample the
  first tile PNG and check if it's > N% solid-colour as a heuristic. Noisy,
  probably not worth hard-coding.

- **Tile render timeout.** `TilePreviewRenderer.inflateAsync.get(10,
  SECONDS)` has a hard cap. If a consumer's tile takes longer to inflate
  (complex resource loading, etc.), they'd get a cryptic
  `TimeoutException`. Worth surfacing as "tile took N seconds; bump
  timeout or investigate".

## Where to hook this in

- Doctor already runs post-`discoverPreviews`, so `previews.json` exists
  and can be parsed for `kind` counts.
- Doctor can resolve Gradle configurations via the Tooling API — the CLI
  already does this for `renderPreviews` invocation. Extend
  `GradleConnector` to expose dependency resolution.
- Some checks (e.g. "is R.id.tag_compat_insets_dispatch going to be looked
  up?") need knowledge of the specific library → consumer mapping. Keep a
  small data table of known-breaking (min-consumer-version, min-renderer-version)
  pairs in the CLI, updated alongside renderer-android's own `libs.versions.toml`
  bumps. Each pair is an explicit "we know this combination breaks at
  runtime" entry; the absence of an entry is not a guarantee of
  compatibility.

## Tile rendering gaps (follow-ups)

Separate from the compatibility issues above, the tile render pipeline has a
couple of cosmetic gaps worth tracking:

- **Tile previews should default to a black background.** ProtoLayout tiles
  expect to render onto the watchface's dark substrate. Right now
  `TilePreviewRenderer` hosts the inflated tile inside a `FrameLayout` with
  no background, so the Compose substrate underneath (which currently only
  honours `showBackground` / `backgroundColor` from `@Preview` — both false
  on `@wear.tiles.tooling.preview.Preview`) leaves the PNG transparent →
  flattens as white. Fix: in `TilePreviewRenderer.TilePreviewComposable`,
  paint `Color.Black` on the hosting `Box` when
  `params.kind == PreviewKind.TILE`, regardless of `showBackground`. Or
  make it the Wear-default when `device` starts with `id:wearos_*`.

- **Round crop should apply automatically to tile previews.** Today the
  `RoundScreenOption` Roborazzi qualifier is added when
  `isRoundDevice(params.device) && params.showSystemUi`. Tile previews
  always have `showSystemUi = false` (they're not `@Composable`s hosting
  system UI), so the round qualifier never fires and tiles end up as
  rectangles. For tiles specifically, `isRound` alone should drive the
  qualifier — the tile itself is always rendered inside a round screen on
  the device. Change the condition to
  `isRoundDevice(params.device) && (params.showSystemUi || params.kind == PreviewKind.TILE)`.

Both land in `renderer-android` and don't touch the plugin or AAR resolution
path, so they're low-risk follow-ups.

## When to revisit this doc

Update the "Known failure signatures" section whenever:

- A new AndroidX AAR adds an R.id field that an older transitive doesn't have.
- The renderer's `libs.versions.toml` bumps a Compose / Activity / Core dep
  and CI integration (on WearTilesKotlin or ComposeStarter) catches a new
  failure mode.
- Consumer-side failures come in from external users — add the signature
  here so doctor can learn it.

The [CI integration matrix](../.github/workflows/integration.yml) is the
canonical "these consumers are tested to work" list. Anything that doesn't
pass there should get a doctor check, a docs entry, or both.
