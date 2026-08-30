package ee.schimke.composeai.servewasm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.designcatalogm3.shared.CatalogApp
import com.example.designcatalogm3.shared.LocalWasmCatalogKnobs
import com.example.designcatalogm3.shared.catalogComponentIds
import kotlinx.coroutines.withTimeoutOrNull

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
  // A variant whose picture is produced by the render harness rather than by the composable is not
  // reproducible here, and must fall back to the server's snapshot (#4821).
  //
  // `button-filled-pressed` / `-focused` compose a *plain* `Button`: since #3672 the state comes
  // from `@FocusedPreview`, which walks real focus under a synthetic keyboard input mode and
  // dispatches a real pointer press. This frontend has no such harness, so composing those ids
  // draws an ordinary unpressed, unfocused button — and labels it "Pressed" / "Focused". That is
  // worse than substituting nothing: the picture is wrong AND it claims to be the state it is not.
  // `card-slots__…__slot-mode` is the same shape one level down — it fell through to `card-slots`
  // without providing `LocalSlotMode`, so the labelled slot placeholders the variant exists to show
  // simply were not drawn.
  //
  // Returning null is the fail-safe: the lane is an additive registry, and a null here is already
  // the "keep using the server's snapshot/live lanes" answer every other catalog gets.
  if (axes.any { it in HARNESS_DRIVEN_AXES }) return null
  val componentId =
    when {
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
  val fonts = rememberCatalogFonts()
  CompositionLocalProvider(LocalWasmCatalogKnobs provides target.knobSeeds) {
    androidx.compose.foundation.layout.Box(modifier) {
      // Held until the faces resolve, so the first frame this lane reveals is already shaped by the
      // right fonts rather than reflowing once they land. `Ready(null)` — a failed or timed-out
      // fetch — composes with the CMP bundled font, which is what this lane always did.
      val loaded = fonts as? CatalogFonts.Ready ?: return@Box
      CatalogApp(
        id = target.componentId,
        dark = dark,
        fontScale = fontScale,
        rtl = locale.takeIf { it.isNotBlank() }?.let(::isRtlLocaleTag) ?: target.rtl,
        stageColor = if (transparent) null else Color(0xFFF8F8FA),
        fontFamily = loaded.family,
        genericFamilies = loaded.generics,
        namedFamilies = loaded.named,
      )
    }
  }
}

/**
 * The catalog's typefaces, fetched once per page and shared by every native preview on it.
 *
 * Process-wide rather than per-composable: a catalog grid mounts one of these per card, and each
 * re-fetching the same eight families would be dozens of redundant requests for bytes the HTTP
 * cache already holds — and would stagger the reveal card by card.
 */
@Composable
private fun rememberCatalogFonts(): CatalogFonts {
  var state by remember { mutableStateOf(loadedCatalogFonts ?: CatalogFonts.Loading) }
  LaunchedEffect(Unit) {
    if (loadedCatalogFonts == null) {
      loadedCatalogFonts =
        withTimeoutOrNull(FONT_LOAD_TIMEOUT_MS) {
          loadCatalogFonts(fetchText = ::fetchText, fetchBase64 = ::fetchBase64)
        } ?: CatalogFonts.Ready()
    }
    state = loadedCatalogFonts!!
  }
  return state
}

/** Resolved faces, kept across recompositions and across every preview on the page. */
private var loadedCatalogFonts: CatalogFonts.Ready? = null

private fun isRtlLocaleAxis(axis: String): Boolean =
  axis.startsWith("locale-") && isRtlLocaleTag(axis.removePrefix("locale-"))

private fun isRtlLocaleTag(tag: String): Boolean =
  tag.substringBefore('-').lowercase() in setOf("ar", "fa", "he", "iw", "ur")

/**
 * Variant axes whose render depends on something outside the composable.
 *
 * Kept as a set rather than folded into the `when` so the reason is stated once and a new
 * harness-driven variant is a one-line addition here rather than a divergence discovered in a pixel
 * diff.
 */
private val HARNESS_DRIVEN_AXES = setOf("pressed", "keyboard-focus", "slot-mode")

private const val COMPOSE_M3_SYSTEM = "compose-m3"
private const val FONT_SCALE_AXIS = "fontscale-"
