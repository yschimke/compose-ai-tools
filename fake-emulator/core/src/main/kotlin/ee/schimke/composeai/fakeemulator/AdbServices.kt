package ee.schimke.composeai.fakeemulator

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Maps OPEN destinations to the handful of device services we implement. */
class EmulatorAdbServices(private val interpreter: ShellInterpreter) : AdbServiceResolver {
  override fun resolve(destination: String): AdbService? =
    when {
      destination.startsWith("shell") -> ShellService(interpreter, destination)
      destination.startsWith("sync:") -> SyncService()
      // framebuffer:/reverse:/jdwp:/tcp: aren't implemented — screencap is the screenshot path.
      else -> null
    }
}

/**
 * Handles both `shell:` (legacy, raw byte stream) and `shell,v2:` (framed: id+len+payload, with a
 * trailing exit packet). The destination may carry options between the first comma and the colon
 * (`shell,v2,raw:`, `shell,v2,TERM=xterm:`); the command is everything after the first colon.
 */
class ShellService(private val interpreter: ShellInterpreter, private val destination: String) :
  AdbService {
  override fun run(io: AdbStreamIo) {
    val colon = destination.indexOf(':')
    val options = if (colon >= 0) destination.substring(0, colon) else destination
    val command = if (colon >= 0) destination.substring(colon + 1) else ""
    val v2 = options.split(',').contains("v2")

    val result = interpreter.execute(command)
    if (v2) {
      writeShellV2(io, ID_STDOUT, result.stdout)
      writeShellV2(io, ID_STDERR, result.stderr)
      writeShellV2(io, ID_EXIT, byteArrayOf(result.exitCode.toByte()))
    } else {
      if (result.stdout.isNotEmpty()) io.write(result.stdout)
      if (result.stderr.isNotEmpty()) io.write(result.stderr)
    }
  }

  private fun writeShellV2(io: AdbStreamIo, id: Int, payload: ByteArray) {
    if (id != ID_EXIT && payload.isEmpty()) return
    var offset = 0
    do {
      val end = (offset + PACKET_CHUNK).coerceAtMost(payload.size)
      val slice = payload.copyOfRange(offset, end)
      val header =
        ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(slice.size)
      io.write(header.array() + slice)
      offset = end
    } while (offset < payload.size)
  }

  private companion object {
    const val ID_STDOUT = 1
    const val ID_STDERR = 2
    const val ID_EXIT = 3
    const val PACKET_CHUNK = 64 * 1024
  }
}

/**
 * Minimal ADB sync service (`sync:`). Enough that file-transfer-shaped flows (e.g. an install
 * pushing an APK) don't hang: STAT reports "not present", SEND is consumed and OKAY'd, RECV fails
 * cleanly, QUIT ends. Full sync (real pull, v2 stat/list) is out of scope — see DESIGN.md.
 */
class SyncService : AdbService {
  override fun run(io: AdbStreamIo) {
    val input = AdbStreamInputStream(io)
    while (true) {
      val id = readId(input) ?: return
      val arg = readLe32(input) ?: return
      when (id) {
        "STAT" -> {
          skip(input, arg) // path
          io.write("STAT".toByteArray(US_ASCII) + le32(0) + le32(0) + le32(0))
        }
        "LIST" -> {
          skip(input, arg) // path
          io.write("DONE".toByteArray(US_ASCII) + ByteArray(16))
        }
        "SEND" -> {
          skip(input, arg) // "path,mode"
          drainSend(input)
          io.write("OKAY".toByteArray(US_ASCII) + le32(0))
        }
        "RECV" -> {
          skip(input, arg) // path
          val msg = "not supported".toByteArray(US_ASCII)
          io.write("FAIL".toByteArray(US_ASCII) + le32(msg.size) + msg)
          return
        }
        "QUIT" -> return
        else -> return
      }
    }
  }

  /** Consume DATA chunks until DONE (whose arg is the mtime, not a length). */
  private fun drainSend(input: InputStream) {
    while (true) {
      val id = readId(input) ?: return
      val arg = readLe32(input) ?: return
      when (id) {
        "DATA" -> skip(input, arg)
        "DONE" -> return
        else -> return
      }
    }
  }

  private fun readId(input: InputStream): String? {
    val b = ByteArray(4)
    if (!readFully(input, b)) return null
    return String(b, US_ASCII)
  }

  private fun readLe32(input: InputStream): Int? {
    val b = ByteArray(4)
    if (!readFully(input, b)) return null
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
  }

  private fun le32(value: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

  private fun skip(input: InputStream, count: Int) {
    var remaining = count
    val buf = ByteArray(8192)
    while (remaining > 0) {
      val n = input.read(buf, 0, remaining.coerceAtMost(buf.size))
      if (n < 0) return
      remaining -= n
    }
  }

  private fun readFully(input: InputStream, b: ByteArray): Boolean {
    var read = 0
    while (read < b.size) {
      val n = input.read(b, read, b.size - read)
      if (n < 0) return false
      read += n
    }
    return true
  }

  private companion object {
    val US_ASCII = StandardCharsets.US_ASCII
  }
}

/**
 * Adapts the chunked [AdbStreamIo.read] into a blocking [InputStream] for byte-oriented services.
 */
class AdbStreamInputStream(private val io: AdbStreamIo) : InputStream() {
  private var buffer: ByteArray = ByteArray(0)
  private var position = 0

  override fun read(): Int {
    if (!ensure()) return -1
    return buffer[position++].toInt() and 0xff
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (len == 0) return 0
    if (!ensure()) return -1
    val n = (buffer.size - position).coerceAtMost(len)
    System.arraycopy(buffer, position, b, off, n)
    position += n
    return n
  }

  private fun ensure(): Boolean {
    while (position >= buffer.size) {
      val next = io.read() ?: return false
      buffer = next
      position = 0
    }
    return true
  }
}
