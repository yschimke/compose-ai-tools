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
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Instances a `google:` family at a document's font-variation axes.
 *
 * The player already resolves a `google:` family: it hands the name to Compose's downloadable-font
 * path (`Font(GoogleFont(...))`), which under the daemon's Robolectric sandbox is served from the
 * shared machine-local font cache. What that path cannot do is *vary* it — the `Font(GoogleFont)`
 * factory takes a weight and a style and has no `variationSettings` parameter at all, which is what
 * the upstream `TODO: Support variation settings for Google fonts` in the text-layout seam is
 * about. A document asking for `wght 1000` therefore drew the family's default instance, and the
 * catalog's `wght` / `wdth` specimens rendered as four identical lines.
 *
 * Applying axes needs the face's *bytes*, so this resolves the family's **variable** file — the one
 * carrying an `fvar` table — through [GoogleFontSource.loadVariable], and builds a Compose `Font`
 * from that file with the axes attached. `Font(File, …, variationSettings)` applies them through
 * `Typeface.Builder.setFontVariationSettings` on API 26+, so the instance the player draws is a
 * real interpolation of the file rather than a synthesised approximation.
 *
 * Note the file is a *different* file from the one the no-axes path resolves: the CSS API serves a
 * baked static instance, and this needs the pre-instancing original. That is why this is consulted
 * only when a document actually carries axes — an unvaried specimen keeps the existing path and its
 * smaller download.
 *
 * Nothing here can fail a render. No cache directory configured, an offline miss, a family with no
 * variable file (Lobster Two ships static faces only), a file the platform won't decode — every one
 * yields null and the caller keeps the behaviour it had before this existed.
 */
internal class GoogleVariableFontFamilies(private val fonts: GoogleFontSource?) {

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

  private data class Request(
    val family: String,
    val italic: Boolean,
    val weight: Int,
    val axes: List<Pair<String, Float>>,
  )

  /**
   * The [FontFamily] drawing [family] at [axes], or null when this isn't a request it can serve —
   * not a `google:` family, no axes to apply, or no variable file for it.
   */
  fun composeFontFamily(
    family: String?,
    weight: FontWeight,
    style: FontStyle,
    axes: List<Pair<String, Float>>,
  ): FontFamily? {
    if (axes.isEmpty()) return null
    val name = googleFamilyName(family) ?: return null
    val italic = style == FontStyle.Italic
    val request = Request(name, italic, weight.weight, axes)
    families[request]?.let {
      return it
    }
    val file = resolveFile(name, italic) ?: return null
    val settings =
      FontVariation.Settings(
        *axes.map { (tag, value) -> FontVariation.Setting(tag, value) }.toTypedArray()
      )
    val resolved =
      runCatching {
          FontFamily(
            Font(file = file, weight = weight, style = style, variationSettings = settings)
          )
        }
        .getOrNull() ?: return null
    families[request] = resolved
    return resolved
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
    val Default: GoogleVariableFontFamilies by lazy {
      testOverride ?: GoogleVariableFontFamilies(systemPropertyGoogleFontSource())
    }

    /**
     * Replaces [Default] before it is first read, so a test can drive the seam from a fake source
     * without a cache directory or a network. Ignored once [Default] has been resolved.
     */
    internal var testOverride: GoogleVariableFontFamilies? = null

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
