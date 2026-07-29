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
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The **control** for [RcEmbeddedRenderHarness]: rasterizes the same documents through the
 * `remote-player-view`-backed [RemoteDocumentPlayer] in an otherwise identical harness — same
 * Robolectric config, same density, same `waitForIdle()` + software-canvas capture.
 *
 * This exists to make divergences *attributable*. A row where the embedded player disagrees with the
 * baked PNG has at least three possible causes:
 *
 * 1. the embedded player renders it differently (a real finding),
 * 2. software-canvas rasterization can't reproduce it — AGSL `RuntimeShader` in particular needs a
 *    hardware-accelerated canvas, and this harness draws into `Canvas(Bitmap)`,
 * 3. Robolectric's graphics support differs from a device.
 *
 * Causes 2 and 3 hit *both* players equally. So: if the View player matches the baked PNG on a row
 * where the embedded player doesn't, the difference is the embedded player and belongs upstream. If
 * both players diverge the same way, it's the environment and belongs in our harness notes, not in
 * a bug report against the player.
 *
 * Output goes to its own directory (`rc.view.output`) so a run can produce both sets side by side.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcViewPlayerRenderHarness(private val entry: RcEmbeddedRenderHarness.Entry) {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun render() {
    val inputDir = inputDir()
    val outputProperty = System.getProperty(OUTPUT_PROPERTY)
    assumeTrue(
      "no $INPUT_PROPERTY / $OUTPUT_PROPERTY configured — control lane not requested",
      inputDir != null && outputProperty != null,
    )
    val outputDir = File(outputProperty!!).apply { mkdirs() }

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
        Modifier.size(
          with(density) { entry.width.toDp() },
          with(density) { entry.height.toDp() },
        )
      ) {
        val document = remember { RemoteDocument(bytes) }
        // The View player takes the document's pixel size directly rather than filling its parent.
        RemoteDocumentPlayer(
          document = document.document,
          documentWidth = entry.width,
          documentHeight = entry.height,
        )
      }
    }

    composeRule.waitForIdle()

    val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
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
    private const val OUTPUT_PROPERTY = "rc.view.output"

    private fun inputDir(): File? =
      System.getProperty(INPUT_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }

    /** Same documents the embedded lane renders, so the two output sets line up row for row. */
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun documents(): List<Array<Any>> {
      val dir = inputDir() ?: return listOf(arrayOf(RcEmbeddedRenderHarness.Entry("<none>", 1, 1)))
      val manifest = File(dir, "manifest.json")
      if (!manifest.isFile) {
        return listOf(arrayOf(RcEmbeddedRenderHarness.Entry("<no manifest.json>", 1, 1)))
      }
      return Json.decodeFromString<List<RcEmbeddedRenderHarness.Entry>>(manifest.readText())
        .filter { File(dir, "${it.id}.rc").isFile }
        .map { arrayOf(it) }
    }
  }
}
