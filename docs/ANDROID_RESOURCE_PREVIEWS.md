# Android resource previews

Plan for treating Android XML resources as first-class previewables alongside `@Preview` composables. Initial scope: **vector drawables**, **animated vector drawables**, **adaptive icons**. Drives previews from AndroidManifest.xml's icon attributes by *linking* into the same renders, not by re-rendering.

## Goals

1. Each XML drawable / mipmap discovered in a module produces one rendered PNG (or GIF) per relevant variant axis, written next to the existing composable renders under `build/compose-previews/renders/`.
2. AndroidManifest.xml references (`android:icon`, `android:roundIcon`, `android:logo`, `android:banner` on `<application>`, `<activity>`, `<activity-alias>`, `<service>`, `<receiver>`, `<provider>`) contribute *references* into a sidecar index — not new renders. Two callers (manifest CodeLens + resource grid) hit the same PNG on disk.
3. Sample exercise lives in `samples/android/` so the functional test asserts on a known PNG/GIF set.

## Scope (initial)

| Resource | Discovery tag | Renderer | Variant axes → captures |
|---|---|---|---|
| Vector drawable | `<vector>` | `ContextCompat.getDrawable(ctx, R.drawable.foo)` → bitmap-backed canvas | resource qualifiers (see below) |
| Animated vector | `<animated-vector>` | `start()`, drive `mainClock.advanceTimeBy` per frame, GIF-encode via `ScrollGifEncoder` | resource qualifiers (one GIF per qualifier set); duration auto-detected from animator XML |
| Adaptive icon | `<adaptive-icon>` (mipmap-anydpi-v26) | composite foreground + background into 108dp canvas, apply shape clip path | resource qualifiers × shape (`CIRCLE`, `ROUNDED_SQUARE`, `SQUARE`) + `LEGACY` |

Out of scope for this iteration: `<animation-list>`, `<shape>`, `<selector>`, `<layer-list>`, `<level-list>`, `<ripple>`, `<bitmap>`. The discovery and JSON-schema work below leaves room for them — adding a new `ResourceType` value plus a renderer branch is the only thing needed to extend the catalogue later.

## Discovery

New `DiscoverAndroidResourcesTask`, sibling of `DiscoverPreviewsTask`:

- Inputs (lazy providers, configuration-cache safe):
  - `Variant.sources.res.all` — every `res/` source root for the variant. Walked for files under `drawable*/` and `mipmap*/`.
  - `Variant.artifacts.get(MERGED_MANIFEST)` — the merged AndroidManifest.xml.
- For each XML resource file: parse the root tag once. Tag determines `ResourceType`; unknown tags are skipped (not errors — keeps the catalogue extensible).
- For the merged manifest: walk every supported component tag, extract drawable/mipmap references from the supported attribute set, record one `ManifestReference` per (component, attribute) pair. Cross-attribute deduplication isn't applied — `<application>` referencing `@mipmap/ic_launcher` for both `android:icon` and (via theme) the round variant produces two rows so the manifest CodeLens can label each.
- Output: `build/compose-previews/resources.json`, sibling of `previews.json`.

## Data model

New types in `gradle-plugin/.../PreviewData.kt`, mirrored TypeScript-side in `vscode-extension/src/types.ts`:

