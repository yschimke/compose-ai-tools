package ee.schimke.composeai.plugin

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared LSP-style JSON-RPC stdio helpers for the `compose-preview bundle daemon` e2e tests
 * ([BundleDaemonEndToEndFunctionalTest], [AndroidBundleDaemonRenderFunctionalTest]).
 *
 * Mirrors the wire shape the VS Code extension's `DaemonClient` writes — `Content-Length`-framed
 * UTF-8 JSON over the daemon subprocess's stdin/stdout — kept here so the tests stay free of any
 * vscode-extension dependency. Reads are bounded by a wall-clock timeout (the blocking
 * [InputStream.read] runs on a daemon worker thread waited on via [java.util.concurrent.Future]) so
 * a wedged daemon surfaces as a deterministic failure rather than hanging the suite.
 */
internal object BundleDaemonStdio {

  fun jsonRpcRequest(id: Int, method: String, params: JsonObject? = null): String =
    Json.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
      },
    )

  fun jsonRpcNotification(method: String, params: JsonObject? = null): String =
    Json.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
      },
    )

  fun writeFrame(stream: OutputStream, json: String) {
    val bytes = json.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    stream.write(header)
    stream.write(bytes)
    stream.flush()
  }

  fun readFrameWithTimeoutMs(stream: InputStream, timeoutMs: Long): String {
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "bundle-daemon-frame-reader").apply { isDaemon = true }
    }
    try {
      val future = executor.submit<String> { readFrameBlocking(stream) }
      try {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {
        future.cancel(true)
        error("timeout (${timeoutMs}ms) reading daemon frame on stdio")
      } catch (e: ExecutionException) {
        // Unwrap the worker's failure so the assertion message points at the inner
        // `daemon stdout closed mid-header` / malformed-header diagnostic instead of an opaque
        // `ExecutionException`.
        throw e.cause ?: e
      }
    } finally {
      executor.shutdownNow()
    }
  }

  private fun readFrameBlocking(stream: InputStream): String {
    val header = StringBuilder()
    while (true) {
      val ch = stream.read()
      check(ch != -1) { "daemon stdout closed mid-header. partial=${header}" }
      header.append(ch.toChar())
      if (header.endsWith("\r\n\r\n")) break
    }
    val contentLength =
      Regex("""(?i)Content-Length:\s*(\d+)""").find(header)?.groupValues?.get(1)?.toIntOrNull()
        ?: error("malformed daemon frame header: $header")
    val buf = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val n = stream.read(buf, read, contentLength - read)
      check(n != -1) { "daemon stdout closed mid-body. expected=$contentLength got=$read" }
      read += n
    }
    return String(buf, Charsets.UTF_8)
  }
}
