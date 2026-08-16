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

package ee.schimke.composeai.cli.serve

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * A stand-in for `RcJvmRenderWorkerMain` that speaks the same frames without Compose or Skiko, so
 * [RcJvmWorkerPoolTest] can exercise the pool's protocol, recycling and failure handling on any
 * machine — including one with no native render stack, where the real worker cannot start at all.
 *
 * Behaviour is selected by system properties on the spawned JVM, so one stub covers every case the
 * pool has to survive:
 * * `stub.mode=ok` (default) — echo a deterministic artifact derived from the request.
 * * `stub.mode=failed` — answer `STATUS_FAILED`, the "player cannot draw this document" case.
 * * `stub.mode=hang` — accept the request and never answer, so the watchdog has to fire.
 * * `stub.mode=badVersion` — hello with an unrecognised protocol version.
 * * `stub.mode=crash` — exit before answering, the "worker died mid-request" case.
 * * `stub.mode=chatty` — write to `System.out` before the hello frame. The real worker reroutes
 *   `System.out` to stderr for exactly this reason; the stub proves the pool would notice if it
 *   ever stopped.
 *
 * The artifact for `ok` is `"<width>x<height>@<density>:<format>:<theme>:<seeds>:<docLen>#<n>"`,
 * where `n` counts the requests **this process** has served. That lets a test assert the whole
 * request survived the wire, and — via `n` — distinguish a reused warm worker from a freshly
 * spawned one, which is the entire property the pool exists to provide.
 */
object RcJvmWorkerPoolStub {

  @JvmStatic
  fun main(args: Array<String>) {
    val mode = System.getProperty("stub.mode", "ok")

    if (mode == "chatty") {
      // Deliberately NOT rerouted: this is the mistake the real worker guards against.
      println("stub: a stray line on stdout")
      System.out.flush()
    }

    val frames = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
    val input = DataInputStream(System.`in`.buffered())

    frames.writeInt(RcJvmWorkerPool.MAGIC_HELLO)
    frames.writeInt(if (mode == "badVersion") 99 else RcJvmWorkerPool.PROTOCOL_VERSION)
    frames.flush()

    var served = 0
    while (true) {
      val magic =
        try {
          input.readInt()
        } catch (_: EOFException) {
          exitProcess(0)
        }
      if (magic != RcJvmWorkerPool.MAGIC_REQUEST) exitProcess(4)

      val requestId = input.readInt()
      val width = input.readInt()
      val height = input.readInt()
      val density = Float.fromBits(input.readInt())
      val format = input.readInt()
      val theme = input.readInt()
      val seeds = String(input.readPayload(), Charsets.UTF_8)
      val doc = input.readPayload()

      when (mode) {
        "hang" -> {
          System.err.println("stub: hanging on request $requestId")
          Thread.sleep(Long.MAX_VALUE)
        }
        "crash" -> {
          System.err.println("stub: dying on request $requestId")
          exitProcess(9)
        }
      }

      served++
      val formatName = if (format == RcJvmWorkerPool.WIRE_FORMAT_SVG) "svg" else "png"
      val themeName = if (theme == RcJvmWorkerPool.WIRE_THEME_DARK) "dark" else "light"
      val payload =
        "${width}x$height@$density:$formatName:$themeName:$seeds:${doc.size}#$served".toByteArray()
      val status =
        if (mode == "failed") RcJvmWorkerPool.STATUS_FAILED else RcJvmWorkerPool.STATUS_OK

      frames.writeInt(RcJvmWorkerPool.MAGIC_RESPONSE)
      frames.writeInt(requestId)
      frames.writeInt(status)
      frames.writeInt(payload.size)
      frames.write(payload)
      frames.flush()
    }
  }

  private fun DataInputStream.readPayload(): ByteArray {
    val len = readInt()
    return ByteArray(len).also { readFully(it) }
  }
}
