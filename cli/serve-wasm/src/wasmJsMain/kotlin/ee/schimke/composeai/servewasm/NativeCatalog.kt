package ee.schimke.composeai.servewasm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.designcatalogm3.shared.CatalogApp
import com.example.designcatalogm3.shared.LocalWasmCatalogKnobs
import com.example.designcatalogm3.shared.catalogComponentIds

/** A catalog preview that the packaged Wasm frontend can compose in its own runtime. */
data class NativeCatalogTarget(
  val componentId: String,
  val dark: Boolean,
  val fontScale: Float,
  val rtl: Boolean,
  val knobSeeds: Map<String, String>,
)

/**
 * Resolve the first built-in native catalog. Other catalogs deliberately return null and keep using
 * the server's snapshot/live lanes, making this an additive registry rather than a special mode for
 * the whole application.
 */
internal fun nativeCatalogTarget(
  system: String?,
  previewId: String,
  knobSeeds: Map<String, String> = emptyMap(),
): NativeCatalogTarget? {
  // An explicit session is authoritative: never reinterpret another catalog's similarly named
  // route. The default server feed is different. It can mix application previews with injected
  // catalog previews while reporting only the application's module name, and the v2 API carries
  // no per-preview provenance. In that feed, exact membership in the catalog compiled into this
  // frontend is the provenance signal.
  if (system != null && system != COMPOSE_M3_SYSTEM) return null
  val axes = previewId.split("__")
  val base = axes.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
  val componentId =
    when {
      "pressed" in axes -> "$base-pressed"
      "keyboard-focus" in axes -> "$base-focused"
      "content-icon-label" in axes -> "$base-icon-label"
      else -> base
    }
  if (componentId !in catalogComponentIds) return null
  val fontScale =
    axes.firstNotNullOfOrNull { axis ->
      axis.removePrefix(FONT_SCALE_AXIS).takeIf { it != axis }?.toFloatOrNull()
    } ?: 1f
  return NativeCatalogTarget(
    componentId = componentId,
    dark = "dark" in axes,
    fontScale = fontScale.coerceIn(0.5f, 2f),
    rtl = "direction-rtl" in axes || axes.any(::isRtlLocaleAxis),
    knobSeeds = knobSeeds,
  )
}

@Composable
internal fun NativeCatalogPreview(
  target: NativeCatalogTarget,
  dark: Boolean = target.dark,
  fontScale: Float = target.fontScale,
  locale: String = "",
  transparent: Boolean = false,
  modifier: Modifier = Modifier,
) {
  CompositionLocalProvider(LocalWasmCatalogKnobs provides target.knobSeeds) {
    androidx.compose.foundation.layout.Box(modifier) {
      CatalogApp(
        id = target.componentId,
        dark = dark,
        fontScale = fontScale,
        rtl = locale.takeIf { it.isNotBlank() }?.let(::isRtlLocaleTag) ?: target.rtl,
        stageColor = if (transparent) null else Color(0xFFF8F8FA),
      )
    }
  }
}

private fun isRtlLocaleAxis(axis: String): Boolean =
  axis.startsWith("locale-") && isRtlLocaleTag(axis.removePrefix("locale-"))

private fun isRtlLocaleTag(tag: String): Boolean =
  tag.substringBefore('-').lowercase() in setOf("ar", "fa", "he", "iw", "ur")

private const val COMPOSE_M3_SYSTEM = "compose-m3"
private const val FONT_SCALE_AXIS = "fontscale-"
