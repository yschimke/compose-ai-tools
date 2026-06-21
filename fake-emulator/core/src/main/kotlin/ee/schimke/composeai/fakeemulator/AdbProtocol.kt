package ee.schimke.composeai.fakeemulator

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ADB transport ("smart socket") wire format — the protocol an adbd device speaks to an adb
 * host. We implement the device side so a host (`adb connect`, dadb, Adam) talks to us directly.
 *
 * Every packet is a 24-byte little-endian header optionally followed by a payload:
 * ```
 * struct message {
 *   u32 command;      // A_CNXN / A_OPEN / A_OKAY / A_WRTE / A_CLSE / A_AUTH / A_STLS
 *   u32 arg0;
 *   u32 arg1;
 *   u32 data_length;  // payload length
 *   u32 data_check;   // sum of payload bytes (modern adb ignores it; we still write it)
 *   u32 magic;        // command ^ 0xffffffff
 * };
 * ```
 *
 * See AOSP `system/core/adb/protocol.txt`.
 */
internal object AdbProtocol {
  // Command constants are the little-endian u32 reading of the 4 ASCII bytes (e.g. "CNXN").
  const val A_CNXN = 0x4e584e43
  const val A_OPEN = 0x4e45504f
  const val A_OKAY = 0x59414b4f
  const val A_CLSE = 0x45534c43
  const val A_WRTE = 0x45545257
  const val A_AUTH = 0x48545541
  const val A_STLS = 0x534c5453

  /** Protocol version we advertise in the CNXN handshake. */
  const val A_VERSION = 0x01000000

  /** Max payload we accept / advertise. The effective chunk size is min(ours, peer's). */
  const val MAX_PAYLOAD = 256 * 1024

  fun checksum(payload: ByteArray): Int {
    var sum = 0
    for (b in payload) sum += b.toInt() and 0xff
    return sum
  }

  /** Read one message from [input], or `null` on a clean EOF before any header byte. */
  fun read(input: InputStream): AdbMessage? {
    val data = DataInputStream(input)
    val header = ByteArray(24)
    var read = 0
    while (read < 24) {
      val n = data.read(header, read, 24 - read)
      if (n < 0) {
        if (read == 0) return null
        throw EOFException("truncated adb header ($read/24 bytes)")
      }
      read += n
    }
    val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val command = bb.int
    val arg0 = bb.int
    val arg1 = bb.int
    val length = bb.int
    bb.int // data_check — ignored, modern adb leaves it 0
    bb.int // magic — ignored
    val payload =
      if (length > 0) {
        require(length <= 64 * 1024 * 1024) { "implausible adb payload length: $length" }
        ByteArray(length).also { data.readFully(it) }
      } else {
        EMPTY
      }
    return AdbMessage(command, arg0, arg1, payload)
  }

  /** Serialize [message] onto [output]. Caller must hold the connection's write lock. */
  fun write(output: OutputStream, message: AdbMessage) {
    val payload = message.payload
    val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(message.command)
    bb.putInt(message.arg0)
    bb.putInt(message.arg1)
    bb.putInt(payload.size)
    bb.putInt(checksum(payload))
    bb.putInt(message.command.inv())
    output.write(bb.array())
    if (payload.isNotEmpty()) output.write(payload)
    output.flush()
  }

  val EMPTY = ByteArray(0)
}

/** One decoded ADB transport packet. [payload] is empty (not null) when there is no body. */
internal class AdbMessage(
  val command: Int,
  val arg0: Int,
  val arg1: Int,
  val payload: ByteArray = ByteArray(0),
)