```kotlin
@Serializable enum class ResourceType { VECTOR, ANIMATED_VECTOR, ADAPTIVE_ICON }
@Serializable enum class AdaptiveShape { CIRCLE, ROUNDED_SQUARE, SQUARE, LEGACY }

@Serializable
data class ResourceVariant(
  /**
   * Resource qualifier suffix as it appears in the AAPT directory name, sans
   * the leading dash. e.g. `"xhdpi"`, `"night-xhdpi"`, `"ldrtl-xhdpi-v26"`,
   * `"de-rDE-xxhdpi"`. `null` means the default (no-qualifier) configuration.
   *
   * This is the runtime configuration the capture was rendered under, not
   * the qualifier of any single source file: when the consumer has both
   * `drawable/foo.xml` and `drawable-night/foo.xml`, Android's resource
   * resolver picks whichever matches the active qualifiers, and the capture
   * carries those active qualifiers. Source-file provenance is recorded
   * separately in `ResourcePreview.sourceFiles`.
   */
  val qualifiers: String? = null,
  /**
   * Adaptive-icon shape mask applied at render time. Not an Android
   * qualifier — adaptive icons are masked at composition time, not at
   * resource-resolution time. `null` for non-adaptive resources.
   */
  val shape: AdaptiveShape? = null,
)

@Serializable
data class ResourceCapture(
  val variant: ResourceVariant? = null,
  val renderOutput: String = "",   // module-relative PNG/GIF path
  val cost: Float = RESOURCE_STATIC_COST,
)

@Serializable
data class ResourcePreview(
  val id: String,                 // "drawable/ic_compose_logo" | "mipmap/ic_launcher"
  val type: ResourceType,
  /**
   * Every source file participating in this resource. The default-qualifier
   * file (e.g. `drawable/ic_foo.xml`) is keyed by `null`; qualified
   * variants (`drawable-night/ic_foo.xml`, `drawable-xhdpi/ic_foo.xml`) are
   * keyed by their qualifier suffix (`"night"`, `"xhdpi"`).
   */
  val sourceFiles: Map<String?, String>,
  val captures: List<ResourceCapture>,
)

@Serializable
data class ManifestReference(
  val source: String,             // "src/main/AndroidManifest.xml"
  val componentKind: String,      // "application" | "activity" | "activity-alias" | ...
  val componentName: String?,     // FQN class for activity/service/etc, null for application
  val attributeName: String,      // "android:icon" | "android:roundIcon" | ...
  val resourceType: String,       // "drawable" | "mipmap"
  val resourceName: String,       // "ic_launcher"
)

@Serializable
data class ResourceManifest(
  val module: String,
  val variant: String,
  val resources: List<ResourcePreview>,
  val manifestReferences: List<ManifestReference>,
)
```

`PreviewKind` gains a `RESOURCE` value so render-task fan-out is type-driven, but the resource manifest stays in its own JSON file: composables key on FQN (`functionName` + `className`), resources key on `(type, name)`, and the consumers (CodeLens, resource grid, CLI) want the lookups separately rather than fishing through a unified list.

## Resource qualifiers and capture fan-out

Android resource directories encode configuration via a dash-separated qualifier suffix (`drawable-night-xhdpi-v26`, `mipmap-anydpi-v26`, `drawable-ldrtl`, `values-de-rDE`, …). At runtime, AAPT picks the best-matching directory for the active `Configuration`. The renderer leverages this rather than re-implementing it: it sets `RuntimeEnvironment.setQualifiers(…)` on Robolectric for each capture and lets the platform resolver pick the right XML.

Discovery's job is to compute the **qualifier dimensions** that matter for a given resource — the union of qualifier sets present across its source files — and emit one capture per element of the cross-product with the DSL-configured implicit dimensions. Two flavours of qualifier:

- **Explicit** — the consumer has a qualified directory for the resource. `drawable-night/ic_foo.xml` adds `night` to the dimension set; `drawable-ldrtl/ic_foo.xml` adds `ldrtl`. The capture for a qualified variant uses the consumer's source file directly.
- **Implicit** — no qualified directory exists, but the qualifier still affects rendering. Density is the canonical case: a single `drawable/ic_foo.xml` (vector) renders meaningfully at every density bucket; the DSL controls which densities to fan out by default.

The full capture set for a resource is:

```
captures = ((explicit qualifier sets) ∪ {default}) × (implicit density list) × (adaptive shapes if applicable)
```

Adaptive shape stays a separate axis because it's applied at *render* time (canvas clip path), not at *resource-resolution* time.

Qualifier dimensions the renderer recognises (initial set):

| Qualifier | Dimension | Implicit fan-out? |
|---|---|---|
| `mdpi` / `hdpi` / `xhdpi` / `xxhdpi` / `xxxhdpi` | density | yes (DSL `densities`) |
| `night` / `notnight` | UI mode night | only when an explicit `drawable-night/` exists |
| `ldrtl` / `ldltr` | layout direction | only when explicit |
| `port` / `land` | orientation | only when explicit |
| `round` / `notround` | round screen | only when explicit (Wear) |
| `<lang>` / `<lang>-r<REGION>` | locale | only when explicit |
| `car` / `desk` / `television` / `watch` / `vrheadset` | UI mode type | only when explicit |
| `sw<N>dp` / `w<N>dp` / `h<N>dp` | screen size | only when explicit |
| `v<N>` | platform version | filters file resolution; not a separate capture axis |

