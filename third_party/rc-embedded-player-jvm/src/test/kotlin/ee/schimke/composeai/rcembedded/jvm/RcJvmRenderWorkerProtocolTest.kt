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

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * End-to-end check that a **pooled worker is unobservable**: a document drawn by
 * [rcJvmRenderWorkerMain] over the wire must be byte-identical to the same document drawn by the
 * one-shot `RcJvmRenderMain` subprocess it replaces.
 *
 * `RcJvmHotWorkerDeterminismTest` proves a warm process does not drift from a cold one; this proves
 * the *transport* adds nothing either — that framing, the inline seed text and the stdout hygiene
 * all preserve the artifact exactly. Together they are what let `RcJvmServerRenderer` route a
 * render through the pool or the one-shot path interchangeably without the choice showing up in
 * `rc-compare`'s pixel gate.
 *
 * The cli-side counterpart (`RcJvmWorkerPoolTest`) covers the pool's failure and recycling paths
 * against a stub; this one covers the real player, so it needs skiko's natives and skips loudly
 * where they are absent.
 */
class RcJvmRenderWorkerProtocolTest {

  private var worker: Process? = null

  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "RcJvmRenderWorkerProtocolTest skipped entirely: skiko's native library did not load, so " +
          "the pooled-worker transport was never exercised. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @After
  fun stopWorker() {
    worker?.destroyForcibly()
  }

  @Test
  fun aWorkerRendersByteIdenticallyToTheOneShotLaneAndStaysWarmAcrossDocuments() {
    val doc = fixtureBytes()
    // The reference is the *one-shot subprocess*, not an in-process call. Those are not the same
    // picture: a JVM's graphics configuration (headless mode above all, which Gradle sets on its
    // test workers) reaches text metrics, so an in-process render here differs from any spawned
    // render by ~1 KB of PNG. That is not drift the pool introduces — it is why `renderJvmArgs` is
    // shared, and why the only meaningful question is whether the two *spawned* lanes agree.
    val oneShot = renderOneShot(doc)

    val process = startWorker().also { worker = it }
    val toWorker = DataOutputStream(process.outputStream.buffered())
    val fromWorker = DataInputStream(process.inputStream.buffered())

    assertEquals("hello magic", MAGIC_HELLO, fromWorker.readInt())
    assertEquals("protocol version", PROTOCOL_VERSION, fromWorker.readInt())

    val first = fromWorker.exchange(toWorker, doc, requestId = 1)
    assertEquals("status", STATUS_OK, first.status)
    assertArrayEquals(
      "a pooled render must be byte-identical to the one-shot lane — otherwise which lane served " +
        "a request would be visible in the pixels, and rc-compare's parity gate becomes flaky",
      oneShot,
      first.payload,
    )

    // Second document on the SAME process: this is the amortisation the pool exists for, and it
    // must not change the answer either.
    val second = fromWorker.exchange(toWorker, doc, requestId = 2)
    assertEquals("status", STATUS_OK, second.status)
    assertArrayEquals("warm render drifted from the cold one", oneShot, second.payload)

    // Closing stdin is the shutdown contract; the worker must exit 0 rather than be killed.
    toWorker.close()
    assertTrue("worker did not exit after stdin closed", process.waitFor(60, TimeUnit.SECONDS))
    assertEquals("clean shutdown exit code", 0, process.exitValue())
  }

  @Test
  fun anUndrawableDocumentIsReportedWithoutKillingTheWorker() {
    val process = startWorker().also { worker = it }
    val toWorker = DataOutputStream(process.outputStream.buffered())
    val fromWorker = DataInputStream(process.inputStream.buffered())
    fromWorker.readInt()
    fromWorker.readInt()

    val garbage = fromWorker.exchange(toWorker, ByteArray(64) { 0xEF.toByte() }, requestId = 1)
    assertEquals("garbage should fail, not crash", STATUS_FAILED, garbage.status)
    assertTrue("a failure must carry a reason", garbage.payload.isNotEmpty())

    // The point: one bad document does not cost the pool a worker. The next render still works.
    val good = fromWorker.exchange(toWorker, fixtureBytes(), requestId = 2)
    assertEquals("worker survived a bad document", STATUS_OK, good.status)
    assertArrayEquals(renderOneShot(fixtureBytes()), good.payload)
  }

