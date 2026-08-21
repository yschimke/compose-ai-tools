package com.example.sampleandroidscreenshot

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **Studio parity gate.** Diffs our renderer's output against Android Studio's.
 *
 * The reference images under `src/screenshotTestDebug/reference/` are produced by Google's
 * `com.android.compose.screenshot` plugin, which renders `@Preview`s through **Layoutlib** — the
 * same engine that draws Studio's preview pane. They are committed (that is the plugin's own
 * convention), so this test needs no Studio, no GUI and no network: it is a permanent, regenerable
 * snapshot of "what Studio shows" for the fixtures in [StudioParityMatrix].
 *
 * Regenerate them after changing a fixture:
 * ```
 * ./gradlew -Pandroid.experimental.enableScreenshotTest=true \
 *   :samples:android-screenshot-test:updateDebugScreenshotTest
 * ```
 *
 * Both engines' sizes are pinned exactly, per preview, rather than compared loosely. Where they
 * agree, that pins the parity. Where they *disagree*, the divergence is written down with its cause
 * and issue — so a fix flips the test instead of silently changing output, and a regression can't
 * hide behind a tolerance. [docs/STUDIO_PARITY.md](../../../../../../../docs/STUDIO_PARITY.md)
 * carries the fixture rationale.
 *
 * Same-size pairs are additionally compared pixel-by-pixel. Text rasterisation differs between
 * Layoutlib (native Skia + platform fonts) and our Robolectric renderer, so an exact match is not
 * achievable — the tolerance is on the *fraction of differing pixels*, which separates
 * anti-aliasing noise (well under 1%) from real layout divergence (20%+ here).
 */
class StudioParityTest {

  private val referenceDir = File("src/screenshotTestDebug/reference")
  private val rendersDir = File("build/compose-previews/renders")
  private val reportDir = File("build/studio-parity")

  /** What we expect of one preview when rendered by both engines. */
  private data class Parity(
    val layoutlib: Pair<Int, Int>,
    val ours: Pair<Int, Int>,
    /** Max fraction of differing pixels, when both engines agree on size. */
    val maxDiffFraction: Double = 0.02,
    /** Non-null when the two engines are known to disagree — the reason, and its issue. */
    val divergence: String? = null,
  )

  /**
   * Layoutlib's rounding, for reference while reading the table: an explicit fixed axis rounds
   * half-up (`dp × density + 0.5`) while catalog device frames keep their resolved pixel sizes.
   */
  private val expectations =
    mapOf(
      // --- agree exactly on geometry ---------------------------------------------------------
      // Wrapped axes: both measure the probe's intrinsic 160×80dp at 2.625×.
      "ParityWrapPreview" to Parity(420 to 210, 420 to 210, maxDiffFraction = 0.01),
      // Device frames resolve to whole pixels, so the rounding rules can't diverge.
      "ParityPhoneDevicePreview" to Parity(1080 to 2340, 1080 to 2340, maxDiffFraction = 0.01),
      "ParityDeviceSpecPreview" to Parity(720 to 1280, 720 to 1280, maxDiffFraction = 0.01),

      // Explicit fixed axes use Studio's half-up pixel rounding.
      "ParityDayPreview" to Parity(525 to 263, 525 to 263),
      "ParityNightPreview" to Parity(525 to 263, 525 to 263),
      "ParityFontScalePreview" to Parity(525 to 263, 525 to 263),
      "ParityLocalePreview" to Parity(525 to 263, 525 to 263),
      "ParityBackgroundPreview" to Parity(315 to 158, 315 to 158),
      // Fixed frames measure content with tight constraints, so preferred-size children fill the
      // requested frame just as they do in Studio.
      "ParityFixedPreview" to Parity(525 to 263, 525 to 263),
      "ParityFixedWidthPreview" to Parity(630 to 210, 630 to 210),
      // Round Wear device previews are circularly clipped with transparent corners.
      "ParityWearDevicePreview" to Parity(384 to 384, 384 to 384),
    )

