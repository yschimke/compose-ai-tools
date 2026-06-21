package ee.schimke.composeai.fakeemulator

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for ADB host connections and runs an [AdbConnection] per socket. Bind to port 0 for an
 * ephemeral port (read it back from [boundPort]); the emulator convention is the odd port paired
 * with an even console port (e.g. console 5554 ↔ adb 5555).
 */
class AdbTransportServer(
  private val requestedPort: Int,
  private val banner: ByteArray,
  private val resolver: AdbServiceResolver,
  private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
) : AutoCloseable {
  private val running = AtomicBoolean(false)
  private val threads = Executors.newCachedThreadPool { r ->
    Thread(r, "fake-adb").apply { isDaemon = true }
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
        socket.tcpNoDelay = true
        val connection = AdbConnection(socket, banner, resolver) { threads.execute(it) }
        threads.execute { connection.run() }
      }
    }
  }

  override fun close() {
    if (running.compareAndSet(true, false)) {
      runCatching { serverSocket.close() }
      threads.shutdownNow()
    }
  }
}
