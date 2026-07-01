package ee.schimke.composeai.fakeemulator

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves an OPEN destination string (`shell,v2:…`, `sync:`, …) to a handler, or `null` to reject.
 */
interface AdbServiceResolver {
  fun resolve(destination: String): AdbService?
}

/**
 * One ADB device service. Runs on its own thread; [io] is closed automatically after [run] returns.
 */
interface AdbService {
  fun run(io: AdbStreamIo)
}

/**
 * Byte duplex for one ADB stream. [write] is flow-controlled; [read] returns `null` at peer close.
 */
interface AdbStreamIo {
  val destination: String

  fun write(bytes: ByteArray)

  fun read(): ByteArray?

  fun close()
}

/**
 * One accepted ADB transport connection (one socket). Owns the read loop, the CNXN handshake, and
 * the per-OPEN stream multiplex with the protocol's one-outstanding-write flow control.
 */
internal class AdbConnection(
  private val socket: Socket,
  private val banner: ByteArray,
  private val resolver: AdbServiceResolver,
  private val spawn: (Runnable) -> Unit,
) {
  private val input: InputStream = socket.getInputStream()
  private val output: OutputStream = socket.getOutputStream()
  private val writeLock = Any()
  private val streams = ConcurrentHashMap<Int, Stream>()
  private val nextLocalId = AtomicInteger(1)
  @Volatile private var peerMaxData = AdbProtocol.MAX_PAYLOAD

  fun run() {
    try {
      while (true) {
        val message = AdbProtocol.read(input) ?: break
        dispatch(message)
      }
    } catch (_: Exception) {
      // Peer disconnected or framing error — fall through to teardown.
    } finally {
      for (stream in streams.values) stream.forceClose()
      streams.clear()
      runCatching { socket.close() }
    }
  }

  private fun dispatch(message: AdbMessage) {
    when (message.command) {
      AdbProtocol.A_CNXN -> {
        if (message.arg1 in 1..(64 * 1024 * 1024)) peerMaxData = message.arg1
        send(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, banner))
      }
      AdbProtocol.A_OPEN -> open(peerLocalId = message.arg0, destination = cstr(message.payload))
      AdbProtocol.A_OKAY -> streams[message.arg1]?.grantWrite()
      AdbProtocol.A_WRTE -> {
        val stream = streams[message.arg1]
        if (stream != null) {
          stream.deliver(message.payload)
          // Ack so the peer may send its next WRTE.
          send(AdbMessage(AdbProtocol.A_OKAY, stream.localId, stream.peerLocalId))
        }
      }
      AdbProtocol.A_CLSE -> streams.remove(message.arg1)?.peerClosed()
      // AUTH / STLS: we connect un-authed and advertise no TLS, so we never negotiate either.
      else -> Unit
    }
  }

  private fun open(peerLocalId: Int, destination: String) {
    val service = resolver.resolve(destination)
    if (service == null) {
      // Reject: CLSE with local-id 0 tells the opener the stream was refused.
      send(AdbMessage(AdbProtocol.A_CLSE, 0, peerLocalId))
      return
    }
    val localId = nextLocalId.getAndIncrement()
    val stream = Stream(localId, peerLocalId, destination)
    streams[localId] = stream
    send(AdbMessage(AdbProtocol.A_OKAY, localId, peerLocalId))
    spawn {
      try {
        service.run(stream)
      } catch (_: Exception) {
        // Service blew up — just close the stream below.
      } finally {
        stream.close()
      }
    }
  }

  private fun send(message: AdbMessage) {
    synchronized(writeLock) { AdbProtocol.write(output, message) }
  }

  /**
   * Decode an OPEN destination. adb appends a single terminating NUL; we strip just that one so
   * `abb_exec:`'s internal NUL argument separators (`package\0install\0-S\0…`) survive — a
   * truncate-at-first-NUL would lose the install args.
   */
  private fun cstr(bytes: ByteArray): String {
    val end =
      if (bytes.isNotEmpty() && bytes[bytes.size - 1].toInt() == 0) bytes.size - 1 else bytes.size
    return String(bytes, 0, end, StandardCharsets.UTF_8)
  }

  /** One logical ADB stream within this connection. */
  private inner class Stream(
    val localId: Int,
    val peerLocalId: Int,
    override val destination: String,
  ) : AdbStreamIo {
    // Protocol flow control: at most one un-acked WRTE in flight. Starts ready (1 permit).
    private val writeReady = Semaphore(1)
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val sentClose = AtomicBoolean(false)

    override fun write(bytes: ByteArray) {
      var offset = 0
      val chunkSize = peerMaxData.coerceAtMost(AdbProtocol.MAX_PAYLOAD)
      while (offset < bytes.size && !closed.get()) {
        val end = (offset + chunkSize).coerceAtMost(bytes.size)
        val chunk = bytes.copyOfRange(offset, end)
        writeReady.acquire()
        if (closed.get()) return
        send(AdbMessage(AdbProtocol.A_WRTE, localId, peerLocalId, chunk))
        offset = end
      }
    }

    override fun read(): ByteArray? {
      val chunk = inbound.take()
      return if (chunk === EOF) null else chunk
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        if (sentClose.compareAndSet(false, true)) {
          send(AdbMessage(AdbProtocol.A_CLSE, localId, peerLocalId))
        }
        streams.remove(localId)
        inbound.offer(EOF)
        // Unblock a writer parked on flow control.
        if (writeReady.availablePermits() == 0) writeReady.release()
      }
    }

    fun grantWrite() {
      if (writeReady.availablePermits() == 0) writeReady.release()
    }

    fun deliver(payload: ByteArray) {
      if (payload.isNotEmpty()) inbound.offer(payload)
    }

    /** Peer sent CLSE: ack with our CLSE (once) and tear down. */
    fun peerClosed() {
      if (closed.compareAndSet(false, true)) {
        if (sentClose.compareAndSet(false, true)) {
          send(AdbMessage(AdbProtocol.A_CLSE, localId, peerLocalId))
        }
        inbound.offer(EOF)
        if (writeReady.availablePermits() == 0) writeReady.release()
      }
    }

    /** Connection teardown: stop blocking readers/writers without emitting more packets. */
    fun forceClose() {
      closed.set(true)
      sentClose.set(true)
      inbound.offer(EOF)
      if (writeReady.availablePermits() == 0) writeReady.release()
    }
  }

  private companion object {
    /** Identity sentinel queued to signal "no more inbound data" to [Stream.read]. */
    val EOF = ByteArray(0)
  }
}
