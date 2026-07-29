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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rasterizes captured Remote Compose documents through the **embedded** player (`RcPlayer`) so the
 * `rc-compare` page can diff them against the baked PNG next to the TypeScript player's render.
 *
 * A harness, not an assertion test: it renders whatever the driver points it at and writes PNGs.
 * `rc-compare.mjs --stage-embedded` writes the input directory (`<id>.rc` + `manifest.json`), this
 * fills the output directory, and `rc-compare.mjs --embedded` reads it back. With nothing staged
 * every case skips, so `check` stays green without a catalog.
 *
 * **One document per test case, deliberately.** Two independent reasons:
 *
 * 1. `RcPlayer` installs its runtime state *onto* the `CoreDocument` and is documented as
 *    one-player-per-document, so sharing a composition risks one row's state bleeding into the next.
 * 2. Swapping documents inside a single composition produced **stale captures** — a document's PNG
 *    came back holding the previous document's pixels. On a compare page that is indistinguishable
 *    from a renderer bug, so the failure mode is removed rather than waited out.
 *
 * **Robolectric is a stopgap.** The CMP android/jvm split exists so this lane can rasterize on a
 * plain JVM through Compose Desktop's Skia backend with no Android runtime. Until then
 * `@GraphicsMode(NATIVE)` gives real pixels.
 *
 * **Why the capture still draws the view by hand.** Every harness here settles with `waitForIdle()`
 * now that the player's frame loop lets the composition reach idle ([RcIdleProbeTest]), but the
 * rasterization itself stays a direct `View.draw(Canvas(bitmap))` rather than `captureToImage()`.
 * That is a Robolectric limit, not a player one: `captureToImage()` calls `forceRedraw`, which waits
 * on a `ViewTreeObserver.OnDrawListener` Robolectric never fires, and times out after 2s for *any*
 * content — [RobolectricCaptureToImageProbeTest] pins that with a bare `Box` and no player at all.
 * When that probe starts failing, Robolectric has grown the draw pass and this can become a
 * `captureToImage()`.
 *
 * Density is pinned: the catalogs capture at dpi 320, so documents carry dp->px factors for density
 * 2.0 (a 200dp preview bakes to 400px). `xhdpi` is that density — rendering at another one
 * re-lays-out the document and every row would diff on geometry instead of renderer behaviour.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcEmbeddedRenderHarness(private val entry: Entry) {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  /** One document to rasterize: `<id>.rc` in the input dir, rendered at the baked PNG's size. */
  @Serializable
  data class Entry(val id: String, val width: Int, val height: Int) {
    /** Drives the JUnit case name. */
    override fun toString(): String = id
  }

  @Test
  fun render() {
    val inputDir = inputDir()
    assumeTrue("no $INPUT_PROPERTY configured — nothing to rasterize", inputDir != null)
    val outputDir =
      File(
        requireNotNull(System.getProperty(OUTPUT_PROPERTY)) {
          "$OUTPUT_PROPERTY must be set alongside $INPUT_PROPERTY"
        }
      )
    outputDir.mkdirs()

    // A document the player cannot render is a *result*, not a reason to fail the run: the driver
    // turns a missing PNG plus this note into an "unrendered" row on the compare page, which is
    // exactly the signal worth surfacing.
    val png = File(outputDir, "${entry.id}.png")
    val err = File(outputDir, "${entry.id}.error")
    // Clear both before rendering. Output directories get reused across runs, and a document that
    // succeeded last time but fails now would otherwise leave its stale PNG in place — the driver
    // checks for the PNG first, so it would diff last run's pixels and report them as a current
    // render. That is precisely the stale-capture failure this harness already had once, in a form
    // that survives across runs rather than within one.
    png.delete()
    err.delete()

    runCatching { renderToBitmap(File(inputDir, "${entry.id}.rc").readBytes()) }
      .onSuccess { bitmap ->
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      }
      .onFailure { t -> err.writeText("${t::class.java.simpleName}: ${t.message?.take(500)}") }
  }

  private fun renderToBitmap(bytes: ByteArray): Bitmap {
    composeRule.setContent {
      val density = LocalDensity.current
      Box(
        // Sized in px routed through the density rather than in dp, so the capture is exactly the
        // baked PNG's pixel size — pixelmatch needs both sides equal, and dp rounding drifts.
        Modifier.size(
          with(density) { entry.width.toDp() },
          with(density) { entry.height.toDp() },
        )
      ) {
        val document = remember { RemoteDocument(bytes) }
        ExperimentalRemoteDocumentPlayer(
          document = document,
          // A still comparison against a still baked PNG.
          modifier = Modifier.fillMaxSize(),
        )
      }
    }

    // The player's frame loop used to keep the composition busy forever, so this harness pumped a
    // fixed number of frames off a manually driven clock. #2945 fixed that, so settling is now just
    // `waitForIdle()` and the render is whatever the document itself came to rest at.
    composeRule.waitForIdle()

    val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
    // Force measure/layout at the document's exact size before drawing, so the draw can't land at
    // the window's bounds instead of the document's.
    root.measure(
      MeasureSpec.makeMeasureSpec(entry.width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(entry.height, MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, entry.width, entry.height)

    val bitmap = Bitmap.createBitmap(entry.width, entry.height, Bitmap.Config.ARGB_8888)
    root.draw(Canvas(bitmap))
    return bitmap
  }

  companion object {
    private const val INPUT_PROPERTY = "rc.embedded.input"
    private const val OUTPUT_PROPERTY = "rc.embedded.output"

    private fun inputDir(): File? =
      System.getProperty(INPUT_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }

    /**
     * One case per staged document. With nothing staged this yields a single placeholder so the
     * runner still has a case to skip — an empty parameter list is an error in `Parameterized`,
     * which would read as a harness failure rather than a no-op.
     */
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun documents(): List<Array<Any>> {
      val dir = inputDir() ?: return listOf(arrayOf(Entry("<none staged>", 1, 1)))
      val manifest = File(dir, "manifest.json")
      if (!manifest.isFile) return listOf(arrayOf(Entry("<no manifest.json>", 1, 1)))
      return Json.decodeFromString<List<Entry>>(manifest.readText())
        .filter { File(dir, "${it.id}.rc").isFile }
        .map { arrayOf(it) }
    }
  }
}
