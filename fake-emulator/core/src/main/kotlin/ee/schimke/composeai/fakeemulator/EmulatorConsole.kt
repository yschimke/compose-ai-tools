package ee.schimke.composeai.fakeemulator

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The emulator console — the small line-oriented text protocol a real emulator serves on its even
 * console port (e.g. 5554). Classic `adb`-emulator auto-detection probes this port; pairing it with
 * the odd adb port (5555) is what makes a device appear as `emulator-5554`. We answer un-authed and
 * implement just the handful of commands detection and tooling use.
 */
class EmulatorConsole(
  private val requestedPort: Int,
  private val avdName: String,
  private val onKill: () -> Unit = {},
  private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
) : AutoCloseable {
  private val running = AtomicBoolean(false)
  private val threads = Executors.newCachedThreadPool { r ->
    Thread(r, "fake-console").apply { isDaemon = true }
  }
  private lateinit var serverSocket: ServerSocket

  val boundPort: Int
    get() = serverSocket.localPort

  fun start() {
    check(running.compareAndSet(false, true)) { "already started" }
    serverSocket = ServerSocket(requestedPort, 50, bindAddress)
    threads.execute {
      while (running.get()) {
        val socket =
          try {
            serverSocket.accept()
          } catch (_: Exception) {
            break
          }
        threads.execute { serve(socket) }
      }
    }
  }

  private fun serve(socket: java.net.Socket) {
    socket.use {
      val out = socket.getOutputStream()
      val reader =
        BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
      fun send(text: String) {
        out.write(text.toByteArray(StandardCharsets.UTF_8))
        out.flush()
      }
      send(
        "Android Console: Copyright (C) Compose AI Tools fake emulator\r\n" +
          "Android Console: type 'help' for a list of commands\r\n" +
          "OK\r\n"
      )
      while (running.get()) {
        val line = reader.readLine() ?: break
        when (val command = line.trim()) {
          "" -> send("OK\r\n")
          "help",
          "?" -> send(HELP + "OK\r\n")
          "avd name" -> send("$avdName\r\nOK\r\n")
          "avd status" -> send("virtual device is running\r\nOK\r\n")
          "redir list" -> send("OK\r\n")
          "quit",
          "exit" -> {
            send("OK\r\n")
            return
          }
          "kill" -> {
            send("OK: killing emulator, bye bye\r\n")
            onKill()
            return
          }
          else ->
            if (command.startsWith("auth ")) send("OK\r\n")
            else if (command.startsWith("avd ")) send("OK\r\n")
            else send("KO: unknown command, try 'help'\r\n")
        }
      }
    }
  }

  override fun close() {
    if (running.compareAndSet(true, false)) {
      runCatching { serverSocket.close() }
      threads.shutdownNow()
    }
  }

  private companion object {
    val HELP = buildString {
      append("Android console command help:\r\n")
      append("    help|?           print a list of commands\r\n")
      append("    avd name         query virtual device name\r\n")
      append("    kill             kill the emulator instance\r\n")
      append("    quit|exit        quit this console session\r\n")
    }
  }
}
