package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode

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
)

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
  )
}
