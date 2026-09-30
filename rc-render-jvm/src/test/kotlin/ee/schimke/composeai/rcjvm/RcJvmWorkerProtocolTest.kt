package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Test

/** Drives the pooled worker exactly as `RcJvmWorkerPool` does: frames over stdin/stdout. */
class RcJvmWorkerProtocolTest {
  private fun startWorker(mainClass: String, vararg args: String): Process =
    ProcessBuilder(
        listOf(
          System.getProperty("java.home") + "/bin/java",
          "--enable-native-access=ALL-UNNAMED",
          "-Dapple.awt.UIElement=true",
          "-cp",
          System.getProperty("java.class.path"),
          mainClass,
        ) + args
      )
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

  private fun resource(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }

  @Test
  fun aWorkerGreetsRendersAndExitsWhenItsStdinCloses() {
    val worker = startWorker("ee.schimke.composeai.rcjvm.RcJvmRenderWorkerMainKt")
    try {
      val input = DataInputStream(worker.inputStream.buffered())
      val output = DataOutputStream(worker.outputStream.buffered())

      assertThat(input.readInt()).isEqualTo(MAGIC_HELLO)
      assertThat(input.readInt()).isEqualTo(PROTOCOL_VERSION)

      repeat(2) { request ->
        val doc = resource("textbutton.rc")
        output.writeInt(MAGIC_REQUEST)
        output.writeInt(41 + request)
        output.writeInt(454)
        output.writeInt(200)
        output.writeInt(2f.toBits())
        output.writeInt(WIRE_FORMAT_PNG)
        output.writeInt(WIRE_THEME_LIGHT)
        output.writeInt(0) // no seeds
        output.writeInt(doc.size)
        output.write(doc)
        output.flush()

        assertThat(input.readInt()).isEqualTo(MAGIC_RESPONSE)
        assertThat(input.readInt()).isEqualTo(41 + request)
        assertThat(input.readInt()).isEqualTo(STATUS_OK)
        val payload = ByteArray(input.readInt()).also { input.readFully(it) }
        // PNG signature: a warm worker answers a second document on the same process.
        assertThat(payload.take(4))
          .containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
          .inOrder()
      }

      output.close()
      assertThat(worker.waitFor(30, TimeUnit.SECONDS)).isTrue()
      assertThat(worker.exitValue()).isEqualTo(0)
    } finally {
      worker.destroyForcibly()
    }
  }

  @Test
  fun aDocumentThePlayerCannotDecodeFailsTheRequestNotTheWorker() {
    val worker = startWorker("ee.schimke.composeai.rcjvm.RcJvmRenderWorkerMainKt")
    try {
      val input = DataInputStream(worker.inputStream.buffered())
      val output = DataOutputStream(worker.outputStream.buffered())
      input.readInt()
      input.readInt()

      val garbage = ByteArray(64) { it.toByte() }
      output.writeInt(MAGIC_REQUEST)
      output.writeInt(7)
      output.writeInt(100)
      output.writeInt(100)
      output.writeInt(2f.toBits())
      output.writeInt(WIRE_FORMAT_PNG)
      output.writeInt(WIRE_THEME_LIGHT)
      output.writeInt(0)
      output.writeInt(garbage.size)
      output.write(garbage)
      output.flush()

      assertThat(input.readInt()).isEqualTo(MAGIC_RESPONSE)
      assertThat(input.readInt()).isEqualTo(7)
      assertThat(input.readInt()).isEqualTo(STATUS_FAILED)
      val reason = String(ByteArray(input.readInt()).also { input.readFully(it) })
      assertThat(reason).isNotEmpty()
      assertThat(worker.isAlive).isTrue()
    } finally {
      worker.destroyForcibly()
    }
  }

  @Test
  fun theOneShotEntryPointRejectsMissingArguments() {
    val process = startWorker("ee.schimke.composeai.rcjvm.RcJvmRenderMainKt")
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
    assertThat(process.exitValue()).isEqualTo(2)
  }
}
