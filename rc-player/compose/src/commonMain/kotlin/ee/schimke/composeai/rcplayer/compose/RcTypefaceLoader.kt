package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation

/**
 * How the player asks a host for a typeface.
 *
 * **Why an interface rather than the `Map<String, RcFontFaces>` this replaces.** A map makes every
 * host reimplement the same lookup rules, and the rules are *protocol* facts rather than host
 * preferences — what a document means when it names no family, what a `google:`-prefixed name is,
 * which families fall back to Compose's built-ins. A second host either reimplements all of that or
 * renders body text in the wrong face, which has already happened once (see the comment on the Wasm
 * host's manifest loader). Those rules now live in [rcResolveTypeface], stated once, and a loader
 * answers only the question it is actually the authority on: *do I have this family, and at these
 * axes?*
 *
 * **Synchronous by design.** [typeface] is called during composition and draw, so it cannot
 * suspend. Async work belongs in *construction* — which is already how every host does it: fetch
 * and decode the faces, then hand the player a loader that only looks things up. Keep that split.
 */
public interface RcTypefaceLoader {
  /**
   * The families this loader can resolve, lowercased.
   *
   * This gates renderability *before* a document is shown: `composeSupportReport` checks a
   * document's named families against this set, so a document naming a family the host cannot
   * supply fails loudly at load rather than rendering silently in a fallback face. A loader that
   * cannot enumerate its families up front would break that check — see #4061, where that is a
   * design question rather than an implementation detail.
   */
  public val families: Set<String>

  /**
   * The family registered under [family] instanced at [settings], or null if there is none.
   *
   * [family] arrives already normalised by [rcResolveTypeface] — lowercased, `google:` stripped —
   * so an implementation matches it against [families] directly rather than re-deriving the rules.
   *
   * Called during composition and draw: it must not block, and it should cache. [RcFontFaces] does
   * the caching for the bundled implementation; a document draws the same family at the same axes
   * every frame, and re-parsing the file per frame is visible on a text-heavy watch face.
   */
  public fun typeface(family: String, settings: FontVariation.Settings? = null): FontFamily?

  public companion object {
    /** Resolves nothing; documents render in Compose's built-in faces. */
    public val Empty: RcTypefaceLoader = RcBundledTypefaceLoader(emptyMap())

    /**
     * The platform default.
     *
     * [Empty] for now, deliberately. What a real default should be is a genuine design question —
     * desktop and iOS could expose system fonts, a browser has no system-font access at all —
     * and #4061 settles it. A default that starts resolving fonts it previously did not is a
     * behaviour change a consumer cannot see coming from a version bump, so it is worth deciding
     * before the first release rather than after (#4066).
     */
    public val Default: RcTypefaceLoader = Empty
  }
}

/**
 * An [RcTypefaceLoader] over faces the host already holds, keyed by lowercased family name.
 *
 * This is what every host builds today: fetch or embed the font files, group them by family, hand
 * the result over. Keys are lowercased on construction so a host that reads a manifest verbatim
 * cannot miss a family by case alone.
 */
public class RcBundledTypefaceLoader(faces: Map<String, RcFontFaces>) : RcTypefaceLoader {
  private val faces: Map<String, RcFontFaces> = faces.mapKeys { (name, _) -> name.lowercase() }

  override val families: Set<String> = this.faces.keys

  override fun typeface(family: String, settings: FontVariation.Settings?): FontFamily? =
    faces[family]?.family(settings)
}

/**
 * The family a document's recorded name resolves to — the whole rule set, in one place.
 *
 * These are wire facts, not host preferences, which is why they are here rather than repeated per
 * host:
 * * a document naming **no** family asks for the literal key `"default"`;
 * * names arrive **lowercased**, and a `google:` prefix is a source marker rather than part of the
 *   name, so it is stripped;
 * * the **generic** families (`sans-serif`, `serif`, `monospace`) try the host first and then fall
 *   back to Compose's built-ins, so a host may override a generic but does not have to supply one.
 *
 * [embedded] is consulted before the host for a non-generic name because a document that carries
 * its own `FontData` has said exactly which bytes it wants.
 *
 * Whether the generic fallback chain should move onto the loader — letting a host own it — is
 * deliberately left to #4061; this preserves today's behaviour exactly.
 */
/**
 * The key a document uses when it names no family at all. A host's default-role family is
 * registered under it as well as under its own name — see [RcManifestTypefaceLoader].
 */
internal const val RC_DEFAULT_FAMILY: String = "default"

internal fun rcResolveTypeface(
  recordedName: String?,
  fontFamilyId: Int,
  embedded: Map<Int, FontFamily>,
  loader: RcTypefaceLoader,
  settings: FontVariation.Settings? = null,
): FontFamily {
  fun host(name: String): FontFamily? = loader.typeface(name, settings)
  return when (val family = recordedName?.lowercase()) {
    null,
    RC_DEFAULT_FAMILY -> host(RC_DEFAULT_FAMILY) ?: FontFamily.Default
    "sans-serif" -> host(family) ?: FontFamily.SansSerif
    "serif" -> host(family) ?: FontFamily.Serif
    "monospace" -> host(family) ?: FontFamily.Monospace
    else -> embedded[fontFamilyId] ?: host(family.removePrefix("google:")) ?: FontFamily.Default
  }
}
