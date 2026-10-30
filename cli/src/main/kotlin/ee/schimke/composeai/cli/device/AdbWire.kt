package ee.schimke.composeai.cli.device

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The adb transport protocol as `adbd` speaks it (AOSP `packages/modules/adb/protocol.txt`):
 * 24-byte little-endian headers — command, two arguments, payload length, payload checksum, and the
 * command inverted — each followed by its payload.
 */
internal object AdbWire {

  const val A_CNXN = 0x4e584e43
  const val A_OPEN = 0x4e45504f
  const val A_OKAY = 0x59414b4f
  const val A_CLSE = 0x45534c43
  const val A_WRTE = 0x45545257
  const val A_AUTH = 0x48545541
  const val A_STLS = 0x534c5453

  /** Version 0x01000001 drops the payload checksum; adb has sent it since 2017. */
  const val VERSION = 0x01000001

  /** The largest payload this device takes and sends (adbd's own `MAX_PAYLOAD`). */
  const val MAX_PAYLOAD = 256 * 1024

  class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = EMPTY) {
    override fun toString(): String = "${name(command)}($arg0, $arg1, ${data.size} bytes)"
  }

  val EMPTY = ByteArray(0)

  fun read(input: DataInputStream): Message {
    val header = ByteArray(24)
    input.readFully(header)
    val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val command = b.int
    val arg0 = b.int
    val arg1 = b.int
    val length = b.int
    b.int // checksum: not checked, as adbd does not past VERSION
    val magic = b.int
    if (magic != command.inv()) throw EOFException("bad adb header magic for ${name(command)}")
    if (length < 0 || length > MAX_PAYLOAD * 4) throw EOFException("adb payload of $length bytes")
    val data = ByteArray(length)
    input.readFully(data)
    return Message(command, arg0, arg1, data)
  }

  fun write(output: OutputStream, message: Message) {
    val b = ByteBuffer.allocate(24 + message.data.size).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(message.command).putInt(message.arg0).putInt(message.arg1)
    b.putInt(message.data.size).putInt(checksum(message.data)).putInt(message.command.inv())
    b.put(message.data)
    output.write(b.array())
    output.flush()
  }

  /** Older hosts (before [VERSION]) still check it; it costs nothing to send. */
  fun checksum(data: ByteArray): Int {
    var sum = 0
    for (byte in data) sum += byte.toInt() and 0xff
    return sum
  }

  fun name(command: Int): String {
    val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array()
    return String(b, Charsets.US_ASCII)
  }

  /** A service name from an `OPEN` payload, without its trailing NUL. */
  fun service(data: ByteArray): String = String(data, Charsets.UTF_8).trimEnd('\u0000')

  /** Shell protocol v2 packet ids (`shell_protocol.h`). */
  object ShellV2 {
    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val CLOSE_STDIN = 4
    const val WINDOW_SIZE_CHANGE = 5

    fun packet(id: Int, data: ByteArray): ByteArray {
      val b = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
      b.put(id.toByte()).putInt(data.size).put(data)
      return b.array()
    }
  }
}

/** Little-endian reads over a byte stream, for the sync protocol. */
internal class LeReader(private val input: InputStream) {
  fun bytes(n: Int): ByteArray {
    val out = ByteArray(n)
    var read = 0
    while (read < n) {
      val r = input.read(out, read, n - read)
      if (r < 0) throw EOFException()
      read += r
    }
    return out
  }

  fun int(): Int = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int

  fun id(): String = String(bytes(4), Charsets.US_ASCII)
}