  @Test
  fun `our renders match Android Studio's Layoutlib output, or diverge only in known ways`() {
    val references = collectReferences()
    val ours = collectOurRenders()
    // Whether this gate is APPLICABLE, or BROKEN. The two look identical from in here — our Parity
    // renders are missing either way, and the Layoutlib references are committed so their presence
    // proves nothing — so the build says which it is (`studioParity.required`, set from the same
    // property that materialises the source set).
    //
    // Getting this wrong in the safe-looking direction is what makes it worth the plumbing: a
    // bare `assumeTrue` retires the whole Studio-parity gate the moment the `-P` flag is dropped
    // from CI or `composePreviewRenderAll` quietly renders none of these fixtures. The suite stays
    // green, the checks stay ticked, and nothing is comparing us to Studio any more.
    // Only the wholesale case needs deciding here. A fixture that individually stopped rendering is
    // already a failure below ("our renderer produced no PNG"); it is ALL of them going missing
    // that short-circuits the test, because that is indistinguishable from the source set never
    // having existed.
    val renderedOurs = ours.keys.any { it.startsWith("Parity") }
    if (System.getProperty("studioParity.required") == "true") {
      assertThat(renderedOurs).isTrue()
    } else {
      assumeTrue("no Studio-parity renders in $rendersDir", renderedOurs)
    }
    assertThat(references.keys).isNotEmpty()

    reportDir.mkdirs()
    val failures = mutableListOf<String>()

    expectations.forEach { (name, expected) ->
      val reference = references[name]
      val our = ours[name]
      if (reference == null) {
        failures += "$name: no Layoutlib reference (regenerate with updateDebugScreenshotTest)"
        return@forEach
      }
      if (our == null) {
        failures += "$name: our renderer produced no PNG"
        return@forEach
      }
      val a = ImageIO.read(reference)
      val b = ImageIO.read(our)
      writeSideBySide(name, a, b)

      if ((a.width to a.height) != expected.layoutlib) {
        failures += "$name: Layoutlib rendered ${a.size()}, expected ${expected.layoutlib.pretty()}"
      }
      if ((b.width to b.height) != expected.ours) {
        failures += "$name: our renderer produced ${b.size()}, expected ${expected.ours.pretty()}"
      }
      if (a.width == b.width && a.height == b.height) {
        val fraction = differingFraction(a, b)
        if (fraction > expected.maxDiffFraction) {
          failures +=
            "$name: ${percent(fraction)} of pixels differ from Layoutlib, allowed " +
              percent(expected.maxDiffFraction)
        }
      }
    }

    // A fixture added to [StudioParityMatrix] without an entry here would otherwise be rendered by
    // both engines and compared by neither.
    val unexpected = references.keys.filter { it.startsWith("Parity") } - expectations.keys
    if (unexpected.isNotEmpty()) {
      failures += "no parity expectation declared for: ${unexpected.sorted()}"
    }

    assertThat(failures).isEmpty()
  }

  @Test
  fun `the known divergences are still exactly the ones we have written down`() {
    // Guards the table itself: if a renderer fix closes a divergence, the entry above must be
    // updated in the same change rather than left claiming a difference that no longer exists.
    val declared = expectations.filterValues { it.divergence != null }.keys
    assertThat(declared).isEmpty()
  }

  /** `<Function>_<preview name>_<hash>_<index>.png` → function name. */
  private fun collectReferences(): Map<String, File> =
    referenceDir
      .walkTopDown()
      .filter { it.isFile && it.extension == "png" }
      .mapNotNull { file ->
        // `\w` matches `_`, so a greedy group swallows the preview name and hash too — the
        // function name is the part before the *first* underscore.
        Regex("""^([A-Za-z0-9]+)_.*\.png$""").find(file.name)?.groupValues?.get(1)?.let {
          it to file
        }
      }
      .toMap()

  /** `<Function>_<sanitised preview name>.png` → function name. */
  private fun collectOurRenders(): Map<String, File> =
    (rendersDir.listFiles() ?: emptyArray())
      .filter { it.extension == "png" }
      .associateBy { it.name.substringBefore('_') }

  /** Fraction of pixels differing by more than a rasterisation-noise threshold on any channel. */
  private fun differingFraction(a: BufferedImage, b: BufferedImage): Double {
    var differing = 0L
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val p = a.getRGB(x, y)
        val q = b.getRGB(x, y)
        if (p == q) continue
        val delta =
          maxOf(
            kotlin.math.abs(((p shr 16) and 0xff) - ((q shr 16) and 0xff)),
            kotlin.math.abs(((p shr 8) and 0xff) - ((q shr 8) and 0xff)),
            kotlin.math.abs((p and 0xff) - (q and 0xff)),
            kotlin.math.abs(((p ushr 24) and 0xff) - ((q ushr 24) and 0xff)),
          )
        if (delta > 8) differing++
      }
    }
    return differing.toDouble() / (a.width.toLong() * a.height).toDouble()
  }

  /** Layoutlib on the left, ours on the right — the artifact a reviewer actually looks at. */
  private fun writeSideBySide(name: String, a: BufferedImage, b: BufferedImage) {
    val gap = 16
    val width = a.width + b.width + gap * 3
    val height = maxOf(a.height, b.height) + gap * 2
    val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, width, height)
    g.drawImage(a, gap, gap, null)
    g.drawImage(b, gap * 2 + a.width, gap, null)
    g.dispose()
    ImageIO.write(sheet, "png", File(reportDir, "$name.png"))
  }

  private fun BufferedImage.size() = "${width}x$height"

  private fun Pair<Int, Int>.pretty() = "${first}x$second"

  private fun percent(fraction: Double) = "%.1f%%".format(fraction * 100)
}
