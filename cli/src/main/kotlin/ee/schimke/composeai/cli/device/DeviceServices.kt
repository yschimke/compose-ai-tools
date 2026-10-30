package ee.schimke.composeai.cli.device

import ee.schimke.composeai.cli.device.AdbWire.ShellV2
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The services the virtual device's adb transport opens, by name, as `adbd`'s `service_to_fd`
 * dispatches them. A name it does not serve (`jdwp:`, `reverse:`, `framebuffer:`, `tcpip:`) is
 * refused, which adb reports as the device closing the stream.
 */
internal class DeviceServices(private val device: VirtualDevice) {

  private val sync = SyncService(device.storage)

  fun resolve(name: String): AdbService? {
    val colon = name.indexOf(':')
    if (colon < 0) return null
    val service = name.substring(0, colon)
    val argument = name.substring(colon + 1)
    return when {
      service == "shell" -> AdbService { shellV1(argument, it) }
      service.startsWith("shell,") -> {
        val options = service.split(',').drop(1)
        if ("v2" in options) AdbService { shellV2(argument, "pty" in options, it) }
        else AdbService { shellV1(argument, it) }
      }
      service == "exec" ->
        AdbService { stream ->
          device.shell.run(argument, stream.input, ShellOutput(stream.output, stream.output))
        }
      service == "abb_exec" ->
        AdbService { stream ->
          val args = argument.split('\u0000').filter { it.isNotEmpty() }
          val line =
            if (args.firstOrNull() == "package")
              (listOf("pm") + args.drop(1)).joinToString(" ") { quote(it) }
            else (listOf("cmd") + args).joinToString(" ") { quote(it) }
          device.shell.run(line, stream.input, ShellOutput(stream.output, stream.output))
        }
      service == "sync" -> sync
      service == "reboot" -> AdbService { device.reboot() }
      service == "root" -> AdbService { it.write("adbd is already running as root\n") }
      service == "unroot" -> AdbService { it.write("adbd not running as root\n") }
      service == "remount" ->
        AdbService { it.write("remount is not supported: the simulator has no system partition\n") }
      service == "track-jdwp" || service == "track-app" ->
        AdbService { stream ->
          // No debuggable processes to report; the host keeps the stream open and closes it.
          if (service == "track-jdwp") stream.write("0000")
          while (stream.input.read() >= 0) Unit
        }
      else -> null
    }
  }

  /** `shell:` — stdout and stderr on the one stream, no exit code; interactive when empty. */
  private fun shellV1(command: String, stream: AdbDeviceServer.AdbStream) {
    val out = ShellOutput(stream.output, stream.output)
    if (command.isBlank()) interactive(stream.input, out)
    else device.shell.run(command, stream.input, out)
  }

  /** `shell,v2:` — the shell protocol: packets on both directions, and an exit packet last. */
  private fun shellV2(command: String, pty: Boolean, stream: AdbDeviceServer.AdbStream) {
    val stdin = ShellV2Input(stream.input)
    val stdout = PacketOutput(stream, ShellV2.STDOUT)
    // A pty has one output, as a terminal does.
    val stderr = if (pty) stdout else PacketOutput(stream, ShellV2.STDERR)
    val out = ShellOutput(stdout, stderr)
    val exit =
      if (command.isBlank()) interactive(stdin, out) else device.shell.run(command, stdin, out)
    stdout.flush()
    stderr.flush()
    stream.write(ShellV2.packet(ShellV2.EXIT, byteArrayOf(exit.toByte())))
  }

  /** A shell with a prompt, echoing what is typed as a terminal on a pty does. */
  private fun interactive(input: InputStream, out: ShellOutput): Int {
    val prompt = "${device.config.serial}:/ $ "
    out.write(prompt.toByteArray())
    out.stdout.flush()
    val line = StringBuilder()
    var exit = 0
    while (true) {
      val c = input.read()
      if (c < 0 || (c == 4 && line.isEmpty())) return exit
      when (c) {
        '\r'.code,
        '\n'.code -> {
          out.write("\r\n".toByteArray())
          val text = line.toString().trim()
          line.clear()
          if (text == "exit") return exit
          if (text.isNotEmpty())
            exit = device.shell.run(text, InputStream.nullInputStream(), crlf(out))
          out.write(prompt.toByteArray())
        }
        0x7f,
        0x08 ->
          if (line.isNotEmpty()) {
            line.setLength(line.length - 1)
            out.write("\b \b".toByteArray())
          }
        3 -> {
          line.clear()
          out.write("^C\r\n$prompt".toByteArray())
        }
        else ->
          if (c >= 0x20) {
            line.append(c.toChar())
            out.write(byteArrayOf(c.toByte()))
          }
      }
      out.stdout.flush()
    }
  }

  /** A terminal in raw mode wants `\r\n`. */
  private fun crlf(out: ShellOutput): ShellOutput {
    fun wrap(target: OutputStream) =
      object : OutputStream() {
        override fun write(b: Int) {
          if (b == '\n'.code) target.write('\r'.code)
          target.write(b)
        }
      }
    return ShellOutput(wrap(out.stdout), wrap(out.stderr))
  }

  /** Buffers one stream's output into shell-protocol packets. */
  private class PacketOutput(private val stream: AdbDeviceServer.AdbStream, private val id: Int) :
    OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
      buffer.write(b)
      if (buffer.size() >= 32 * 1024) flush()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      buffer.write(b, off, len)
      if (buffer.size() >= 32 * 1024) flush()
    }

    override fun flush() {
      if (buffer.size() == 0) return
      stream.write(ShellV2.packet(id, buffer.toByteArray()))
      buffer.reset()
    }
  }

  /** The stdin packets of the shell protocol, as a stream; ends at `CLOSE_STDIN`. */
  private class ShellV2Input(private val raw: InputStream) : InputStream() {
    private val reader = LeReader(raw)
    private var current = ByteArray(0)
    private var at = 0
    private var ended = false

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      while (at >= current.size) {
        if (ended) return -1
        val id =
          try {
            reader.bytes(1)[0].toInt()
          } catch (_: java.io.EOFException) {
            ended = true
            return -1
          }
        val data = reader.bytes(reader.int())
        when (id) {
          ShellV2.STDIN -> {
            current = data
            at = 0
          }
          ShellV2.CLOSE_STDIN -> ended = true
          else -> Unit // window size changes
        }
      }
      val n = minOf(len, current.size - at)
      System.arraycopy(current, at, b, off, n)
      at += n
      return n
    }
  }

  private companion object {
    /** Quotes an `abb_exec` argument back into a shell word. */
    fun quote(arg: String): String =
      if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./:=,+@%" }) arg
      else "'" + arg.replace("'", "'\\''") + "'"
  }
}
