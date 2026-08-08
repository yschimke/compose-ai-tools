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

package ee.schimke.composeai.renderer

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * The **pooled** counterpart of [main]: a long-lived renderer that draws one capture per request
 * frame instead of one per process.
 *
 * `RenderPreviewsTask` used to `javaexec` this module once per capture, so every preview paid a
 * whole JVM plus Compose Desktop and Skiko boot — 2.15 s/preview measured end-to-end on m3-catalog,
 * ~43 min for its 1095-preview catalog, against ~36 ms for a render on an already-warm process.
 *
 * The body here is deliberately thin: it decodes a frame and calls [main] with exactly the argv the
 * per-capture `javaexec` would have passed. Nothing about *how* a preview is drawn lives in this
 * file, so a pooled capture cannot draw differently from a forked one — the property
 * `DesktopRendererReentrancyTest` pins, and the reason this is a transport change rather than a
 * rendering one.
 *
 * Two things make calling [main] repeatedly in one process safe, both of them checked rather than
 * assumed:
 * * every `exitProcess` in [main] is on the argument-validation prologue — a *render* failure
 *   writes an `.error.json` sidecar and returns normally, so a broken preview costs a request
 *   rather than a worker;
 * * `@OverrideVariant` seeds are reset per render (see [main]'s `resetForNewSession()` call), so a
 *   capture never inherits the knobs of whatever the worker drew before it.
 *
 * ## Wire protocol
 *
 * Binary frames over stdin/stdout, big-endian, no external dependency — the same shape as
 * `RcJvmRenderWorkerMain`, whose pool this one's mirrors. The plugin side is
 * `DesktopRenderWorkerPool`, which refuses to use a worker whose [WORKER_PROTOCOL_VERSION] it does
 * not recognise, so a renderer resolved from an older `composePreviewRenderer` configuration falls
 * back to per-capture forks instead of hanging on a handshake that never comes.
 *
 * ```
 * worker -> pool, once at startup:
 *   int32 MAGIC_HELLO, int32 WORKER_PROTOCOL_VERSION
 * pool -> worker, per request:
 *   int32 MAGIC_REQUEST, int32 requestId,
 *   int32 seedLen, <seedLen bytes UTF-8>,     // `composeai.overrides.seed`, empty for none
 *   int32 argc, argc x (int32 len, <len bytes UTF-8>)
 * worker -> pool, per response:
 *   int32 MAGIC_RESPONSE, int32 requestId, int32 status (0=ok, 1=failed),
 *   int32 messageLen, <messageLen bytes UTF-8>   // empty on success
 * ```
 *
 * The rendered artifact is **not** returned over the wire: [main] writes it to the output path in
 * the argv, exactly as the forked renderer did, so the pool changes nothing about where files land.
 *
 * Closing the worker's stdin ends it cleanly (the next frame read hits EOF and it exits 0).
 */
fun desktopRendererWorkerMain() {
  // Claim the real stdout for protocol frames BEFORE anything else can print to it. Skiko, AWT,
  // Compottie and the renderer itself all write to `System.out`; one stray line would be read as a
  // frame header and desynchronise the stream permanently. Everything that keeps writing to
  // `System.out` lands on stderr, which the pool drains into its failure tail.
  val frames = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
  System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

  val input = DataInputStream(System.`in`.buffered())

  frames.writeInt(MAGIC_HELLO)
  frames.writeInt(WORKER_PROTOCOL_VERSION)
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
      System.err.println("compose-preview renderer worker: unexpected frame magic $magic; exiting")
      exitProcess(4)
    }

    val requestId = input.readInt()
    val seed = String(input.readPayload(), Charsets.UTF_8)
    val argc = input.readInt()
    if (argc < 0 || argc > MAX_ARGC) {
      System.err.println("compose-preview renderer worker: implausible argc $argc; exiting")
      exitProcess(4)
    }
    val args = Array(argc) { String(input.readPayload(), Charsets.UTF_8) }

    var fatal: Throwable? = null
    var status = STATUS_OK
    var message = ""
    try {
      // Per-render, so it rides the request rather than the worker's environment. Cleared
      // afterwards as well as set: `main()` resets the controller per render, but leaving a stale
      // property set would make the *next* request look seeded to anything else reading it.
      if (seed.isEmpty()) System.clearProperty(OVERRIDES_SEED_PROPERTY)
      else System.setProperty(OVERRIDES_SEED_PROPERTY, seed)
      main(args)
    } catch (e: Exception) {
      // A capture the renderer cannot draw is an ordinary per-request failure. `main()` already
      // writes an `.error.json` sidecar for render failures it handles; this covers the rest
      // without taking the worker down, matching the forked path's non-zero exit for one capture.
      status = STATUS_FAILED
      message = "${e::class.java.simpleName}: ${e.message}"
    } catch (t: Throwable) {
      // An Error (OOM, a native link failure) means the process is no longer trustworthy. Answer
      // first so the caller gets a reason rather than a timeout, then exit so the pool discards it.
      fatal = t
      status = STATUS_FAILED
      message = "${t::class.java.simpleName}: ${t.message}"
    } finally {
      System.clearProperty(OVERRIDES_SEED_PROPERTY)
    }

    val payload = message.toByteArray(Charsets.UTF_8)
    frames.writeInt(MAGIC_RESPONSE)
    frames.writeInt(requestId)
    frames.writeInt(status)
    frames.writeInt(payload.size)
    frames.write(payload)
    frames.flush()

    fatal?.let {
      System.err.println(
        "compose-preview renderer worker: fatal ${it::class.java.simpleName}; exiting"
      )
      exitProcess(5)
    }
  }
}

/** Entry point for `java -cp … ee.schimke.composeai.renderer.DesktopRendererWorkerMainKt`. */
fun main() {
  desktopRendererWorkerMain()
}

/**
 * Read a length-prefixed payload, rejecting a length that could only come from a desynchronised
 * stream — without this a corrupt length allocates an arbitrary array and the worker dies on OOM
 * rather than on the protocol error that actually happened.
 */
private fun DataInputStream.readPayload(): ByteArray {
  val len = readInt()
  if (len < 0 || len > MAX_PAYLOAD_BYTES) {
    System.err.println("compose-preview renderer worker: implausible payload length $len; exiting")
    exitProcess(4)
  }
  return ByteArray(len).also { readFully(it) }
}

// Mirrored by `DesktopRenderWorkerPool` in the gradle plugin, which cannot depend on this module
// (the renderer is resolved into the consumer's graph). The version check on the hello frame is
// what keeps the duplication honest: a mismatch is refused and the caller forks instead.
internal const val MAGIC_HELLO = 0x43505731 // 'CPW1'
internal const val MAGIC_REQUEST = 0x43505131 // 'CPQ1'
internal const val MAGIC_RESPONSE = 0x43505231 // 'CPR1'
internal const val WORKER_PROTOCOL_VERSION = 1
internal const val STATUS_OK = 0
internal const val STATUS_FAILED = 1
internal const val OVERRIDES_SEED_PROPERTY = "composeai.overrides.seed"

/** Far above any real argv or seed, far below "allocate until OOM". */
private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
private const val MAX_ARGC = 1024
