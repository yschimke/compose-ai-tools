# Findings: `roborazzi-compose-preview-scanner-support`

Exploration response to [`docs/ROBORAZZI_SCANNER_SUPPORT.md`](../ROBORAZZI_SCANNER_SUPPORT.md).

## Summary recommendation

**Option (A) — annotations-only**, with a documented re-evaluation trigger. Adopt
`io.github.takahirom.roborazzi:roborazzi-annotations` as a `compileOnly`
dependency, teach [`DiscoverPreviewsTask`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt)
to read `@RoboComposePreviewOptions`, and map each `ManualClockOptions` entry
into our existing `RenderPreviewParams` / shard pipeline. Keep our discovery,
sharding, naming, tile path and device resolver as-is. Full or hybrid adoption
of `roborazzi-compose-preview-scanner-support` would be a regression on at least
three load-bearing behaviours (tiles, `@PreviewWrapper`, sharding model) and
would force every public surface — annotation, scanner, capture flow, runner —
to ride on `@ExperimentalRoborazziApi`.

## Answers to the nine questions

### 1. Meta-annotation coverage

**Partial.** ComposablePreviewScanner explicitly supports
`@PreviewScreenSizes`, `@PreviewFontScales`, `@PreviewLightDark`,
`@PreviewDynamicColors` and "custom multi-previews"
([upstream README](https://github.com/sergio-sastre/ComposablePreviewScanner)).
The Wear-specific `@WearPreviewDevices` / `@WearPreviewFontScales` /
`@WearPreviewLargeRound` family are not in the documented support list — the
maintainer's own docs do not confirm them. Our
[`DiscoverPreviewsTask.resolveMultiPreview`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt)
handles them generically by recursing on any annotation that itself carries
`@Preview`s, with cycle detection. The Wear annotations follow the standard
multi-preview shape, so they probably "just work" via the scanner's generic
path, but we couldn't verify this from docs alone — see open questions.

### 2. Tile previews

**Not supported.** ComposablePreviewScanner's README states tile preview
support is "under evaluation" — see the
[README](https://github.com/sergio-sastre/ComposablePreviewScanner). Our
discovery special-cases `androidx.wear.tiles.tooling.preview.Preview` (the
`TILE_PREVIEW_FQN` constant in
[`DiscoverPreviewsTask`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt#L73))
and the renderer dispatches via
[`TilePreviewComposable`](../../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/TilePreviewRenderer.kt)
to reflectively invoke the non-composable preview function. Adopting the
scanner today means losing tile coverage or running a parallel pipeline.

### 3. Device-spec parsing

**Equivalent or better, but with a different code path.**
ComposablePreviewScanner ships `DevicePreviewInfoParser.parse(device)` covering
"ALL possible combinations of device strings up to Android Studio Narwhal"
([README](https://github.com/sergio-sastre/ComposablePreviewScanner)),
including the `spec:width=…` form our
[`DeviceDimensionsTest`](../../gradle-plugin/src/test/kotlin/ee/schimke/composeai/plugin/DeviceDimensionsTest.kt)
exercises. The scanner-support code in
[`RoborazziPreviewScannerSupport.kt`](https://github.com/takahirom/roborazzi/blob/main/roborazzi-compose-preview-scanner-support/src/main/java/com/github/takahirom/roborazzi/RoborazziPreviewScannerSupport.kt)
delegates qualifier emission to `RobolectricDeviceQualifierBuilder.build(previewDevice)`
and reads `previewInfo.widthDp` / `previewInfo.heightDp` straight off
`AndroidPreviewInfo`. Our
[`DeviceDimensions.resolve`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DeviceDimensions.kt)
hard-codes a much smaller table. **Fidelity gap is in our favour to close** —
scanner is more comprehensive; our table only covers eight `id:*` devices and
collapses unknown `wear*` to one default.

### 4. Configuration cache

**Couldn't verify upstream.** The scanner runs at test-execution time (it's a
`testImplementation` artifact invoked from a JUnit runner), so it never crosses
the configuration-time boundary itself. The Roborazzi Gradle DSL feature
`generateComposePreviewRobolectricTests { generatedTestClassCount = 4 }`
documented in the
[Roborazzi README](https://github.com/takahirom/roborazzi#composablepreviewscanner-integration)
is a configuration-time codegen task — we did not audit it for CC compatibility.
Not blocking for option (A).

### 5. Variation semantics

**Cross-product.** From
[`RoborazziPreviewScannerSupport.kt`](https://github.com/takahirom/roborazzi/blob/main/roborazzi-compose-preview-scanner-support/src/main/java/com/github/takahirom/roborazzi/RoborazziPreviewScannerSupport.kt):
the scanner emits one `AndroidPreviewJUnit4TestParameter` per scanned preview
× per `RoboComposePreviewOptionVariation`. So with N preview entries (already
expanded by multi-`@Preview`) and M `ManualClockOptions`, the test count is
**N × M**. Empty `manualClockOptions` collapses to one variation per preview.

### 6. Output naming

**Different schema.** Scanner-support appends
`"_TIME_{advanceTimeMillis}ms"` via `RoboComposePreviewOptionVariation.nameWithPrefix()`,
yielding files like `com.example.MyKt.Foo_TIME_500ms.png`. Our naming is
`<class>.<function>{_<variantSuffix>}.png` driven by
[`buildVariantSuffix`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt#L269)
(prefers `name`, then `group`, then `device|fontScale|uiMode`). We can append
a parallel `_TIME_{ms}ms` suffix in `buildVariantSuffix` when the new
annotation is present without breaking existing `previews.json` consumers (CLI,
VS Code extension key off `id` / `renderOutput` strings, not a naming grammar).
Lower disruption than swapping our scheme for upstream's.

### 7. Sharding compatibility

**Incompatible runner model.** Our
[`GenerateRenderShardsTask`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/GenerateRenderShardsTask.kt)
emits Java `RobolectricRenderTest_ShardN` subclasses, each a
`@RunWith(ParameterizedRobolectricTestRunner.class)` with its own
`@Parameters` slice. Roborazzi's sharding equivalent is the codegen DSL
`generateComposePreviewRobolectricTests { generatedTestClassCount = N }`,
which writes its own test classes and uses a custom `ComposePreviewTester`
runner — not `ParameterizedRobolectricTestRunner`. Adopting it would mean
deleting our shard generator and remapping the sandbox-reuse story (one
sandbox per generated class today) onto whatever class shape Roborazzi emits.
Not a deal-breaker, but a real swap, not a drop-in.

### 8. Dependency footprint

From the scanner-support module's
[`build.gradle`](https://github.com/takahirom/roborazzi/blob/main/roborazzi-compose-preview-scanner-support/build.gradle):

- `api` → `roborazzi`, `roborazzi-compose`, `roborazzi-annotations`
- `compileOnly` → `androidx.compose.runtime`, `composable-preview-scanner`,
  `robolectric`, `androidx.compose.ui.test.junit4`, `kotlinx-coroutines-test`

The `compileOnly` posture means consumer projects supply their own Compose /
Robolectric / scanner versions — aligned with our
[`RENDERER_COMPATIBILITY.md`](../RENDERER_COMPATIBILITY.md) policy. The
runtime addition over what we already pull in (`roborazzi-compose`) is
`roborazzi-annotations` plus the consumer-supplied `composable-preview-scanner`
JAR. The annotation-only path adds **just** `roborazzi-annotations`, a tiny
zero-runtime artifact.

### 9. Upstream stability

**Experimental.** Every public symbol in
[`RoborazziPreviewScannerSupport.kt`](https://github.com/takahirom/roborazzi/blob/main/roborazzi-compose-preview-scanner-support/src/main/java/com/github/takahirom/roborazzi/RoborazziPreviewScannerSupport.kt)
is annotated `@ExperimentalRoborazziApi` — `captureRoboImage`,
`toRoborazziComposeOptions`, `previewDevice`, `composeTestRule`,
`manualAdvance`, `AndroidComposePreviewTester`, `RoboComposePreviewOptionVariation`,
and the `ComposePreviewTester` interface itself. Issue
[#447](https://github.com/takahirom/roborazzi/issues/447) (open, no resolution)
flags that the existing API isn't yet expressive enough to plumb full
`RoborazziOptions` per preview. The annotation surface in
`roborazzi-annotations` is much smaller and stable enough to mirror in our
discovery — the file's TODOs (`ignoreFrames` etc.) are forward-looking, not
breaking.

## Rationale for (A)

Concretely, full adoption (C) loses tile previews (Q2), forces a sharding
rewrite (Q7), drops `@PreviewWrapper` support — the scanner's docs make no
mention of compose 1.11+ wrapper providers (Q1) — and locks every renderer
codepath to `@ExperimentalRoborazziApi` (Q9). The hybrid (B) — keep our
discovery, use scanner-support's capture path — would still pull in the
experimental capture surface for marginal benefit, since our `renderDefault`
in
[`RobolectricRenderTest.kt`](../../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt)
already does the exact same thing (paused `mainClock`, qualifier-set,
`captureRoboImage`) and integrates with our a11y branch.

The narrow win — annotation interop — is achievable in option (A) at a cost
of: one extra dep coordinate, one switch in `extractPreviewParams`, and one
new field on `RenderPreviewParams` (or `manualClockOptionsMs: List<Long>`).
A consumer who annotates `@RoboComposePreviewOptions(manualClockOptions =
[@ManualClockOptions(500), @ManualClockOptions(1000)])` gets two variants out
of our discovery, named `Foo_TIME_500ms.png` / `Foo_TIME_1000ms.png`, both
sharded and rendered with the same `mainClock.advanceTimeBy` mechanism we
already use — the only behavioural change is making `CAPTURE_ADVANCE_MS` a
per-variant value instead of a constant.

## Things to revisit later

- **Tile previews land in ComposablePreviewScanner.** Q2 is the dealbreaker
  for full adoption. If sergio-sastre/ComposablePreviewScanner ships first-class
  tile discovery, re-run this evaluation — at that point the scanner would
  cover everything our `DiscoverPreviewsTask` does and (B) becomes more
  attractive.
- **`@RoboComposePreviewOptions` grows.** The annotation file's TODOs
  reference `ignoreFrames` and other fields. If the schema expands beyond
  `manualClockOptions`, decide whether to keep mirroring it in our discovery
  or switch to scanner-support to avoid drift.
- **Scanner-support exits experimental.** Re-evaluate (B) once
  `@ExperimentalRoborazziApi` comes off the public surface and issue
  [#447](https://github.com/takahirom/roborazzi/issues/447) is resolved.

## Open questions / things we couldn't verify

- **Wear multi-preview annotations.** ComposablePreviewScanner docs don't
  enumerate `@WearPreviewDevices` / `@WearPreviewFontScales` etc., though they
  follow the generic multi-preview shape. We didn't run a side-by-side scan
  on `:sample-wear` to confirm.
- **`@PreviewWrapper` (compose 1.11+).** Not mentioned in
  ComposablePreviewScanner docs at all. Our
  [`extractWrapperFqn`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt#L170)
  reads it via ClassGraph. Whether the scanner surfaces it in
  `AndroidPreviewInfo` is unknown without reading scanner source — and
  `roborazzi-compose-preview-scanner-support` itself doesn't reference it.
- **Configuration cache safety of `generateComposePreviewRobolectricTests`.**
  Q4 — we'd need to read that DSL's task implementation to confirm CC-clean
  under `problems=fail`. Not relevant for the recommended path.
- **Cross-product variant naming collisions.** If we add `_TIME_500ms` to a
  preview already named `_LargeRound`, the order becomes load-bearing for VS
  Code extension matching. Worth a unit test before shipping the annotation
  support.
- **`previewInfo.widthDp` fallback when `device = "id:pixel_6"` only.** We
  couldn't find an explicit fallback in scanner-support's source —
  presumably `AndroidComposablePreviewScanner` resolves it. Our
  `DeviceDimensions.resolve` does this explicitly; if we ever swap to the
  scanner we should verify pixel-equivalence on every device id we list.
