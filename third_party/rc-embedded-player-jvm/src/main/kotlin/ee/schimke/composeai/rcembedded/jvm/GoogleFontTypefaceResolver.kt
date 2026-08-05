/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.fonts.google.GoogleFontCache
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface as SkiaTypeface

/**
 * Resolves a document's `google:`-namespaced font family to a real downloaded face, for both of the
 * jvm player's text seams.
 *
 * A `RemoteFontFamily.Named("google:Orbitron")` is a *downloadable font* request: on Android the
 * embedded player hands it to `FontsContractCompat`, and in the browser lane the vendored player
 * fetches it from the Google Fonts CSS API. Off Android there is no font provider, so both jvm
 * seams used to strip the prefix, try the bare name against the host's installed families, and fall
 * back to the default face — the "downloadable fonts" parity limit in PROVENANCE.md. A document
 * naming a branded face therefore rendered in Helvetica/DejaVu here while the other lanes showed
 * the real thing.
 *
 * There is no provider off Android, but there is a downloader: [GoogleFontSource] — the same
 * `(family, weight, italic) -> File` cache the Robolectric downloadable-font shadow and the
 * figma-svg embed path already resolve through. Going through it (rather than fetching again here)
 * is what makes the lanes comparable: one machine-local cache, one resolution rule, so `Orbitron`
 * at weight 400 is *the same file* in every lane rather than two faces that merely share a name.
 *
 * Both seams are served because the player has two of them and they are reached by different
 * documents: [composeFontFamily] for the layout ops (`CoreText` / `TextLayout`, laid out by
 * Compose) and [skiaTypeface] for the canvas ops (`DrawText…`, shaped by skiko directly). A
 * resolver that only fixed one would leave the same document rendering in two typefaces.
 *
 * Nothing here can fail a render. No cache directory configured (the render was not given one), an
 * offline miss, a network failure, a file skia won't parse — every one yields null and the caller
 * keeps the behaviour it had before this existed. A name that resolves nowhere is also remembered
 * as a miss, so a text-heavy document doesn't re-attempt the fetch on every frame.
 */
internal class GoogleFontTypefaceResolver(private val fonts: GoogleFontSource?) {

  /**
   * Resolved files by request. A miss is cached as [NO_FILE] rather than absent — the point is to
   * ask the (possibly network-backed) source once per (family, weight, italic), not once per op.
   */
  private val files = ConcurrentHashMap<GoogleFontKey, File>()
  private val skiaTypefaces = ConcurrentHashMap<GoogleFontKey, SkiaTypeface>()
  /**
   * Resolved families keyed by request *and* axis instance. A variable file serves many instances,
   * and they are different faces — keying on the request alone would hand a `wght 100` line the
   * `wght 1000` family the previous line built.
   */
  private val fontFamilies =
    ConcurrentHashMap<Pair<GoogleFontKey, List<Pair<String, Float>>>, FontFamily>()

  /**
   * The Compose [FontFamily] for [family] at [weight]/[italic], or null when [family] is not a
   * Google Fonts request or the face could not be resolved.
   *
   * One face per family, not a full ramp: the file the cache serves is already the one for this
   * exact `(family, weight, italic)`, so Compose has nothing left to select between — and asking
   * for the weights the document never draws would mean fetches nothing needs.
   *
   * [settings] are the document's font-variation axes. A variable file is one file serving many
   * instances, so they are applied when the face is built rather than selected afterwards: Compose
   * carries variations on a `Font`, and a family's faces cannot be re-instanced once built.
   */
  fun composeFontFamily(
    family: String?,
    weight: Int,
    italic: Boolean,
    settings: FontVariation.Settings? = null,
  ): FontFamily? {
    // A `wght` axis also decides *which file to fetch*. Google Fonts serves a named family as a
    // static instance per weight, so asking for `Roboto Flex` at 400 and then applying `wght 1000`
    // to it varies nothing — the file has no axes to vary. Reading the axis as the requested weight
    // fetches the instance the document actually asked for; the settings are still passed through,
    // so a host that supplies a genuinely variable file (the browser lane's manifest) also gets the
    // exact instance rather than the nearest static one.
    val axes = settings?.settings.orEmpty().map { it.axisName to it.toVariationValue(null) }
    val effectiveWeight =
      axes.firstOrNull { (tag, _) -> tag == WEIGHT_AXIS }?.second?.toInt()?.coerceIn(1, 1000)
        ?: weight
    val key = googleFontKey(family, effectiveWeight, italic) ?: return null
    val instanceKey = key to axes
    fontFamilies[instanceKey]?.let {
      return it
    }
    val file = resolveFile(key) ?: return null
    val style = if (key.italic) FontStyle.Italic else FontStyle.Normal
    val resolved =
      runCatching {
          FontFamily(
            if (settings == null || settings.settings.isEmpty()) {
              Font(file = file, weight = FontWeight(key.weight), style = style)
            } else {
              Font(
                file = file,
                weight = FontWeight(key.weight),
                style = style,
                variationSettings = settings,
              )
            }
          )
        }
        .getOrNull() ?: return null
    fontFamilies[instanceKey] = resolved
    return resolved
  }