`v<N>` is parsed but not expanded — it gates which file Android picks at resolution time, not how the picked file renders. The `mipmap-anydpi-v26` directory (where adaptive icons live) is one such case: the `v26` qualifier just tells AAPT "only use this on API 26+"; we render under whatever density × shape we want, and Android picks the `anydpi-v26` source.

## Cost catalogue additions

Slotting into the existing catalogue in `PreviewData.kt`:

```kotlin
const val RESOURCE_STATIC_COST: Float = 1.0f       // per vector capture
const val RESOURCE_ADAPTIVE_COST: Float = 4.0f     // per adaptive shape (× 4 = ~16 / icon)
const val RESOURCE_ANIMATED_COST: Float = 35.0f    // single AVD GIF; ≈ SCROLL_GIF_COST
```

`RESOURCE_ADAPTIVE_COST` and `RESOURCE_ANIMATED_COST` sit above `HEAVY_COST_THRESHOLD = 5`, so adaptive icons and animated vectors fall into the heavy-stale bucket on every-save renders — same behaviour as `@AnimatedPreview` today.

## Filenames

Following the whitelist + normalization rules in [RENDER_FILENAMES.md](RENDER_FILENAMES.md), resource captures land under `build/compose-previews/renders/resources/<type>/<name>[_<axis>...].{png,gif}`:

- `renders/resources/drawable/ic_compose_logo_xhdpi.png` — default-qualifier × xhdpi
- `renders/resources/drawable/ic_compose_logo_night-xhdpi.png` — `drawable-night/` source × xhdpi
- `renders/resources/drawable/ic_compose_logo_ldrtl-xhdpi.png` — `drawable-ldrtl/` source × xhdpi
- `renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png`
- `renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_rounded_square.png`
- `renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_square.png`
- `renders/resources/mipmap/ic_launcher_xhdpi_LEGACY.png`
- `renders/resources/drawable/avd_pulse_xhdpi.gif`

Filename suffix derivation: qualifier string first (verbatim, with `-` preserved per the whitelist), then `_SHAPE_<shape>` / `_LEGACY` appended for adaptive icons. Default qualifier (no qualified directory + density off) yields a bare `<name>.png`.

`<type>/` prefix scopes resource renders so the existing `cleanStaleRenders` predicate in `ComposePreviewTasks.kt` can keep its current "anything not in `previews.json` is stale" logic for composables, with a parallel pass for `resources.json`.

## Rendering

Mirror of `RobolectricRenderTest`, in a new `ResourcePreviewRenderer` class under `renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/`:

- Same SDK pin (`@Config(sdk = [35])`), same `pixelCopyRenderMode=hardware`, same Robolectric harness. Robolectric's classpath already has the consumer's merged resource APK (`apk-for-local-test.ap_`), so resource lookup via the consumer's R class works directly via reflection at render time.
- For each `ResourceCapture`, the renderer first sets `RuntimeEnvironment.setQualifiers(variant.qualifiers)` so AAPT picks the correct source file, then per-type:
  - **Vector**: resolve drawable, set bounds from intrinsic size × density factor (parsed from the qualifier string), draw to a bitmap canvas, write PNG.
  - **Animated vector**: resolve drawable, `start()`, walk frames at the encoder's frame interval (default ~33ms), GIF-encode via the existing `ScrollGifEncoder`. Duration auto-detected from the animator XML's `duration`; capped at a sane max (TBD — probably 3s) to bound runaway animations. **Known limitation:** under Robolectric's `looperMode=PAUSED` (which we pin for Compose's render path), `AnimatedVectorDrawable`'s `ObjectAnimator` doesn't receive the `Choreographer` frame callbacks that drive its tick — `setVisible(true, true) + start() + idleFor(50ms)` and `LooperMode.LEGACY` both leave every frame at t=0. Until we land an animator-stepping shim, AVD captures emit a single-frame GIF showing the icon's rest state. The format stays `.gif` so a future commit can write multi-frame output into the same path without touching discovery / wiring.
  - **Adaptive icon**: render foreground + background each into a 108dp×108dp canvas, composite, then apply each `AdaptiveShape`'s clip path. `LEGACY` falls back to the `android:icon` attribute on the `<adaptive-icon>` parent (when present) or to the foreground rendered against a transparent background. Mask paths come from the same set Studio uses (circle, rounded-rect r=8dp, square).
- `ResourcePreviewRenderer` runs as a separate Robolectric test class. The render-task wiring decides which renderer(s) to launch based on which manifests exist post-discovery.
- The renderer never reads `manifestReferences` — references are pure metadata.

