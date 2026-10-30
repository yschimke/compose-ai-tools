package ee.schimke.composeai.cli.device

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.serialization.json.JsonPrimitive

/**
 * The emulator console on the device's even port (`telnet localhost 5554`, `adb emu …`): the
 * commands tools read the device's identity through (`avd name`, `avd path`, `avd id`) and the few
 * that have a simulator equivalent — `geo fix`, `power capacity` / `power ac`, `rotate` — and
 * `kill`. Lines end `\r\n`, every answer with `OK` or `KO: …`, as the emulator's do. Any `auth`
 * token is accepted: the console only listens on loopback.
 */
internal class EmulatorConsole(
  private val device: VirtualDevice,
  private val avdPath: String,
  private val discoveryPath: () -> String?,
) : AutoCloseable {

  private val server = ServerSocket(device.config.consolePort, 10, InetAddress.getLoopbackAddress())

  fun start(): EmulatorConsole {
    Thread(
        {
          while (!server.isClosed) {
            val socket =
              try {
                server.accept()
              } catch (_: IOException) {
                break
              }
            Thread({ session(socket) }, "console").apply {
              isDaemon = true
              start()
            }
          }
        },
        "console-accept",
      )
      .apply {
        isDaemon = true
        start()
      }
    return this
  }

  private fun session(socket: Socket) = socket.use {
    val out = socket.getOutputStream().bufferedWriter()
    fun send(text: String) {
      out.write(text.replace("\n", "\r\n"))
      out.write("\r\n")
      out.flush()
    }
    send("Android Console: type 'help' for a list of commands\nOK")
    socket.getInputStream().bufferedReader().useLines { lines ->
      for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line == "quit" || line == "exit") return@use
        val answer =
          try {
            answer(line.split(Regex("\\s+")))
          } catch (e: Exception) {
            "KO: ${e.message ?: e}"
          }
        send(answer)
        if (line == "kill") {
          device.stop()
          return@use
        }
      }
    }
  }

  /** One command's answer, ending `OK` or `KO: …`. */
  internal fun answer(words: List<String>): String =
    when (words[0]) {
      "auth" -> "OK"
      "ping" -> "I am alive!\nOK"
      "help" -> "Android console commands:\n\n    avd, geo, power, rotate, ping, kill, quit\nOK"
      "kill" -> "OK: killing emulator, bye bye"
      "avd" ->
        when (words.getOrNull(1)) {
          "name" -> "${device.config.avdName}\nOK"
          "id" -> "${device.config.avdName}\nOK"
          "path" -> "$avdPath\nOK"
          "discoverypath" -> discoveryPath()?.let { "$it\nOK" } ?: "KO: no discovery file"
          "status" -> "virtual device is running\nOK"
          else -> "KO: avd ${words.getOrNull(1) ?: ""} is not supported by the simulator"
        }
      "geo" -> {
        if (words.getOrNull(1) != "fix" || words.size < 4)
          "KO: usage: geo fix <longitude> <latitude> [<altitude>]"
        else {
          val args = buildList {
            add("lng" to JsonPrimitive(words[2].toDouble()))
            add("lat" to JsonPrimitive(words[3].toDouble()))
            words.getOrNull(4)?.toDoubleOrNull()?.let { add("altitude" to JsonPrimitive(it)) }
          }
          device.simulator().request("location", *args.toTypedArray())
          "OK"
        }
      }
      "power" ->
        when (words.getOrNull(1)) {
          "capacity" -> {
            device.simulator().request("battery", "level" to JsonPrimitive(words[2].toInt()))
            "OK"
          }
          "ac" -> {
            val on = words.getOrNull(2) == "on"
            device
              .simulator()
              .request("battery", "plugged" to JsonPrimitive(if (on) "ac" else "none"))
            "OK"
          }
          else -> "KO: power ${words.getOrNull(1) ?: ""} is not supported by the simulator"
        }
      "rotate" -> {
        device.simulator().request("rotate")
        "OK"
      }
      else -> "KO: unknown command, try 'help'"
    }

  override fun close() {
    runCatching { server.close() }
  }
}
