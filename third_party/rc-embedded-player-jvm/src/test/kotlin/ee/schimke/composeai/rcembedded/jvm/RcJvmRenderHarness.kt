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

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The **cmp-jvm** counterpart of `RcEmbeddedRenderHarness` (the Android embedded lane): rasterizes
 * captured Remote Compose documents through the desktop/JVM player ([renderRemoteDocumentToPng]) so
 * the `rc-compare` page can diff them against the baked PNG — the View player's output the parity
 * goal targets — next to the TypeScript and Android-embedded renders.
 *
 * A harness, not an assertion test: it renders whatever the driver stages and writes PNGs.
 * `rc-compare.mjs --stage-embedded` writes the input directory (`<id>.rc` + `manifest.json`) that
 * both this and the Android harness read; this fills `rc.jvm.output`, and `rc-compare.mjs
 * --embedded-jvm` reads it back. With nothing staged it skips, so `check` stays green.
 *
 * Density comes from each document's `DOC_DENSITY_AT_GENERATION` header property, staged in the
 * manifest by `rc-compare.mjs`. That keeps dp-denominated modifiers at the same geometry as the
 * baked reference; legacy manifests retain the historical 2.0 fallback.
 *
 * Like the other skiko tests here it **needs the natives** (`skiko-awt-runtime-*` + a loadable GL
 * lib) and skips loudly where they are absent — a CI check, not a bare-working-tree one.
 */
class RcJvmRenderHarness {

  @Test
  fun render() {
    val inputDir = System.getProperty(INPUT_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }
    assumeTrue("no $INPUT_PROPERTY configured — nothing to rasterize", inputDir != null)
    inputDir!!

    if (!SKIKO_LOADED) {
      System.err.println(
        "RcJvmRenderHarness skipped: skiko's native library did not load, so no jvm renders were " +
          "produced for the rc-compare cmp-jvm lane. Cause: $skikoLoadFailure"
      )
    }
    assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)

    val outputDir =
      File(
        requireNotNull(System.getProperty(OUTPUT_PROPERTY)) {
          "$OUTPUT_PROPERTY must be set alongside $INPUT_PROPERTY"
        }
      )
    outputDir.mkdirs()

    val manifest = File(inputDir, "manifest.json")
    if (!manifest.isFile) {
      System.err.println("RcJvmRenderHarness: no manifest.json in $inputDir — nothing to render")
      return
    }

    for (entry in parseManifest(manifest)) {
      val rc = File(inputDir, "${entry.id}.rc")
      if (!rc.isFile) continue

      val png = File(outputDir, "${entry.id}.png")
      val err = File(outputDir, "${entry.id}.error")
      // Clear both first: a document that rendered last run but fails now must not leave a stale
      // PNG
      // the driver would diff as a current render (the same stale-capture trap the Android harness
      // guards against).
      png.delete()
      err.delete()

      runCatching {
        renderRemoteDocumentToPng(rc.readBytes(), entry.width, entry.height, entry.density)
      }
        .onSuccess { bytes -> png.writeBytes(bytes) }
        .onFailure { t -> err.writeText("${t::class.java.simpleName}: ${t.message?.take(500)}") }
    }
  }

  private data class Entry(val id: String, val width: Int, val height: Int, val density: Float)

  private fun parseManifest(manifest: File): List<Entry> =
    Json.parseToJsonElement(manifest.readText()).jsonArray.map { element ->
      val obj = element.jsonObject
      Entry(
        id = obj.getValue("id").jsonPrimitive.content,
        width = obj.getValue("width").jsonPrimitive.int,
        height = obj.getValue("height").jsonPrimitive.int,
        density = obj["density"]?.jsonPrimitive?.float ?: 2f,
      )
    }

  private companion object {
    const val INPUT_PROPERTY = "rc.jvm.input"
    const val OUTPUT_PROPERTY = "rc.jvm.output"

    var skikoLoadFailure: String? = null

    /** Whether Skia is callable at all — decided once by touching a class that loads the native. */
    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
  }
}
