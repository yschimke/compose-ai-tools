package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride

/**
 * Backend-neutral subset of a render spec that [PreviewOverrides] can mutate.
 *
 * Concrete hosts keep backend-local `RenderSpec` types, but recording and render override semantics
 * need to stay identical across hosts. This small DTO lets hosts adapt their local spec into a
 * shared merge function, then copy the merged fields back into their local type.
 */
data class PreviewOverrideBaseSpec(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val device: String?,
  val localeTag: String?,
  val fontScale: Float?,
  val uiMode: UiMode?,
  val orientation: Orientation?,
  val inspectionMode: Boolean?,
  val material3Theme: Material3ThemeOverrides? = null,
  val wallpaper: WallpaperOverride? = null,
  val ambient: AmbientOverride? = null,
  val focus: FocusOverride? = null,
)

data class MergedPreviewOverrides(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val device: String?,
  val localeTag: String?,
  val fontScale: Float?,
  val uiMode: UiMode?,
  val orientation: Orientation?,
  val inspectionMode: Boolean?,
  val material3Theme: Material3ThemeOverrides?,
  val wallpaper: WallpaperOverride?,
  val ambient: AmbientOverride?,
  val focus: FocusOverride?,
) {
  /**
   * Project the merged overrides down to a [PreviewOverrides] bag that only carries fields
   * extensions consume. Returns `null` when no extension-driven override is set so the renderer can
   * skip the data-extension pipeline entirely.
   *
   * **`localeTag` exception.** Pseudolocale handling (`en-XA` / `ar-XB`) is in two halves: the
   * renderer rewrites the qualifier directly, but the around-composable wrap that pseudolocalises
   * `Resources.getText` (Android) / flips `LocalLayoutDirection` (Desktop) is driven by
   * `PseudolocalePreviewOverrideExtension`, which inspects `localeTag`. So when the caller set
   * *only* `localeTag = "en-XA"` we still need to flow the bag through the planner pipeline;
   * without this, locale-only overrides ran the qualifier rewrite but never installed the
   * around-composable, leaving strings un-pseudolocalised.
   */
  fun toExtensionOverrides(): PreviewOverrides? {
    val isPseudolocale = isPseudolocaleTag(localeTag)
    if (
      material3Theme == null &&
        wallpaper == null &&
        ambient == null &&
        focus == null &&
        !isPseudolocale
    ) {
      return null
    }
    return PreviewOverrides(
      material3Theme = material3Theme,
      wallpaper = wallpaper,
      ambient = ambient,
      focus = focus,
      localeTag = if (isPseudolocale) localeTag else null,
    )
  }
}

/**
 * Hard-coded duplicate of `Pseudolocale.fromTag(...) != null`. Inlined here so `:daemon:core` (the
 * protocol module) doesn't take a dependency on `:data-pseudolocale-core` just to gate the bag
 * projection in [MergedPreviewOverrides.toExtensionOverrides]. If new pseudolocale tags ever land,
 * keep this list in sync with the `Pseudolocale` enum's `tag` values.
 */
private fun isPseudolocaleTag(tag: String?): Boolean {
  if (tag.isNullOrBlank()) return false
  val normalized = tag.replace('_', '-').lowercase()
  return normalized == "en-xa" || normalized == "ar-xb"
}

/**
 * Merge per-call [PreviewOverrides] over a discovery-time spec.
 *
 * Explicit `widthPx` / `heightPx` / `density` overrides win over `device`-resolved values. Device
 * resolution matches `renderNow.overrides.device`: resolve the supplied device id/spec, derive
 * pixels from its dp geometry at the effective density, then let explicit pixel dimensions replace
 * either axis.
 */
fun mergePreviewOverrides(
  base: PreviewOverrideBaseSpec,
  overrides: PreviewOverrides?,
): MergedPreviewOverrides {
  if (overrides == null) {
    return MergedPreviewOverrides(
      widthPx = base.widthPx,
      heightPx = base.heightPx,
      density = base.density,
      device = base.device,
      localeTag = base.localeTag,
      fontScale = base.fontScale,
      uiMode = base.uiMode,
      orientation = base.orientation,
      inspectionMode = base.inspectionMode,
      material3Theme = base.material3Theme,
      wallpaper = base.wallpaper,
      ambient = base.ambient,
      focus = base.focus,
    )
  }
  val deviceOverride = overrides.device?.takeIf { it.isNotBlank() }
  val deviceSpec = deviceOverride?.let { DeviceDimensions.resolve(it) }
  val effectiveDensity = overrides.density ?: deviceSpec?.density ?: base.density
  val widthPx =
    overrides.widthPx ?: deviceSpec?.let { (it.widthDp * effectiveDensity).toInt() } ?: base.widthPx
  val heightPx =
    overrides.heightPx
      ?: deviceSpec?.let { (it.heightDp * effectiveDensity).toInt() }
      ?: base.heightPx
  return MergedPreviewOverrides(
    widthPx = widthPx,
    heightPx = heightPx,
    density = effectiveDensity,
    device = deviceOverride ?: base.device,
    localeTag = overrides.localeTag?.takeIf { it.isNotBlank() } ?: base.localeTag,
    fontScale = overrides.fontScale ?: base.fontScale,
    uiMode = overrides.uiMode ?: base.uiMode,
    orientation = overrides.orientation ?: base.orientation,
    inspectionMode = overrides.inspectionMode ?: base.inspectionMode,
    material3Theme = overrides.material3Theme ?: base.material3Theme,
    wallpaper = overrides.wallpaper ?: base.wallpaper,
    ambient = overrides.ambient ?: base.ambient,
    focus = overrides.focus ?: base.focus,
  )
}
