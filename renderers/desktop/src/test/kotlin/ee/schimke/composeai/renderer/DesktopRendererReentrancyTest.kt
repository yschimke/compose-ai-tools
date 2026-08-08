package ee.schimke.composeai.renderer

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The gate on turning `composePreviewRender` from **one JVM per capture** into a pool of long-lived
 * renderer workers.
 *
 * `RenderPreviewsTask.invokeRenderer` calls `execOperations.javaexec` once per capture, so every
 * preview pays Compose Desktop + Skiko boot. m3-catalog measures that whole path at **2.15
 * s/preview** (~43 min for its 1095-preview catalog) while the daemon — the same work on an
 * already-warm JVM — runs at ~0.31 s/preview. The gap is startup, not drawing.
 *
 * The obvious fix is the one already shipped for the cmp-jvm lane (`RcJvmWorkerPool`): keep the
 * renderer exactly as it is and stop paying for its JVM more than once. That is only viable if
 * [main] can be called repeatedly **in one process** and produce the same pixels each time, which
 * is what this asserts.
 *
 * Why this beats the alternative of routing the task at the preview daemon:
 * * the daemon renders a `@PreviewParameter` provider's *first value only*, where this path fans
 *   out one file per value — routing there today would silently drop every fan-out PNG;
 * * the daemon's render body is a hand-synced *copy* of this one, so the two could drift;
 * * a worker running this same [main] cannot drift from itself by construction.
 *
 * Also measures the amortisation, so the payoff is a number rather than an assumption.
 *
 * Needs skiko's natives and skips loudly without them, like the other render tests here.
 */
class DesktopRendererReentrancyTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "DesktopRendererReentrancyTest skipped entirely: skiko's native library did not load, so " +
          "the renderer-reentrancy gate never ran. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @Test
  fun repeatedInProcessRendersMatchTheFirstAndAmortiseStartup() {
    val outDir = tempFolder.newFolder("renders")

    val timingsNanos = ArrayList<Long>()
    val renders =
      (0 until RENDER_COUNT).map { i ->
        val target = File(outDir, "sticker-$i.png")
        val start = System.nanoTime()
        renderOnce(target)
        timingsNanos += System.nanoTime() - start
        assertTrue("render $i wrote no file", target.isFile)
        target.readBytes()
      }

    val firstMs = timingsNanos.first() / 1_000_000
    val laterMs = timingsNanos.drop(1).map { it / 1_000_000 }
    // Deliberately NOT called a JVM/Skiko-boot saving. This JVM is already up, and the companion's
    // `SKIKO_LOADED` probe has touched `FontMgr` before the first timer starts, so `firstMs` is
    // in-process Compose warm-up only — it understates a real cold start. The number that bounds
    // the win is `laterMs` against the *whole* `javaexec` a capture costs today (2.15 s/preview
    // measured end-to-end by m3-catalog), not against `firstMs`.
    System.err.println(
      "renderer reentrancy: $RENDER_COUNT renders in one JVM — first ${firstMs}ms " +
        "(in-process warm-up only; JVM + Skiko boot already paid before the timer), " +
        "subsequent ${laterMs.joinToString("/")}ms (mean ${laterMs.average().toInt()}ms). " +
        "Each of these is a whole `javaexec` today."
    )

    // The property the pool depends on: a render on a warm process is the same picture as the
    // first one. Compared as decoded pixels — the claim is about what is drawn, and an encoder
    // change would move bytes without moving a pixel.
    val first = decode(renders.first())
    renders.drop(1).forEachIndexed { idx, png ->
      val later = decode(png)
      assertEquals(
        "render ${idx + 1} changed size on a warm JVM",
        first.width to first.height,
        later.width to later.height,
      )
      val differing = first.argb.indices.count { first.argb[it] != later.argb[it] }
      assertEquals(
        "render ${idx + 1} drew different pixels than the first render in the same process — a " +
          "renderer worker pool would then produce output that depends on how many previews the " +
          "worker had already drawn ($differing of ${first.argb.size} pixels differ)",
        0,
        differing,
      )
    }
  }

  /**
   * The other half of worker safety, and the one repetition alone cannot see: a render must not
   * inherit state from whatever the process drew *before* it.
   *
   * `@OverrideVariant` seeds are the sharp edge. `main()` applies a seed only when
   * `composeai.overrides.seed` is set, and `clearDeclarations()` deliberately keeps seeds alive
   * across compositions — so before this gate, a seeded variant followed by an ordinary preview
   * left the ordinary preview rendering with the variant's knobs. A fresh JVM per capture hid it; a
   * pooled worker would not.
   */
  @Test
  fun aRenderDoesNotInheritTheSeedOfThePreviousOne() {
    val outDir = tempFolder.newFolder("override-renders")

    // Baseline: the fixture with nothing seeded, drawn before any seed exists in this process.
    val baseline = File(outDir, "baseline.png").also { renderOnce(it, overrideFixture = true) }

    // A seeded render, which must look different — otherwise the probe proves nothing.
    val seeded =
      File(outDir, "seeded.png").also {
        withSeed(SEED_JSON) { renderOnce(it, overrideFixture = true) }
      }

    // The same unseeded render again, now *after* a seeded one. This is the contamination case.
    val afterSeeded =
      File(outDir, "after-seeded.png").also { renderOnce(it, overrideFixture = true) }

    val baselinePixels = decode(baseline.readBytes())
    val seededPixels = decode(seeded.readBytes())
    val afterPixels = decode(afterSeeded.readBytes())

    val seedTook =
      baselinePixels.argb.indices.count { baselinePixels.argb[it] != seededPixels.argb[it] }
    assertTrue(
      "the seed changed nothing, so this test cannot detect a leak — fixture or seed is wrong",
      seedTook > 0,
    )

    val leaked =
      baselinePixels.argb.indices.count { baselinePixels.argb[it] != afterPixels.argb[it] }
    assertEquals(
      "an unseeded render inherited the previous render's @OverrideVariant seed " +
        "($leaked of ${baselinePixels.argb.size} pixels differ from the clean baseline) — a " +
        "pooled renderer worker would render previews with knobs belonging to whichever variant " +
        "it happened to draw before them",
      0,
      leaked,
    )
  }

  private fun <T> withSeed(seedJson: String, block: () -> T): T {
    System.setProperty(SEED_PROPERTY, seedJson)
    return try {
      block()
    } finally {
      System.clearProperty(SEED_PROPERTY)
    }
  }

  /**
   * One capture through the real entry point, with the same argument shape
   * `RenderPreviewsTask.invokeRenderer` passes. Calling [main] rather than a private helper is the
   * point: a worker would call exactly this, so anything the gate misses the worker would inherit.
   *
   * Safe to call in-process because every `exitProcess` in [main] is on the argument-validation
   * prologue — a *render* failure writes an `.error.json` sidecar and returns normally.
   */
  private fun renderOnce(target: File, overrideFixture: Boolean = false) {
    main(
      arrayOf(
        if (overrideFixture) OVERRIDE_FIXTURE_CLASS else FIXTURE_CLASS,
        if (overrideFixture) OVERRIDE_FIXTURE_FUNCTION else FIXTURE_FUNCTION,
        WIDTH.toString(),
        HEIGHT.toString(),
        DENSITY.toString(),
        /* showBackground = */ "true",
        /* backgroundColor = */ "0",
        target.absolutePath,
      )
    )
  }

  private class Decoded(val width: Int, val height: Int, val argb: IntArray)

  private fun decode(png: ByteArray): Decoded {
    val image =
      requireNotNull(ImageIO.read(ByteArrayInputStream(png))) { "render did not decode as a PNG" }
    return Decoded(
      image.width,
      image.height,
      image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
    )
  }

  private companion object {
    const val FIXTURE_CLASS = "ee.schimke.composeai.renderer.SizeBoundsRenderTestFixturesKt"
    const val FIXTURE_FUNCTION = "WrapContentSticker"
    const val WIDTH = 200
    const val HEIGHT = 200
    const val DENSITY = 2.0f
    const val RENDER_COUNT = 8

    const val OVERRIDE_FIXTURE_CLASS = "ee.schimke.composeai.renderer.OverrideLeakTestFixturesKt"
    const val OVERRIDE_FIXTURE_FUNCTION = "OverrideLeakSticker"
    const val SEED_PROPERTY = "composeai.overrides.seed"

    /** Seeds [LEAK_KNOB_KEY] to a colour far from the fixture's default green. */
    const val SEED_JSON =
      """{"name":"leak","seeds":[{"key":"$LEAK_KNOB_KEY","kind":"COLOR","raw":"#FFB71C1C"}]}"""

    var skikoLoadFailure: String? = null

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
