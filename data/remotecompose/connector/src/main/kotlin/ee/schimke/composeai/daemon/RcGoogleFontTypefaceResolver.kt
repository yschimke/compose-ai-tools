@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import android.graphics.Typeface
import android.view.ViewGroup
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.player.core.platform.DefaultTypefaceResolver
import androidx.compose.remote.player.core.platform.FontInstance
import androidx.compose.remote.player.core.platform.TypefaceResolver
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.remote.player.view.platform.RemoteComposeView
import ee.schimke.composeai.fonts.google.GoogleFontCache
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File

/**
 * A [TypefaceResolver] for the view-backed (`java`) Remote Compose lane that serves a
 * `google:`-namespaced family, delegating everything else to the stock [DefaultTypefaceResolver].
 *
 * The AOSP player resolves a named family by listing `/system/fonts/` for a filename containing the
 * name and falling back to `Typeface.create(name, style)`; there is no downloadable-font path
 * anywhere in `remote-player-view` / `remote-player-core`, so `google:Orbitron` renders in the
 * platform default — while the same document under the vendored embedded player (which asks
 * `FontsContractCompat`, intercepted by the daemon's `ShadowFontsContractCompat`) and in the
 * browser lane (which fetches from the Google Fonts CSS API) shows the real face. Two chips in one
 * viewer disagreeing about one document is the gap this closes; see `docs/design/
 * RC_PLAYER_TYPEFACES.md`.
 *
 * Resolution goes through the same [GoogleFontSource] the Robolectric downloadable-font shadow and
 * the figma-svg embed path use, so a family resolves to *the same file* in every lane — one shared
 * machine-local cache keyed by `(family, weight, italic)`, not a second downloader that could drift
 * onto a differently-metricked face.
 *
 * Everything here is a fallback, never a failure: no cache directory configured, an offline cache
 * miss, a file the platform won't parse — each degrades to [delegate], which is exactly the
 * behaviour before this resolver existed.
 */
