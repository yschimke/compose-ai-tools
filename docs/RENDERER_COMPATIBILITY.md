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
  have, and we've seen a consumer hit the resulting runtime error. Add a test
  in `CompatRulesTest.kt` with both the triggering and non-triggering paths.
- **The three mitigation mechanisms** in the renderer/plugin that must move
  together — remove any one and the compat matrix re-opens:
  1. `compileOnly` for Compose / Activity / UI-test libs in
     [`renderer-android/build.gradle.kts`](../renderer-android/build.gradle.kts).
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

## Tile-rendering gaps (follow-ups)

Unrelated to the compat story but worth tracking here so they don't go
missing:

- **Tile previews should default to a black background.** Today
  `TilePreviewRenderer` hosts the inflated tile in a `FrameLayout` with no
  background; `showBackground`/`backgroundColor` from `@Preview` are both
  false on `@wear.tiles.tooling.preview.Preview`. Fix in
  `TilePreviewRenderer.TilePreviewComposable`: paint `Color.Black` on the
  hosting Box when `params.kind == PreviewKind.TILE`, or when `device`
  starts with `id:wearos_*`.
- **Round crop for tile previews.** `RoundScreenOption` is added when
  `isRoundDevice(params.device) && params.showSystemUi`. Tile previews
  always have `showSystemUi = false`, so the qualifier never fires. Change
  to `isRoundDevice(device) && (showSystemUi || kind == PreviewKind.TILE)`.

Both land entirely in `renderer-android` and don't touch the plugin / AAR
resolution path, so they're low-risk follow-ups.