  /**
   * The skiko [SkiaTypeface] for [family] at [weight]/[italic], or null when [family] is not a
   * Google Fonts request or the face could not be resolved.
   *
   * The canvas seam shapes through `org.jetbrains.skia.Font`, so it needs the typeface itself
   * rather than a Compose family — the same file, loaded through skia's own font manager.
   */
  fun skiaTypeface(family: String?, weight: Int, italic: Boolean): SkiaTypeface? {
    val key = googleFontKey(family, weight, italic) ?: return null
    skiaTypefaces[key]?.let {
      return it
    }
    val file = resolveFile(key) ?: return null
    val resolved =
      runCatching { FontMgr.default.makeFromFile(file.absolutePath, 0) }.getOrNull() ?: return null
    skiaTypefaces[key] = resolved
    return resolved
  }

  private fun resolveFile(key: GoogleFontKey): File? {
    val source = fonts ?: return null
    val cached = files[key]
    if (cached != null) return cached.takeIf { it !== NO_FILE }
    val file = runCatching { source.load(key) }.getOrNull()
    files[key] = file ?: NO_FILE
    return file
  }

  companion object {
    /** The namespace marking a family as one to fetch from Google Fonts. */
    const val GOOGLE_PREFIX = "google:"

    /**
     * The one axis that also selects a *file*: Google Fonts serves a static instance per weight.
     */
    private const val WEIGHT_AXIS = "wght"

    /** Negative-cache marker — a request the source could not serve. Never opened. */
    private val NO_FILE = File("")

    /**
     * The cache key for a document's [family] name, or null when the name isn't a Google Fonts
     * request.
     *
     * Only the `google:` prefix opts in, matching the browser lane's `parseFamily` and the Android
     * embedded player's resolver. Treating any unrecognised family as a Google font would turn a
     * typo — or a name that only means something on the host ("SF Pro") — into a network fetch, and
     * would leave no way to say "this one is local".
     */
    fun googleFontKey(family: String?, weight: Int, italic: Boolean): GoogleFontKey? {
      val name = family?.trim() ?: return null
      if (!name.startsWith(GOOGLE_PREFIX, ignoreCase = true)) return null
      val bare = name.substring(GOOGLE_PREFIX.length).trim()
      if (bare.isEmpty()) return null
      return GoogleFontKey(bare, weight, italic)
    }

    /**
     * The resolver the player's text seams use, built once per JVM from the same two system
     * properties the Robolectric downloadable-font shadow and the figma-svg embed path read:
     * `composeai.fonts.cacheDir` (where resolved TTFs live) and `composeai.fonts.offline` (turn a
     * miss into a null instead of a fetch).
     *
     * No cache directory means no downloads — a render that was not given one keeps the previous
     * substitute-a-local-face behaviour rather than fetching to a temporary directory it would
     * throw away. `compose-preview serve` and the CLI's daemon launchers set the property; the
     * `cmp-jvm` subprocess is handed it by [RcJvmServerRenderer][the cli's cmp-jvm launcher].
     */
    private val systemPropertyDefault: GoogleFontTypefaceResolver by lazy {
      GoogleFontTypefaceResolver(systemPropertyGoogleFontSource())
    }

    /**
     * Overrides [Default] for a test that must resolve hermetically (a vendored face, no network).
     * Null — always, in production — restores the system-property-configured resolver.
     */
    internal var testOverride: GoogleFontTypefaceResolver? = null

    val Default: GoogleFontTypefaceResolver
      get() = testOverride ?: systemPropertyDefault

    private fun systemPropertyGoogleFontSource(): GoogleFontSource? {
      val cacheDir = System.getProperty("composeai.fonts.cacheDir")?.takeIf { it.isNotBlank() }
      return cacheDir?.let {
        GoogleFontCache(
          cacheDir = File(it),
          offline = System.getProperty("composeai.fonts.offline")?.lowercase() == "true",
        )
      }
    }
  }
}