internal class RcGoogleFontTypefaceResolver(
  private val delegate: TypefaceResolver,
  private val fonts: GoogleFontSource,
  /**
   * Resolves a document *text id* to its string, so a named family that reaches the paint layer as
   * an id rather than a name is still recognisable. Null for an id that names no text — an embedded
   * `FontData` id, notably, which must stay with [delegate].
   */
  private val textForId: (Int) -> String?,
  /** Seam: `Typeface.createFromFile` + the requested weight/italic. Replaced in tests. */
  private val typefaceLoader: (File, Int, Boolean) -> Typeface? = ::loadTypefaceFromFile,
) : TypefaceResolver {

  /**
   * Faces resolved so far, keyed by request. `resolve` is called per paint change, so without this
   * a text-heavy document re-reads (and re-parses) the same file on every op.
   */
  private val instances = HashMap<GoogleFontKey, FontInstance?>()

  override fun resolve(
    fontType: Int,
    weight: Int,
    italic: Boolean,
    fallbackTypeface: Typeface?,
    fallbackWeight: Int,
    fallbackItalic: Boolean,
  ): FontInstance {
    // 0..3 are the built-in families; anything above is an id — either an embedded `FontData` (the
    // delegate's own job, and it does it) or the text id a named family arrives as, because
    // `CoreText.updateVariables` falls through to `mType = mFontFamilyId` for a family it doesn't
    // recognise. Only the latter can name a Google font, and only a `google:` prefix says it does.
    if (fontType > BUILT_IN_FAMILY_MAX) {
      googleInstance(textForId(fontType), weight, italic)?.let {
        return it
      }
    }
    return delegate.resolve(
      fontType,
      weight,
      italic,
      fallbackTypeface,
      fallbackWeight,
      fallbackItalic,
    )
  }

  override fun resolve(
    fontName: String,
    weight: Int,
    italic: Boolean,
    fallbackTypeface: Typeface?,
    fallbackWeight: Int,
    fallbackItalic: Boolean,
  ): FontInstance {
    googleInstance(fontName, weight, italic)?.let {
      return it
    }
    return delegate.resolve(
      fontName,
      weight,
      italic,
      fallbackTypeface,
      fallbackWeight,
      fallbackItalic,
    )
  }

  private fun googleInstance(family: String?, weight: Int, italic: Boolean): FontInstance? {
    val key = googleFontKey(family, weight, italic) ?: return null
    if (instances.containsKey(key)) return instances[key]
    // A miss is remembered too. `resolve` runs per text op, so a family the source can't serve —
    // a typo, an offline cache miss, a failed download, a file the platform won't parse — would
    // otherwise re-attempt the (network-backed) fetch for every run in the document. Storing null
    // makes it one attempt per key, and the caller falls back to `DefaultTypefaceResolver` as fast
    // as it did the first time.
    val instance =
      fonts.load(key)?.let { file ->
        typefaceLoader(file, weight, italic)?.let { VariableFontInstance(it, file, weight, italic) }
      }
    instances[key] = instance
    return instance
  }

  /**
   * A [FontInstance] over a downloaded face that can also serve *variable-font instances* of it.
   *
   * The plain typeface answers [getTypeface]. [applyVariationSettings] is the interesting half: a
   * document that names axes (`wght 700`, `wdth 25`) is asking for a different instance of the same
   * file, and the only way to get one on Android is to rebuild the typeface from the file with
   * those axes — `Typeface.Builder(file).setFontVariationSettings(...)`, which is what the platform
   * resolver does for a `/system/fonts/` face. Returning the base typeface instead (the obvious
   * no-op) is what makes a `wght` ramp draw every line at one weight.
   *
   * Instances are cached per axis string: the paint layer re-applies the same settings on every
   * text op, and re-parsing a font file per op would be visible on a text-heavy document.
   */
  private class VariableFontInstance(
    private val typeface: Typeface,
    private val file: File,
    private val weight: Int,
    private val italic: Boolean,
  ) : FontInstance {
    private val variations = HashMap<String, Typeface>()

    override fun getTypeface(): Typeface = typeface

    override fun applyVariationSettings(tags: Array<String>, values: FloatArray): Typeface {
      val settings = variationSettings(tags, values) ?: return typeface
      variations[settings]?.let {
        return it
      }
      val instance =
        runCatching {
            Typeface.Builder(file)
              .setFontVariationSettings(settings)
              .setWeight(weight)
              .setItalic(italic)
              .build()
          }
          .getOrNull() ?: typeface
      variations[settings] = instance
      return instance
    }

    override fun setOnLoadedListener(listener: Runnable) {
      // The face is already resolved when the instance is handed out, so a listener would never
      // fire; callers read "no listener call" as "nothing pending", which is the truth here.
    }
  }

  companion object {
    /** `0 = default, 1 = sans-serif, 2 = serif, 3 = monospace`; above that, a value is an id. */
    private const val BUILT_IN_FAMILY_MAX = 3

    /** The namespace marking a family as one to fetch from Google Fonts. */
    const val GOOGLE_PREFIX = "google:"

    /**
     * The cache key for a document's [family] name, or null when the name isn't a Google Fonts
     * request.
     *
     * Only the `google:` prefix opts in, matching the browser lane's `parseFamily` and the embedded
     * player's resolver. Treating any unrecognised family as a Google font would turn a typo — or a
     * name that only means something on the host ("SF Pro") — into a network fetch, and would leave
     * no way to say "this one is local".
     */
    /**
     * The CSS-ish font-variation string Android's `Typeface.Builder` takes (`'wght' 700,'wdth' 25`),
     * or null when the request names no usable axis. Tags and values are positional, so an axis
     * counts only when both halves are present — pairing a tag with a neighbour's value would apply
     * a silently wrong instance.
     */
    fun variationSettings(tags: Array<String>?, values: FloatArray?): String? {
      if (tags == null || values == null) return null
      val axes =
        tags.mapIndexedNotNull { index, tag ->
          val value = values.getOrNull(index) ?: return@mapIndexedNotNull null
          tag.takeIf { it.isNotBlank() }?.let { "'$it' $value" }
        }
      return axes.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun googleFontKey(family: String?, weight: Int, italic: Boolean): GoogleFontKey? {
      val name = family?.trim() ?: return null
      if (!name.startsWith(GOOGLE_PREFIX, ignoreCase = true)) return null
      val bare = name.substring(GOOGLE_PREFIX.length).trim()
      if (bare.isEmpty()) return null
      return GoogleFontKey(bare, weight, italic)
    }
  }
}

/**
 * `Typeface.createFromFile` plus the requested weight/italic. The cache already keys the *file* on
 * `(family, weight, italic)`, so the second step only restates the request for a face whose file
 * serves a range; a file the platform won't parse yields null and the caller falls back.
 */
private fun loadTypefaceFromFile(file: File, weight: Int, italic: Boolean): Typeface? =
  runCatching { Typeface.create(Typeface.createFromFile(file), weight, italic) }.getOrNull()

/**
 * The shared machine-local Google font cache, or null when this render was not given one.
 *
 * Same two system properties the Robolectric downloadable-font shadow and the figma-svg embed path
 * read (`composeai.fonts.cacheDir`, `composeai.fonts.offline`), set for the daemon by the Gradle
 * plugin's render task and by the CLI's daemon launchers — so all three lanes share one cache
 * directory and one offline switch. A render with no cache directory keeps the stock resolution
 * rather than downloading to a temporary location on every frame.
 */
internal fun daemonGoogleFontSource(): GoogleFontSource? {
  val cacheDir = System.getProperty("composeai.fonts.cacheDir")?.takeIf { it.isNotBlank() }
  return cacheDir?.let {
    GoogleFontCache(
      cacheDir = File(it),
      offline = System.getProperty("composeai.fonts.offline")?.lowercase() == "true",
    )
  }
}

/**
 * Installs [RcGoogleFontTypefaceResolver] on [player], leaving the stock resolution in place when
 * there is no font cache or the player's `RemoteContext` can't be reached.
 *
 * Called from the view-player call sites' `init` callback — the `AndroidView` factory's player,
 * before it has a canvas — which is early enough: `AndroidRemoteContext.useCanvas` pushes whatever
 * resolver the context holds into the paint context when it creates one, on the first draw.
 */
internal fun installGoogleFontTypefaceResolver(
  player: RemoteComposePlayer,
  fonts: GoogleFontSource? = daemonGoogleFontSource(),
) {
  val source = fonts ?: return
  val remoteContext = player.remoteContextOrNull() ?: return
  player.setTypefaceResolver(
    RcGoogleFontTypefaceResolver(
      delegate = DefaultTypefaceResolver(remoteContext),
      fonts = source,
      textForId = { id -> runCatching { remoteContext.getText(id) }.getOrNull() },
    )
  )
}

/**
 * The `RemoteContext` behind this player, or null when it can't be reached. `RemoteComposePlayer`
 * keeps its own `getRemoteContext()` private, so the public route is the `RemoteComposeView` it
 * wraps — and if that shape ever changes, callers install nothing rather than break.
 */
private fun RemoteComposePlayer.remoteContextOrNull(): RemoteContext? {
  fun find(group: ViewGroup): RemoteContext? {
    for (i in 0 until group.childCount) {
      when (val child = group.getChildAt(i)) {
        is RemoteComposeView -> return child.remoteContext
        is ViewGroup -> find(child)?.let { return it }
        else -> Unit
      }
    }
    return null
  }
  return find(this)
}