## Link-don't-re-render contract

`ManifestReference` rows resolve to a `ResourcePreview` by `(resourceType, resourceName)`. Consumers build an in-memory index `Map<Pair<String, String>, ResourcePreview>` once per module load. Two surfaces:

1. **Manifest CodeLens** — opening AndroidManifest.xml, the extension provides one CodeLens per line whose icon-bearing attribute resolves into the index. Click → opens the resource preview, which is the same PNG path the resource grid would have shown. No new disk artifact, no rerender.
2. **Resource grid** — lists `resources` directly. Resources that have one or more `ManifestReference` rows pointing at them get a "Used in: AndroidManifest.xml ↦ application@android:icon" footer.

When a resource referenced from the manifest does *not* appear in `resources` (e.g. it's a raster mipmap with no XML file we render), the CodeLens still resolves to the source-file path so the lens is at least a navigation aid. Rendering raster mipmaps (`mipmap-mdpi/ic_launcher.png` etc.) is left for a follow-up.

## VS Code surfacing

- `vscode-extension/src/types.ts` mirrors of `ResourceManifest`, `ResourcePreview`, `ResourceCapture`, `ResourceVariant`, `AdaptiveShape`, `ManifestReference`.
- `ResourceManifestService` watches `resources.json` alongside the existing `previews.json` watcher.
- `AndroidManifestCodeLensProvider` registered against `AndroidManifest.xml`. Returns one CodeLens per `ManifestReference` whose `source` matches the open file. Lens command opens the resource preview tab focused on the referenced resource.
- Webview: extend the existing Compose preview view with a tabbed "Resources" section (decision deferred to implementation — depends on how busy the existing UI ends up).

## Plugin DSL

```kotlin
composePreview {
  resourcePreviews {
    enabled = true                          // off by default
    densities = listOf("mdpi", "xhdpi")     // implicit density fan-out
    shapes = listOf(                        // restrict adaptive-icon shapes
      AdaptiveShape.CIRCLE,
      AdaptiveShape.ROUNDED_SQUARE,
      AdaptiveShape.SQUARE,
      AdaptiveShape.LEGACY,
    )
    // Explicit qualifier dimensions are picked up automatically from the
    // consumer's res/<base>-<quals>/ directories — no DSL needed. Only
    // implicit dimensions (density fan-out, adaptive-icon shapes) are
    // configured here.
  }
}
```

Defaults match the precedent set by `accessibilityChecks` — opt-in, no behavioural change for existing consumers, byte-identical render output until flipped.

## Phasing

| Commit | Scope |
|---|---|
| 1 | Plan + samples + AndroidManifest.xml updates (this commit) |
| 2 | `DiscoverAndroidResourcesTask` + data-model types + `resources.json` write |
| 3 | Static `ResourcePreviewRenderer` for vector + adaptive icon (shapes + legacy) |
| 4 | Animated-vector GIF rendering |
| 5 | VS Code TS types + `AndroidManifestCodeLensProvider` + resource webview tab |
| 6 | Functional test asserting expected PNG/GIF set on `samples/android` |

## Sample exercise

Files added under `samples/android/src/main/res/`:

- `drawable/ic_compose_logo.xml` — plain vector (default qualifier).
- `drawable-night/ic_compose_logo.xml` — recoloured night variant; exercises the explicit-qualifier capture path.
- `drawable/avd_pulse.xml` — animated-vector with embedded `<aapt:attr>` `<objectAnimator>`.
- `drawable/ic_launcher_foreground.xml` — vector (foreground for the adaptive icon).
- `drawable/ic_launcher_background.xml` — vector (background for the adaptive icon).
- `drawable/ic_launcher_legacy.xml` — vector (pre-O fallback baked into the adaptive icon's legacy slot).
- `drawable/ic_settings.xml` — vector used as an `android:icon` override on `MainActivity`.
- `mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon referencing fg+bg.
- `mipmap-anydpi-v26/ic_launcher_round.xml` — round adaptive variant.

`samples/android/src/main/AndroidManifest.xml` gains:

- `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` on `<application>`.
- `android:icon="@drawable/ic_settings"` on the existing `MainActivity` — exercises the activity-level override path.

Three references from the manifest map to two unique resources rendered (`mipmap/ic_launcher{,_round}` and `drawable/ic_settings`), proving the link-don't-re-render contract.
