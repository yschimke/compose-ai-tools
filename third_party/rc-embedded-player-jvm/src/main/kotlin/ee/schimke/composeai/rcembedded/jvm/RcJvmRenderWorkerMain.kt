/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.remote.core.operations.Theme
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
 * The one-shot entry point pays Compose Desktop + Skiko boot on every document — measured at ~2.3
 * s, against ~85 ms for a render on an already-warm JVM. Since a `.rc` document is self-describing
 * (it is the same byte stream the in-browser JS player draws with no knowledge of any project), a
 * worker needs nothing project-derived and can therefore take a document from any catalog, in any
 * order. That is what makes a shared pool possible here and not for the `@Preview` lane, whose
 * daemon must hold the consumer module's classloader.
 *
 * Reusing a process across documents is only sound because it is observably identical to a fresh
 * one: `RcJvmHotWorkerDeterminismTest` renders a corpus cold, churns the process, and asserts the
 * re-render is byte-identical. That test is the gate on this file existing — if it ever fails, the
 * pool must be disabled rather than the assertion relaxed, because `rc-compare` gates the PR on
 * pixel parity and a worker-age-dependent render would make that gate flaky.
 *
 * ## Wire protocol
 *
 * Binary frames over stdin/stdout, big-endian, no external dependency. The cli side is
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
 * Closing the worker's stdin ends it cleanly (the next frame read hits EOF and it exits 0).
 */
fun rcJvmRenderWorkerMain() {
  // Claim the real stdout for protocol frames BEFORE anything else can print to it. Skiko, AWT,
  // the font resolver and any transitive library are all free to write to `System.out`; a single
  // stray line would be read as a frame header and desynchronise the stream for good. Everything
  // that goes on writing to `System.out` lands on stderr instead, where the pool drains it into
  // the failure tail.
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
    // Appended after `format` when the jvm player learned to select a `ColorTheme` mode. Both ends
    // of this pipe are staged from the same build, so the frame is versioned by the build rather
    // than negotiated — but the field is last so a frame read by an older worker would simply stop
    // before it, which is the failure this ordering is chosen for.
    val theme = if (input.readInt() == WIRE_THEME_DARK) Theme.DARK else Theme.LIGHT
    val seedsText = String(input.readPayload(), Charsets.UTF_8)
    val doc = input.readPayload()

    var fatal: Throwable? = null
    val response =
      try {
        val seeds = parseSeedText(seedsText)
        val artifact =
          when (format) {
            WIRE_FORMAT_SVG -> renderRemoteDocumentToSvg(doc, width, height, density, seeds, theme)
            else -> renderRemoteDocumentToPng(doc, width, height, density, seeds, theme)
          }
        Response(STATUS_OK, artifact)
      } catch (e: Exception) {
        // A document this player cannot draw is an ordinary per-request failure: report it and stay
        // alive, exactly as the one-shot path reports it as a non-zero exit without implying the
        // renderer itself is broken.
        Response(STATUS_FAILED, "${e::class.java.simpleName}: ${e.message}".toByteArray())
      } catch (t: Throwable) {
        // An Error (OOM, a native link failure, a StackOverflow) says the *process* is no longer
        // trustworthy. Answer the caller so it gets a reason rather than a timeout, then exit so
        // the pool discards this worker instead of reusing a damaged JVM.
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

/** Entry point for `java -cp … ee.schimke.composeai.rcembedded.jvm.RcJvmRenderWorkerMainKt`. */
fun main() {
  rcJvmRenderWorkerMain()
}

private class Response(val status: Int, val payload: ByteArray)

/**
 * Read a length-prefixed payload, rejecting a length that could only come from a desynchronised
 * stream — without this a corrupt length allocates an arbitrary array and the worker dies on OOM
 * instead of on the protocol error that actually happened.
 */
private fun DataInputStream.readPayload(): ByteArray {
  val len = readInt()
  if (len < 0 || len > MAX_PAYLOAD_BYTES) {
    System.err.println("rcjvm worker: implausible payload length $len; exiting")
    exitProcess(4)
  }
  return ByteArray(len).also { readFully(it) }
}

// Mirrored by `RcJvmWorkerPool` on the cli side. 'RCW1' / 'RCQ1' / 'RCR1' as big-endian ASCII.
internal const val MAGIC_HELLO = 0x52435731
internal const val MAGIC_REQUEST = 0x52435131
internal const val MAGIC_RESPONSE = 0x52435231
// 2 adds the per-request `theme` field. The version is what makes a stale `lib-rcjvm/` sidecar fall
// back to the one-shot path rather than mis-reading a frame it does not know the shape of, so it
// has
// to move whenever the frame does — a worker still speaking 1 would read `theme` as `seedsLen` and
// desynchronise for good.
internal const val PROTOCOL_VERSION = 2
internal const val STATUS_OK = 0
internal const val STATUS_FAILED = 1
internal const val WIRE_THEME_LIGHT = 0
internal const val WIRE_THEME_DARK = 1
internal const val WIRE_FORMAT_PNG = 0
internal const val WIRE_FORMAT_SVG = 1

/** 256 MB — far above any real document or rendered artifact, far below "allocate until OOM". */
private const val MAX_PAYLOAD_BYTES = 256 * 1024 * 1024
