package ee.schimke.composeai.cli

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The simulated Wearable Data Layer's hub for `launch --pair`: a localhost socket each paired
 * simulator's `DataLayer` connects to, relaying every frame (a big-endian length, then one JSON
 * object) to the others. A device pair's Play services do the same over Bluetooth; here both apps
 * run in their own simulator JVM, so the hub is the only shared piece.
 *
 * The hub reads only a `hello`'s `from`, to tell the others with a `bye` when that node's simulator
 * goes away; everything else a frame means is the simulators' business.
 */
internal class DataLayerHub(private val log: (String) -> Unit = {}) : AutoCloseable {

  private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
  private val connections = CopyOnWriteArrayList<Connection>()

  val port: Int
    get() = server.localPort

  /** The simulators connected now. */
  internal val connected: Int
    get() = connections.size

  private inner class Connection(val socket: Socket) {
    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    @Volatile var node: String? = null

    fun send(bytes: ByteArray) {
      synchronized(out) {
        try {
          out.writeInt(bytes.size)
          out.write(bytes)
          out.flush()
        } catch (_: IOException) {
          // Its reader notices the socket is gone and removes it.
        }
      }
    }
  }

  fun start(): DataLayerHub {
    thread("datalayer-hub") {
      while (!server.isClosed) {
        val socket =
          try {
            server.accept()
          } catch (_: IOException) {
            return@thread
          }
        socket.tcpNoDelay = true
        val connection = Connection(socket)
        connections += connection
        thread("datalayer-hub-peer") { relay(connection) }
      }
    }
    return this
  }

  private fun relay(from: Connection) {
    try {
      DataInputStream(BufferedInputStream(from.socket.getInputStream())).use { input ->
        while (true) {
          val length = input.readInt()
          if (length < 0 || length > MAX_FRAME) throw IOException("bad frame length $length")
          val bytes = ByteArray(length)
          input.readFully(bytes)
          if (from.node == null) from.node = helloFrom(bytes)
          log(summary(bytes))
          for (to in connections) if (to !== from) to.send(bytes)
        }
      }
    } catch (_: EOFException) {
      // The simulator ended.
    } catch (e: IOException) {
      log("connection failed: $e")
    } finally {
      connections -= from
      runCatching { from.socket.close() }
      from.node?.let { node ->
        val bye = """{"type":"bye","from":${Json.encodeToString(node)}}""".toByteArray()
        log("$node left")
        for (to in connections) to.send(bye)
      }
    }
  }

  override fun close() {
    runCatching { server.close() }
    for (connection in connections) runCatching { connection.socket.close() }
  }

  companion object {
    /** As the simulator side's `DataLayerLink.MAX_FRAME`. */
    const val MAX_FRAME = 64 * 1024 * 1024

    private fun parse(bytes: ByteArray): JsonObject? = runCatching {
      Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    }
      .getOrNull()

    /** The node a `hello` frame announces, or null for any other frame. */
    internal fun helloFrom(bytes: ByteArray): String? {
      val frame = parse(bytes) ?: return null
      if (frame["type"]?.jsonPrimitive?.content != "hello") return null
      return frame["from"]?.jsonPrimitive?.content
    }

    /** One line about a frame, for `--verbose`: type, from → to, path. */
    internal fun summary(bytes: ByteArray): String {
      val frame = parse(bytes) ?: return "unreadable frame of ${bytes.size} bytes"
      fun field(name: String) = frame[name]?.jsonPrimitive?.content
      return buildString {
        append(field("type"))
        append(' ')
        append(field("from"))
        field("to")?.let { append(" → ").append(it) }
        (field("path") ?: field("uri"))?.let { append(' ').append(it) }
        append(" (${bytes.size} bytes)")
      }
    }

    private fun thread(name: String, body: () -> Unit) =
      Thread(body, name).apply {
        isDaemon = true
        start()
      }
  }
}
