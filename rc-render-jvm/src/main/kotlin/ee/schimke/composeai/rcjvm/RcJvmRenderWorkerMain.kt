package ee.schimke.composeai.rcjvm

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * The **pooled** counterpart of [main]: a long-lived worker that renders one captured Remote
 * Compose document per request frame, instead of one per process.
 *
 * The one-shot entry point pays Compose Desktop + Skiko boot on every document (seconds), against
 * tens of milliseconds for a render on an already-warm JVM. A `.rc` document is self-describing, so
 * a worker needs nothing project-derived and can take a document from any catalog, in any order.
 * That is what makes a shared pool possible here and not for the `@Preview` lane, whose daemon must
 * hold the consumer module's classloader.
 *
 * ## Wire protocol
 *
 * Binary frames over stdin/stdout, big-endian, no external dependency. The serve side is
 * `RcJvmWorkerPool`, which mirrors these constants and refuses to use a worker whose
 * [PROTOCOL_VERSION] it does not recognise — so an install whose `lib-rcjvm/` sidecar predates this
 * file falls back to the one-shot path instead of hanging on a handshake that never comes.
 *
 * ```
 * worker -> pool, once at startup:
 *   int32 MAGIC_HELLO, int32 PROTOCOL_VERSION
 * pool -> worker, per request:
 *   int32 MAGIC_REQUEST, int32 requestId, int32 width, int32 height,
 *   int32 densityBits (Float.floatToIntBits), int32 format (0=png, 1=svg),
 *   int32 theme (0=light, 1=dark), int32 seedsLen, <seedsLen bytes UTF-8>,
 *   int32 docLen, <docLen bytes>
 * worker -> pool, per response:
 *   int32 MAGIC_RESPONSE, int32 requestId, int32 status (0=ok, 1=failed),
 *   int32 payloadLen, <payloadLen bytes>   // artifact bytes on ok, UTF-8 reason on failure
 * ```
 *
 * The frame carries no font scale, so the serve side renders a request that scales text through the
 * one-shot path, which does. Closing the worker's stdin ends it cleanly.
 */
public fun rcJvmRenderWorkerMain() {
  // Claim the real stdout for frames before anything prints: one stray line from Skiko, AWT or a
  // library would desynchronise the stream. Later `System.out` writes go to stderr.
  val frames = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
  System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

  val input = DataInputStream(System.`in`.buffered())

  frames.writeInt(MAGIC_HELLO)
  frames.writeInt(PROTOCOL_VERSION)
  frames.flush()

  while (true) {
    val magic =
      try {
        input.readInt()
      } catch (_: EOFException) {
        // The pool closed our stdin: an ordinary shutdown, not a failure.
        frames.flush()
        exitProcess(0)
      }
    if (magic != MAGIC_REQUEST) {
      // The stream is desynchronised and there is no safe way to resynchronise mid-frame. Die so
      // the pool replaces this worker rather than serving it garbage forever.
      System.err.println("rcjvm worker: unexpected frame magic $magic; exiting")
      exitProcess(4)
    }

    val requestId = input.readInt()
    val width = input.readInt()
    val height = input.readInt()
    val density = Float.fromBits(input.readInt())
    val format = input.readInt()
    val dark = input.readInt() == WIRE_THEME_DARK
    val seedsText = String(input.readPayload(), Charsets.UTF_8)
    val doc = input.readPayload()

    var fatal: Throwable? = null
    val response =
      try {
        val seeds = parseSeedText(seedsText)
        val artifact =
          when (format) {
            WIRE_FORMAT_SVG -> renderRemoteDocumentToSvg(doc, width, height, density, seeds, dark)
            else -> renderRemoteDocumentToPng(doc, width, height, density, seeds, dark)
          }
        Response(STATUS_OK, artifact)
      } catch (e: Exception) {
        // An undrawable document is a per-request failure; the worker stays alive.
        Response(STATUS_FAILED, "${e::class.java.simpleName}: ${e.message}".toByteArray())
      } catch (t: Throwable) {
        // An Error means the JVM is no longer trustworthy: answer, then exit so the pool replaces
        // us.
        fatal = t
        Response(STATUS_FAILED, "${t::class.java.simpleName}: ${t.message}".toByteArray())
      }

    frames.writeInt(MAGIC_RESPONSE)
    frames.writeInt(requestId)
    frames.writeInt(response.status)
    frames.writeInt(response.payload.size)
    frames.write(response.payload)
    frames.flush()

    fatal?.let {
      System.err.println("rcjvm worker: fatal ${it::class.java.simpleName}; exiting")
      exitProcess(5)
    }
  }
}

/** Entry point for `java -cp … ee.schimke.composeai.rcjvm.RcJvmRenderWorkerMainKt`. */
public fun main() {
  rcJvmRenderWorkerMain()
}

private class Response(val status: Int, val payload: ByteArray)

/** Read a length-prefixed payload, exiting on a length only a desynchronised stream could send. */
private fun DataInputStream.readPayload(): ByteArray {
  val len = readInt()
  if (len < 0 || len > MAX_PAYLOAD_BYTES) {
    System.err.println("rcjvm worker: implausible payload length $len; exiting")
    exitProcess(4)
  }
  return ByteArray(len).also { readFully(it) }
}

// Mirrored by `RcJvmWorkerPool` on the serve side. 'RCW1' / 'RCQ1' / 'RCR1' as big-endian ASCII.
internal const val MAGIC_HELLO = 0x52435731
internal const val MAGIC_REQUEST = 0x52435131
internal const val MAGIC_RESPONSE = 0x52435231

/**
 * Bump whenever the frame changes, so a stale `lib-rcjvm/` sidecar falls back to the one-shot path
 * instead of misreading frames. 2 added the per-request `theme`.
 */
internal const val PROTOCOL_VERSION = 2
internal const val STATUS_OK = 0
internal const val STATUS_FAILED = 1
internal const val WIRE_THEME_LIGHT = 0
internal const val WIRE_THEME_DARK = 1
internal const val WIRE_FORMAT_PNG = 0
internal const val WIRE_FORMAT_SVG = 1

/** 256 MB — far above any real document or rendered artifact, far below "allocate until OOM". */
private const val MAX_PAYLOAD_BYTES = 256 * 1024 * 1024
