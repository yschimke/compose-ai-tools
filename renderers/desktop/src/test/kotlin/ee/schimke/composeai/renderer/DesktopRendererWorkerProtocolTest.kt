package ee.schimke.composeai.renderer

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end check that a **pooled capture is indistinguishable from a forked one**.
 *
 * `DesktopRendererReentrancyTest` proves the renderer is safe to call repeatedly in one process;
 * this proves the *transport* around it adds nothing — that a worker driven over its frame protocol
 * writes the same pixels to the same place as the per-capture `javaexec` it replaces, keeps serving
 * after a capture it cannot draw, and exits cleanly when its stdin closes.
 *
 * The plugin-side pool (`DesktopRenderWorkerPool`) is covered separately against a stub; this one
 * runs the real renderer, so it needs skiko's natives and skips loudly without them.
 */
class DesktopRendererWorkerProtocolTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var worker: Process? = null

  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "DesktopRendererWorkerProtocolTest skipped entirely: skiko's native library did not load, " +
          "so the pooled-capture transport was never exercised. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @After
  fun stopWorker() {
    worker?.destroyForcibly()
  }

  @Test
  fun aWorkerDrawsWhatTheForkedRendererDrawsAndStaysWarmAcrossCaptures() {
    // Reference: the renderer invoked exactly as `invokeRenderer` forks it today.
    val forked = File(tempFolder.newFolder("forked"), "sticker.png")
    renderForked(forked)
    assertTrue("forked render wrote no file", forked.isFile)

    val process = startWorker().also { worker = it }
    val toWorker = DataOutputStream(process.outputStream.buffered())
    val fromWorker = DataInputStream(process.inputStream.buffered())

    assertEquals("hello magic", MAGIC_HELLO, fromWorker.readInt())
    assertEquals("protocol version", WORKER_PROTOCOL_VERSION, fromWorker.readInt())

    val pooledDir = tempFolder.newFolder("pooled")
    val first = File(pooledDir, "sticker-1.png")
    assertEquals(STATUS_OK, fromWorker.exchange(toWorker, argsFor(first), requestId = 1))
    assertPixelsEqual(forked, first, "a pooled capture must draw what the forked renderer drew")

    // Second capture on the SAME process — the amortisation the pool exists for, which must not
    // change the picture either.
    val second = File(pooledDir, "sticker-2.png")
    assertEquals(STATUS_OK, fromWorker.exchange(toWorker, argsFor(second), requestId = 2))
    assertPixelsEqual(forked, second, "a warm capture drifted from the first")

    // Closing stdin is the shutdown contract; the worker must exit 0, not be killed.
    toWorker.close()
    assertTrue("worker did not exit after stdin closed", process.waitFor(120, TimeUnit.SECONDS))
    assertEquals("clean shutdown exit code", 0, process.exitValue())
  }

  @Test
  fun aCaptureTheRendererCannotDrawDoesNotCostTheWorker() {
    val process = startWorker().also { worker = it }
    val toWorker = DataOutputStream(process.outputStream.buffered())
    val fromWorker = DataInputStream(process.inputStream.buffered())
    fromWorker.readInt()
    fromWorker.readInt()

    // A class that isn't there. The renderer records this per capture rather than dying, exactly
    // as the forked path reported it via one non-zero exit.
    val doomed = File(tempFolder.newFolder("doomed"), "missing.png")
    val bad =
      argsFor(doomed).toMutableList().also { it[0] = "ee.schimke.composeai.renderer.NoSuchClassKt" }
    fromWorker.exchange(toWorker, bad, requestId = 1)

    // The point: the worker is still serving. A pool that lost a warm JVM to every bad preview
    // would give most of the amortisation back on a module with one broken capture.
    val good = File(tempFolder.newFolder("after"), "sticker.png")
    assertEquals(
      "worker did not survive a capture it could not draw",
      STATUS_OK,
      fromWorker.exchange(toWorker, argsFor(good), requestId = 2),
    )
    assertTrue("recovered capture wrote no file", good.isFile)
  }

  private fun assertPixelsEqual(expected: File, actual: File, message: String) {
    val a = decode(expected.readBytes())
    val b = decode(actual.readBytes())
    assertEquals("$message (size)", a.width to a.height, b.width to b.height)
    val differing = a.argb.indices.count { a.argb[it] != b.argb[it] }
    assertEquals("$message ($differing of ${a.argb.size} pixels differ)", 0, differing)
  }

  private fun DataInputStream.exchange(
    toWorker: DataOutputStream,
    args: List<String>,
    requestId: Int,
    seed: String = "",
  ): Int {
    val seedBytes = seed.toByteArray(Charsets.UTF_8)
    toWorker.writeInt(MAGIC_REQUEST)
    toWorker.writeInt(requestId)
    toWorker.writeInt(seedBytes.size)
    toWorker.write(seedBytes)
    toWorker.writeInt(args.size)
    args.forEach {
      val b = it.toByteArray(Charsets.UTF_8)
      toWorker.writeInt(b.size)
      toWorker.write(b)
    }
    toWorker.flush()

    assertEquals("response magic", MAGIC_RESPONSE, readInt())
    assertEquals("response is for the request we sent", requestId, readInt())
    val status = readInt()
    val len = readInt()
    ByteArray(len).also { readFully(it) }
    return status
  }

  private fun argsFor(target: File) =
    listOf(
      FIXTURE_CLASS,
      FIXTURE_FUNCTION,
      WIDTH.toString(),
      HEIGHT.toString(),
      DENSITY.toString(),
      "true",
      "0",
      target.absolutePath,
    )

  private fun renderForked(target: File) {
    val process =
      ProcessBuilder(
          jvmCommand("ee.schimke.composeai.renderer.DesktopRendererMainKt") + argsFor(target)
        )
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    assertTrue("forked render did not finish", process.waitFor(180, TimeUnit.SECONDS))
    assertEquals("forked render exit code", 0, process.exitValue())
  }

  private fun startWorker(): Process =
    ProcessBuilder(jvmCommand("ee.schimke.composeai.renderer.DesktopRendererWorkerMainKt"))
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

  /**
   * Identical flags for both lanes — differing ones would make them draw differently for reasons
   * that have nothing to do with pooling.
   */
  private fun jvmCommand(mainClass: String): List<String> {
    val java = File(System.getProperty("java.home"), "bin/java")
    return listOf(
      if (java.canExecute()) java.absolutePath else "java",
      "-Dapple.awt.UIElement=true",
      "-cp",
      System.getProperty("java.class.path"),
      mainClass,
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
