package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Test

/**
 * `sync: SEND` must capture the pushed APK into the [ApkStore] so `pm install <path>` can use it.
 */
class SyncServiceTest {
  @Test
  fun `SEND captures the pushed file bytes under its path`() {
    val store = ApkStore()
    val apk = ApkFixtures.apk("com.example.pushed")
    val path = "/data/local/tmp/base.apk"

    val io = ScriptedStreamIo()
    io.feed(sendFrames(path, apk))
    SyncService(store).run(io)

    assertThat(store.takePushedFile(path)).isEqualTo(apk)
    // The service OKAY'd the transfer.
    assertThat(String(io.written(), Charsets.US_ASCII)).contains("OKAY")
  }

  /**
   * Encodes `SEND <path,mode>` + one `DATA` chunk + `DONE` as the sync protocol frames the client
   * sends.
   */
  private fun sendFrames(path: String, payload: ByteArray): ByteArray {
    val spec = "$path,33188".toByteArray(Charsets.US_ASCII)
    val out = ByteArrayOutputStream()
    out.write("SEND".toByteArray(Charsets.US_ASCII))
    out.write(le32(spec.size))
    out.write(spec)
    out.write("DATA".toByteArray(Charsets.US_ASCII))
    out.write(le32(payload.size))
    out.write(payload)
    out.write("DONE".toByteArray(Charsets.US_ASCII))
    out.write(le32(0)) // mtime
    return out.toByteArray()
  }

  private fun le32(v: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

  /** An [AdbStreamIo] that replays scripted inbound chunks and records everything written. */
  private class ScriptedStreamIo : AdbStreamIo {
    override val destination = "sync:"
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val ended = ByteArray(0)
    private val out = ByteArrayOutputStream()

    fun feed(bytes: ByteArray) {
      inbound.offer(bytes)
      inbound.offer(ended) // signal EOF after the scripted bytes
    }

    override fun write(bytes: ByteArray) {
      out.write(bytes)
    }

    override fun read(): ByteArray? {
      val next = inbound.take()
      return if (next === ended) null else next
    }

    override fun close() {}

    fun written(): ByteArray = out.toByteArray()
  }
}