  private class Frame(val status: Int, val payload: ByteArray)

  private fun DataInputStream.exchange(
    toWorker: DataOutputStream,
    doc: ByteArray,
    requestId: Int,
    seedsText: String = "",
  ): Frame {
    val seeds = seedsText.toByteArray(Charsets.UTF_8)
    toWorker.writeInt(MAGIC_REQUEST)
    toWorker.writeInt(requestId)
    toWorker.writeInt(WIDTH)
    toWorker.writeInt(HEIGHT)
    toWorker.writeInt(DENSITY.toRawBits())
    toWorker.writeInt(WIRE_FORMAT_PNG)
    toWorker.writeInt(WIRE_THEME_LIGHT)
    toWorker.writeInt(seeds.size)
    toWorker.write(seeds)
    toWorker.writeInt(doc.size)
    toWorker.write(doc)
    toWorker.flush()

    assertEquals("response magic", MAGIC_RESPONSE, readInt())
    assertEquals("response is for the request we sent", requestId, readInt())
    val status = readInt()
    val payload = ByteArray(readInt()).also { readFully(it) }
    return Frame(status, payload)
  }

  /**
   * Render through the existing one-shot entry point, spawned exactly the way
   * `RcJvmServerRenderer.renderOneShot` spawns it. This is the lane the pool must be
   * indistinguishable from.
   */
  private fun renderOneShot(doc: ByteArray): ByteArray {
    val input = File.createTempFile("rcjvm-proto-in-", ".rc").apply { writeBytes(doc) }
    val output = File.createTempFile("rcjvm-proto-out-", ".png").apply { delete() }
    try {
      val process =
        ProcessBuilder(
            jvmCommand("ee.schimke.composeai.rcembedded.jvm.RcJvmRenderMainKt") +
              listOf(
                "--input",
                input.absolutePath,
                "--output",
                output.absolutePath,
                "--width",
                WIDTH.toString(),
                "--height",
                HEIGHT.toString(),
                "--density",
                DENSITY.toString(),
                "--format",
                "png",
              )
          )
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
      assertTrue("one-shot render did not finish", process.waitFor(120, TimeUnit.SECONDS))
      assertEquals("one-shot render exit code", 0, process.exitValue())
      return output.readBytes()
    } finally {
      input.delete()
      output.delete()
    }
  }

  /**
   * The JVM flags both lanes boot under, mirroring `RcJvmServerRenderer.renderJvmArgs`. Identical
   * on purpose: differing flags would make the two lanes draw differently for reasons that have
   * nothing to do with pooling.
   */
  private fun jvmCommand(mainClass: String): List<String> {
    val java = File(System.getProperty("java.home"), "bin/java")
    return listOf(
      if (java.canExecute()) java.absolutePath else "java",
      "--enable-native-access=ALL-UNNAMED",
      "-Dapple.awt.UIElement=true",
      "-cp",
      System.getProperty("java.class.path"),
      mainClass,
    )
  }

  /**
   * Spawn the worker on this test JVM's own classpath — it already carries the player and skiko's
   * natives, so no staged `lib-rcjvm/` sidecar is needed to exercise the real entry point.
   */
  private fun startWorker(): Process =
    ProcessBuilder(jvmCommand("ee.schimke.composeai.rcembedded.jvm.RcJvmRenderWorkerMainKt"))
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

  private fun fixtureBytes(): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/$FIXTURE")) { "missing test fixture $FIXTURE" }
      .use { it.readBytes() }

  private companion object {
    const val FIXTURE = "rc-fixtures/TitleCardRemote-640x480.rc"
    const val WIDTH = 640
    const val HEIGHT = 480
    const val DENSITY = 2f

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
