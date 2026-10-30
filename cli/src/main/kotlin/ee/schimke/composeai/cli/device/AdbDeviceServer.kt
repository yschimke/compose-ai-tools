package ee.schimke.composeai.cli.device

import ee.schimke.composeai.cli.device.AdbWire.Message
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device end of the adb transport, as `adbd` (or an emulator's `adbd` behind its port 5555)
 * presents it: a host's adb server connects, the two exchange `CNXN`, and each `OPEN` names a
 * service (`shell:…`, `exec:…`, `sync:`, `abb_exec:…`) that [services] runs on a stream of its own.
 *
 * No `AUTH`: the device answers the host's `CNXN` with its own, as a device with `ro.adb.secure=0`
 * does, and it only listens on loopback.
 */
internal class AdbDeviceServer(
  val port: Int,
  private val banner: () -> String,
  private val services: (String) -> AdbService?,
  private val log: (String) -> Unit = {},
) : AutoCloseable {

  private val server = ServerSocket(port, 50, InetAddress.getLoopbackAddress())
  private val connections = ConcurrentHashMap.newKeySet<Socket>()

  fun start(): AdbDeviceServer {
    thread("adb-accept-$port") {
      while (!server.isClosed) {
        val socket =
          try {
            server.accept()
          } catch (_: IOException) {
            break
          }
        connections += socket
        thread("adb-conn-$port") {
          try {
            Connection(socket).run()
          } catch (e: IOException) {
            log("connection closed: ${e.message}")
          } finally {
            connections -= socket
            runCatching { socket.close() }
          }
        }
      }
    }
    return this
  }

  override fun close() {
    runCatching { server.close() }
    connections.forEach { runCatching { it.close() } }
  }

  inner class Connection internal constructor(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    @Volatile private var maxPayload = 4096

    fun send(message: Message) = synchronized(output) { AdbWire.write(output, message) }

    fun run() {
      try {
        while (true) {
          val message = AdbWire.read(input)
          when (message.command) {
            AdbWire.A_CNXN -> {
              maxPayload = message.arg1.coerceIn(4096, AdbWire.MAX_PAYLOAD)
              log("CNXN from host: ${String(message.data).trimEnd('\u0000')}")
              send(
                Message(
                  AdbWire.A_CNXN,
                  AdbWire.VERSION,
                  AdbWire.MAX_PAYLOAD,
                  (banner() + "\u0000").toByteArray(),
                )
              )
            }
            AdbWire.A_OPEN -> open(message)
            AdbWire.A_OKAY -> streams[message.arg1]?.acknowledged(message.arg0)
            AdbWire.A_WRTE -> {
              val stream = streams[message.arg1]
              if (stream == null) {
                send(Message(AdbWire.A_CLSE, 0, message.arg0))
              } else {
                stream.received(message.data)
                send(Message(AdbWire.A_OKAY, stream.localId, stream.remoteId))
              }
            }
            AdbWire.A_CLSE -> streams.remove(message.arg1)?.closedByHost()
            AdbWire.A_AUTH,
            AdbWire.A_STLS -> log("ignoring ${AdbWire.name(message.command)}")
            else -> log("unknown command ${AdbWire.name(message.command)}")
          }
        }
      } finally {
        streams.values.forEach { it.closedByHost() }
      }
    }

    private fun open(message: Message) {
      val name = AdbWire.service(message.data)
      val service = services(name)
      log("OPEN $name${if (service == null) " (refused)" else ""}")
      if (service == null) {
        send(Message(AdbWire.A_CLSE, 0, message.arg0))
        return
      }
      val stream = AdbStream(nextId.getAndIncrement(), message.arg0, name, this)
      streams[stream.localId] = stream
      send(Message(AdbWire.A_OKAY, stream.localId, stream.remoteId))
      thread("adb-$name".take(60)) {
        try {
          service.run(stream)
        } catch (e: Exception) {
          log("$name failed: $e")
        } finally {
          stream.close()
          streams.remove(stream.localId)
        }
      }
    }

    val payload: Int
      get() = maxPayload
  }

  /**
   * One open service. [input] is what the host writes; [write] sends to the host, one `WRTE` at a
   * time, each waiting for the host's `OKAY` as the protocol requires.
   */
  inner class AdbStream
  internal constructor(
    val localId: Int,
    val remoteId: Int,
    val service: String,
    private val connection: Connection,
  ) {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val window = Semaphore(1)
    @Volatile private var closed = false

    val input: InputStream =
      object : InputStream() {
        private var current: ByteArray = AdbWire.EMPTY
        private var at = 0

        override fun read(): Int {
          val one = ByteArray(1)
          return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (len == 0) return 0
          while (at >= current.size) {
            val next = incoming.take()
            if (next === EOF) {
              incoming.put(EOF)
              return -1
            }
            current = next
            at = 0
          }
          val n = minOf(len, current.size - at)
          System.arraycopy(current, at, b, off, n)
          at += n
          return n
        }
      }

    val output: OutputStream =
      object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        override fun write(b: ByteArray, off: Int, len: Int) =
          this@AdbStream.write(b.copyOfRange(off, off + len))
      }

    fun write(data: ByteArray) {
      var at = 0
      while (at < data.size) {
        val n = minOf(connection.payload, data.size - at)
        while (!window.tryAcquire(200, TimeUnit.MILLISECONDS)) {
          if (closed) throw IOException("stream closed by host")
        }
        if (closed) throw IOException("stream closed by host")
        connection.send(Message(AdbWire.A_WRTE, localId, remoteId, data.copyOfRange(at, at + n)))
        at += n
      }
    }

    fun write(text: String) = write(text.toByteArray())

    internal fun received(data: ByteArray) {
      if (data.isNotEmpty()) incoming.put(data)
    }

    internal fun acknowledged(@Suppress("UNUSED_PARAMETER") remote: Int) = window.release()

    internal fun closedByHost() {
      closed = true
      incoming.put(EOF)
      window.release()
    }

    /** Waits (briefly) for the last `WRTE` to be acknowledged, then closes the stream. */
    fun close() {
      if (closed) return
      window.tryAcquire(2, TimeUnit.SECONDS)
      closed = true
      incoming.put(EOF)
      runCatching { connection.send(Message(AdbWire.A_CLSE, localId, remoteId)) }
    }
  }

  private companion object {
    val EOF = ByteArray(0)

    fun thread(name: String, body: () -> Unit) =
      Thread(body, name).apply {
        isDaemon = true
        start()
      }
  }
}

/** A service the device runs on an opened stream; returning closes it. */
internal fun interface AdbService {
  fun run(stream: AdbDeviceServer.AdbStream)
}
