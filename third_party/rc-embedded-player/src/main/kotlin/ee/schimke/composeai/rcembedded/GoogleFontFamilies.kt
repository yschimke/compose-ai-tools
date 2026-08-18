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

package ee.schimke.composeai.rcembedded

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.fonts.google.GoogleFontCache
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a `google:` family — at a document's font-variation axes when it carries any — from the
 * shared machine-local Google Fonts cache.
 *
 * The player has another way to resolve one: it hands the name to Compose's downloadable-font path
 * (`Font(GoogleFont(...))`), which needs a font provider to answer. Two things that path cannot do
 * are why this exists.
 *
 * It cannot **vary** a family. The `Font(GoogleFont)` factory takes a weight and a style and has no
 * `variationSettings` parameter at all — the upstream `TODO: Support variation settings for Google
 * fonts` in the text-layout seam. A document asking for `wght 1000` drew the family's default
 * instance, and the catalog's `wght` / `wdth` specimens rendered as four identical lines. Applying
 * axes needs the face's *bytes*, so an axis request resolves the family's **variable** file — the
 * one carrying an `fvar` table — through [GoogleFontSource.loadVariable] and builds a Compose
 * `Font` from it with the axes attached. `Font(File, …, variationSettings)` applies them through
 * `Typeface.Builder.setFontVariationSettings` on API 26+, so the instance drawn is a real
 * interpolation of the file rather than a synthesised approximation. That is a *different* file
 * from the static one: the CSS API serves a baked instance even for a purely variable family.
 *
 * And it cannot resolve **anything** where no provider answers. Under Robolectric that means the
 * render must carry the `FontsContractCompat` shadow — the daemon does, the `rc-compare` harness
 * does not — so an unvaried branded family rendered in the platform default on that lane while four
 * other lanes drew the real face (compose-ai-tools#4170). A request with no axes therefore resolves
 * the ordinary static face from the same cache, which is what the jvm player's resolver already
 * does with the same request.
 *
 * Nothing here can fail a render. No cache directory configured, an offline miss, a family with no
 * variable file (Lobster Two ships static faces only), a file the platform won't decode — every one
 * yields null and the caller falls back to the downloadable-font path, exactly as it did before
 * this existed. On a device that is *always* the outcome: [fonts] is null unless
 * `composeai.fonts.cacheDir` is set, and that is a render-side property no app sets.
 */
internal class GoogleFontFamilies(private val fonts: GoogleFontSource?) {

  /**
   * Resolved families by request. The axis list is part of the key, not just the family: a variable
   * file serves many instances and they are different faces, so a cache keyed on the family alone
   * would hand a `wght 100` line the `wght 1000` family the previous line built.
   */
  private val families = ConcurrentHashMap<Request, FontFamily>()

  /**
   * Files by family, including misses — ask the (possibly network-backed) source once, not per op.
   */
  private val files = ConcurrentHashMap<Pair<String, Boolean>, File>()

  /** Static faces by `(family, weight, italic)` — the no-axes request, one file per instance. */
  private val staticFiles = ConcurrentHashMap<GoogleFontKey, File>()

  private data class Request(
    val family: String,
    val italic: Boolean,
    val weight: Int,
    val axes: List<Pair<String, Float>>,
  )

  /**
   * The [FontFamily] drawing [family] at [axes], or null when this isn't a request it can serve —
   * not a `google:` family, or no file for it.
   *
   * With no [axes] this is the family's ordinary static face; with axes it is an instance of its
   * variable file. See the class doc for why both are served from the cache rather than left to the
   * downloadable-font path.
   */
  fun composeFontFamily(
    family: String?,
    weight: FontWeight,
    style: FontStyle,
    axes: List<Pair<String, Float>>,
  ): FontFamily? {
    val name = googleFamilyName(family) ?: return null
    val italic = style == FontStyle.Italic
    val request = Request(name, italic, weight.weight, axes)
    families[request]?.let {
      return it
    }
    if (axes.isEmpty()) {
      // No axes: the static instance the cache serves for this exact (family, weight, italic) is
      // the whole answer, and there is nothing to vary it with.
      val staticFile = resolveStaticFile(name, weight.weight, italic) ?: return null
      val resolved =
        runCatching { FontFamily(Font(file = staticFile, weight = weight, style = style)) }
          .getOrNull() ?: return null
      families[request] = resolved
      return resolved
    }
    val file = resolveFile(name, italic) ?: return null
    val settings =
      FontVariation.Settings(
        *axes.map { (tag, value) -> FontVariation.Setting(tag, value) }.toTypedArray()
      )
    val resolved =
      runCatching {
        FontFamily(Font(file = file, weight = weight, style = style, variationSettings = settings))
      }
        .getOrNull() ?: return null
    families[request] = resolved
    return resolved
  }

  /** The static face for one `(family, weight, italic)`, cached including misses like [files]. */
  private fun resolveStaticFile(name: String, weight: Int, italic: Boolean): File? {
    val source = fonts ?: return null
    val key = GoogleFontKey(name, weight, italic)
    staticFiles[key]?.let {
      return it.takeIf { cached -> cached !== NO_FILE }
    }
    val file = runCatching { source.load(key) }.getOrNull()
    staticFiles[key] = file ?: NO_FILE
    return file
  }

  private fun resolveFile(name: String, italic: Boolean): File? {
    val source = fonts ?: return null
    val key = name to italic
    files[key]?.let {
      return it.takeIf { cached -> cached !== NO_FILE }
    }
    val file = runCatching { source.loadVariable(name, italic) }.getOrNull()
    files[key] = file ?: NO_FILE
    return file
  }

  companion object {
    /** The namespace marking a family as one to fetch from Google Fonts. */
    const val GOOGLE_PREFIX = "google:"

    /** Negative-cache marker — a family the source could not serve a variable file for. */
    private val NO_FILE = File("")

    /**
     * The bare family name behind a `google:`-namespaced [family], or null when it isn't one.
     *
     * Only the `google:` prefix opts in, matching the resolvers in the other lanes. A `device:`
     * family is the host's to supply and a bare name is local by definition; treating either as a
     * Google Fonts request would turn a typo into a network fetch.
     */
    fun googleFamilyName(family: String?): String? {
      val name = family?.trim() ?: return null
      if (!name.startsWith(GOOGLE_PREFIX, ignoreCase = true)) return null
      return name.substring(GOOGLE_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The resolver the player's text seam uses, built once per process from the same two system
     * properties the Robolectric downloadable-font shadow and the figma-svg embed path read:
     * `composeai.fonts.cacheDir` (where resolved files live) and `composeai.fonts.offline` (turn a
     * miss into a null instead of a fetch).
     *
     * No cache directory means no downloads — a render that was not given one keeps the previous
     * axes-dropped behaviour rather than fetching into a directory it would throw away. The daemon
     * launchers set the property for every server-side render.
     */
    val Default: GoogleFontFamilies by lazy {
      testOverride ?: GoogleFontFamilies(systemPropertyGoogleFontSource())
    }

    /**
     * Replaces [Default] before it is first read, so a test can drive the seam from a fake source
     * without a cache directory or a network. Ignored once [Default] has been resolved.
     */
    internal var testOverride: GoogleFontFamilies? = null

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
