package ee.schimke.composeai.daemon

import java.util.Collections
import kotlinx.serialization.Serializable

/**
 * The `compose-figma-fonts.warnings.json` sidecar: which faces the render drew with that the export
 * could not reproduce, and what it substituted instead. Written only for a degraded export, so its
 * presence is the signal.
 */
@Serializable
data class FigmaSvgFontWarnings(
  /** Families the render drew with that the SVG never names. */
  val unnamedRenderedFamilies: List<String>,
  /** Families the SVG does name, for contrast when only part of a sheet degraded. */
  val namedFamilies: List<String>,
  /** The face the unnamed text was exported in — boxes, not a substitute typeface. */
  val tofuFamily: String,
)

/**
 * Per-preview record of the font families a render **actually drew with**, so the
 * `compose/figma-svg` export can tell "this text legitimately uses the platform default" apart from
 * "we lost the branded family somewhere".
 *
 * Those two look identical from inside the export — both arrive as a text node with no captured
 * family — but only one of them is a defect. A stock-Material preview genuinely has no explicit
 * `fontFamily` and correctly exports as Roboto; a branded preview that lost its family exports as
 * Roboto too, and that is how a whole sticker sheet shipped in the wrong typeface. The render is the
 * only place that knows which it was, so it publishes here and the export cross-checks.
 *
 * Populated by the Android render's font recorder (`FontResolverRecorder`) as resolutions happen,
 * rather than at post-capture: the recorder and the SVG export are independent post-capture
 * extensions with no ordering guarantee between them, so the data has to be in place before either
 * runs. Lives in this module because the export reads it and the recorder (in `:daemon:android`)
 * already depends on this one — the same direction `FontResolutionDiagnostics` is driven from two
 * render paths.
 */
object FigmaSvgRenderedFonts {

  private val families = Collections.synchronizedSet(LinkedHashSet<String>())

  /** Clear the buffer before a preview renders, so each export sees only its own faces. */
  fun begin() {
    families.clear()
  }

  /**
   * Record that the render resolved [family] — a display name (`"Orbitron"`), not a `toString()`
   * blob. Blank names are ignored; the platform default is deliberately *not* recorded, since
   * defaulting text to the default face is exactly the case that must stay quiet.
   */
  fun record(family: String?) {
    val name = family?.trim().orEmpty()
    if (name.isNotEmpty()) families.add(name)
  }

  /** The families this preview drew with. */
  fun snapshot(): Set<String> = synchronized(families) { families.toSet() }

  /**
   * The families the render drew with that [named] does not account for — the faces the export is
   * about to misrepresent. Compared case-insensitively because a family reaches the two sides by
   * different routes (a declared `GoogleFont` name vs a name read out of font bytes).
   */
  fun unnamedIn(named: Collection<String>): List<String> {
    val have = named.map { it.lowercase() }.toSet()
    return snapshot().filterNot { it.lowercase() in have }.sorted()
  }
}
