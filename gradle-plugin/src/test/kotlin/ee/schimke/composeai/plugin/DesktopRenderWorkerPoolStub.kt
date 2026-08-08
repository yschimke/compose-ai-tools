package ee.schimke.composeai.plugin

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * A stand-in for `DesktopRendererWorkerMain` that speaks the same frames without Compose or Skiko,
 * so [DesktopRenderWorkerPoolTest] can exercise the pool's protocol, recycling and failure handling
 * on any machine — including CI images with no native render stack, where the real worker cannot
 * start at all.
 *
 * `stub.mode` selects the behaviour: `ok` (default) writes the file named by the last argv entry
 * and reports success, `failed` reports a render failure, `hang` never answers, `crash` exits
 * mid-request, and `badVersion` sends an unrecognised protocol version.
 *
 * On success it writes `"<argc>:<seed>#<n>"` into the output file, where `n` counts the requests
 * this process has served — which is how a test tells a reused warm worker from a fresh one.
 */
object DesktopRenderWorkerPoolStub {

  @JvmStatic
  fun main(args: Array<String>) {
    val mode = System.getProperty("stub.mode", "ok")
    val frames = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
    val input = DataInputStream(System.`in`.buffered())

    frames.writeInt(DesktopRenderWorkerPool.MAGIC_HELLO)
    frames.writeInt(
      if (mode == "badVersion") 99 else DesktopRenderWorkerPool.WORKER_PROTOCOL_VERSION
    )
    frames.flush()

    var served = 0
    while (true) {
      val magic =
        try {
          input.readInt()
        } catch (_: EOFException) {
          exitProcess(0)
        }
      if (magic != DesktopRenderWorkerPool.MAGIC_REQUEST) exitProcess(4)

      val requestId = input.readInt()
      val seed = String(input.readPayload(), Charsets.UTF_8)
      val argc = input.readInt()
      val argv = List(argc) { String(input.readPayload(), Charsets.UTF_8) }

      when (mode) {
        "hang" -> Thread.sleep(Long.MAX_VALUE)
        "crash" -> exitProcess(9)
      }

      served++
      var status = DesktopRenderWorkerPool.STATUS_OK
      var message = ""
      if (mode == "failed") {
        status = 1
        message = "stub refused request $requestId"
      } else {
        // Last argv entry is the renderer's output path in the real protocol.
        argv.lastOrNull()?.let { File(it).writeText("$argc:$seed#$served") }
      }

      val payload = message.toByteArray(Charsets.UTF_8)
      frames.writeInt(DesktopRenderWorkerPool.MAGIC_RESPONSE)
      frames.writeInt(requestId)
      frames.writeInt(status)
      frames.writeInt(payload.size)
      frames.write(payload)
      frames.flush()
    }
  }

  private fun DataInputStream.readPayload(): ByteArray {
    val len = readInt()
    return ByteArray(len).also { readFully(it) }
  }
}
