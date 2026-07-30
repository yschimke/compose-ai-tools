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

import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Exercises the **jvm document render harness** — [renderRemoteDocumentToPng] — end to end: it
 * parses a captured `.rc` document, drives the whole shared draw/layout dispatch through skiko, and
 * PNG-encodes the result, with no Android and no Robolectric. This is the test that the "cmp-jvm"
 * render lane actually produces pixels.
 *
 * The fixture is `TitleCardRemote-640x480.rc`, the same captured document the Android embedded lane
 * (`RcEmbeddedRenderHarness`) and the idle/semantics probes render — shared from the Android
 * module's test resources (see this module's `build.gradle.kts`), so the jvm output is comparable
 * to the Android one rather than being a different document.
 *
 * ## What is asserted, and what deliberately is not
 *
 * Not asserted: an exact pixel match against a baked PNG. That is the CI `rc-compare` lane's job
 * (which diffs against the View player, the parity target), and it needs the same host fonts on
 * both sides; pinning pixels here would pin *this container's* fonts, exactly as
 * `DesktopTextPlatformTest` explains for the text seam.
 *
 * Asserted instead: the harness returns a **decodable PNG of the requested size that is not blank**
 * — i.e. the dispatch tree actually painted something. A render path that silently drew nothing (an
 * empty document, a swallowed exception, a mis-provided composition local) would still return a
 * valid PNG, so the non-blank check is the one that has teeth.
 */
class RcJvmRendererTest {

  /**
   * Skips the class when skiko's native library cannot load, rather than reporting a cryptic
   * `NoClassDefFoundError` per method. Same rationale (and same loud-skip contract) as
   * `DesktopTextPlatformTest.requireSkikoNatives`: `ImageComposeScene` rasterizes through Skia,
   * which link-depends on the platform GL library even for offscreen raster, so a host without one
   * cannot run any of this. If this prints on CI, fix the environment (`libgl1` +
   * `LD_LIBRARY_PATH`, and `./gradlew --stop` so test workers don't inherit a stale daemon
   * environment).
   */
  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "RcJvmRendererTest skipped entirely: skiko's native library did not load, so the jvm " +
          "document render harness was never exercised. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  private fun fixtureBytes(): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/$FIXTURE")) {
        "missing test fixture $FIXTURE — is the Android module's test-resources srcDir shared?"
      }
      .use { it.readBytes() }

  @Test
  fun rendersTheCapturedDocumentToANonBlankPngOfTheRequestedSize() {
    val png = renderRemoteDocumentToPng(fixtureBytes(), WIDTH, HEIGHT)

    // A real PNG the size we asked for.
    val image = Image.makeFromEncoded(png)
    assertEquals("rendered width", WIDTH, image.width)
    assertEquals("rendered height", HEIGHT, image.height)

    // …and it actually drew something: more than one distinct pixel value. A path that composed the
    // tree but painted nothing (or threw and was swallowed) would come back a single flat colour.
    assertTrue(
      "the render is blank — the dispatch tree painted nothing",
      distinctColorCount(png) > 1,
    )

    // Dump the render for inspection (visual evidence / local debugging). Not an assertion — the
    // path above is what has teeth; this just leaves the PNG on disk when the lane runs for real.
    // An env var (not a system property) so it reaches the forked test worker without build wiring.
    System.getenv(DUMP_ENV)?.let { dir ->
      java.io.File(dir).apply { mkdirs() }.resolve("TitleCardRemote-jvm.png").writeBytes(png)
    }
  }

  /** Number of distinct ARGB values in the decoded PNG, capped for cost — >1 means "not flat". */
  private fun distinctColorCount(png: ByteArray): Int {
    val image = Image.makeFromEncoded(png)
    val bitmap = org.jetbrains.skia.Bitmap()
    bitmap.allocN32Pixels(image.width, image.height)
    check(image.readPixels(bitmap)) { "could not read pixels back from the rendered image" }
    val seen = HashSet<Int>()
    var y = 0
    while (y < bitmap.height) {
      var x = 0
      while (x < bitmap.width) {
        seen.add(bitmap.getColor(x, y))
        if (seen.size > 1) return seen.size
        x += 7 // stride the scan; we only need to prove non-flatness, not enumerate colours
      }
      y += 7
    }
    return seen.size
  }

  private companion object {
    const val FIXTURE = "rc-fixtures/TitleCardRemote-640x480.rc"
    const val WIDTH = 640
    const val HEIGHT = 480

    /** When set, the rendered PNG is written under this directory for inspection. */
    const val DUMP_ENV = "RC_JVM_DUMP"

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
