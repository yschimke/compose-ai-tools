@file:OptIn(
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package ee.schimke.composeai.servewasm

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ComposeViewport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image

fun main() {
  val config = ClientConfig.fromLocation(locationSearch())
  val client = BrowserPreviewClient(config)
  ComposeViewport(viewportContainerId = "composeApp") { PreviewServerApp(client, config) }
}

data class ClientConfig(
  val session: String?,
  val token: String?,
  val initialPreview: String?,
  val initialLive: Boolean,
  val initialComposer: Boolean,
) {
  fun query(extra: Map<String, String> = emptyMap()): String {
    val values = buildMap {
      session?.let { put("session", it) }
      token?.let { put("token", it) }
      putAll(extra)
    }
    return values.entries.joinToString("&") { (key, value) ->
      "${encodeComponent(key)}=${encodeComponent(value)}"
    }
  }

  fun suffix(extra: Map<String, String> = emptyMap()): String =
    query(extra).takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

  companion object {
    fun fromLocation(search: String): ClientConfig {
      val params = parseQuery(search)
      return ClientConfig(
        session = params["session"]?.takeIf { it.isNotBlank() },
        token = params["token"]?.takeIf { it.isNotBlank() },
        initialPreview = params["preview"]?.takeIf { it.isNotBlank() },
        initialLive = params["live"] == "1" || params["live"] == "true",
        initialComposer = params["compose"] == "1" || params["compose"] == "true",
      )
    }
  }
}

data class Catalog(
  val module: String,
  val trust: String?,
  val degradations: List<String>,
  val previews: List<PreviewSummary>,
)

data class PreviewSummary(
  val id: String,
  val label: String,
  val modes: List<String>,
  val liveOnly: Boolean,
  val views: Long,
  val nativeTarget: NativeCatalogTarget?,
)

data class LiveFrame(
  val bitmap: ImageBitmap,
  val widthPx: Int,
  val heightPx: Int,
  val sequence: Long,
)

sealed interface StreamEvent {
  data object Opened : StreamEvent

  data class Frame(val value: LiveFrame) : StreamEvent

  data class Error(val message: String) : StreamEvent

  data class Closed(val code: Int, val reason: String) : StreamEvent
}

class BrowserPreviewClient(private val config: ClientConfig) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun catalog(): Catalog {
    val root = json.parseToJsonElement(fetchText("/api/previews${config.suffix()}")).jsonObject
    val module = root.string("module") ?: config.session ?: "Preview server"
    val previews =
      root["previews"]?.jsonArray.orEmpty().mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        val id = item.string("id") ?: return@mapNotNull null
        PreviewSummary(
          id = id,
          label = item.string("label") ?: id,
          modes = item["modes"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
          liveOnly = item["liveOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
          views = item["views"]?.jsonPrimitive?.longOrNull ?: 0,
          nativeTarget =
            nativeCatalogTarget(
              system = config.session,
              previewId = id,
              knobSeeds = item.overrideSeeds(),
            ),
        )
      }
    return Catalog(
      module = module,
      trust = root.string("trust"),
      degradations =
        root["degradations"]?.jsonArray.orEmpty().mapNotNull {
          (it as? JsonObject)?.string("detail")
        },
      previews = previews,
    )
  }

  suspend fun snapshot(previewId: String, overrides: Map<String, String>): ImageBitmap =
    decodeImage(
      fetchBase64(
        "/render/${encodeComponent(previewId)}${config.suffix(overrides + ("format" to "png"))}"
      )
    )

  fun legacyViewerUrl(previewId: String): String =
    "/p/${encodeComponent(previewId)}${config.suffix()}"

  fun replaceLocation(previewId: String?) {
    val query = buildMap {
      config.session?.let { put("session", it) }
      config.token?.let { put("token", it) }
      previewId?.let { put("preview", it) }
    }
    replaceBrowserQuery(
      query.entries.joinToString("&") { "${encodeComponent(it.key)}=${encodeComponent(it.value)}" }
    )
  }

  fun replaceComposerLocation() {
    val query = buildMap {
      config.session?.let { put("session", it) }
      config.token?.let { put("token", it) }
      put("compose", "1")
    }
    replaceBrowserQuery(
      query.entries.joinToString("&") { "${encodeComponent(it.key)}=${encodeComponent(it.value)}" }
    )
  }

  fun openStream(previewId: String, overrides: Map<String, String>) {
    // Decode through Skia in the Wasm process. PNG is supported by every Skiko browser runtime;
    // WebP support varies with the Skia build and a failed decode would strand an otherwise healthy
    // stream on its connecting frame.
    val query = config.query(overrides + ("codec" to "png"))
    val path =
      "/ws/${encodeComponent(previewId)}${query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""}"
    openBrowserStream(path)
  }

  fun closeStream() = closeBrowserStream()

  fun sendOverrides(overrides: Map<String, String>) {
    val body = buildString {
      append("{\"type\":\"setOverrides\",\"overrides\":{")
      overrides.entries.forEachIndexed { index, (key, value) ->
        if (index > 0) append(',')
        append(jsonString(key)).append(':').append(jsonString(value))
      }
      append("}}")
    }
    sendBrowserStream(body)
  }

  fun sendTap(x: Int, y: Int) {
    sendBrowserStream(
      "{\"type\":\"input\",\"kind\":\"click\",\"pixelX\":$x,\"pixelY\":$y," +
        "\"pointerId\":1,\"pointerType\":\"mouse\"}"
    )
  }

  suspend fun nextStreamEvent(): StreamEvent {
    val root = json.parseToJsonElement(awaitPromise(nextBrowserStreamEvent())).jsonObject
    return when (root.string("type")) {
      "opened" -> StreamEvent.Opened
      "frame" -> {
        val payload = root.string("dataBase64")
        if (payload == null) {
          StreamEvent.Error("The live stream sent an empty frame")
        } else {
          StreamEvent.Frame(
            LiveFrame(
              bitmap = decodeImage(payload),
              widthPx = root.int("widthPx") ?: 1,
              heightPx = root.int("heightPx") ?: 1,
              sequence = root["seq"]?.jsonPrimitive?.longOrNull ?: 0,
            )
          )
        }
      }
      "error" -> StreamEvent.Error(root.string("message") ?: "Live render failed")
      "closed" ->
        StreamEvent.Closed(
          code = root.int("code") ?: 1006,
          reason = root.string("reason").orEmpty(),
        )
      else -> StreamEvent.Error("Unknown live-stream message")
    }
  }

  private fun decodeImage(base64: String): ImageBitmap =
    Image.makeFromEncoded(Base64.decode(base64)).toComposeImageBitmap()

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

  private fun JsonObject.overrideSeeds(): Map<String, String> = overrideSeedsOf(this)
}

/**
 * The knob seeds a published preview's override declarations carry.
 *
 * A declaration's value is not always spelled `value`. `PreviewOverrideValue` serialises each case
 * under its own property name, and the colour case carries `argb` — so reading only `value` dropped
 * every colour override on the floor and rendered the composable's author default instead of the
 * catalog's current colour, silently and only for colours.
 *
 * Top-level and `internal` so the parsing can be tested without a browser: it is the half of this
 * client that has rules, and the half where a missing case looks like a working render.
 */
internal fun overrideSeedsOf(root: JsonObject): Map<String, String> =
  root["overrides"]
    ?.jsonArray
    .orEmpty()
    .mapNotNull { value ->
      val declaration = value as? JsonObject ?: return@mapNotNull null
      val key =
        (declaration["key"]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }
          ?: return@mapNotNull null
      val index = declaration["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
      val declared = (declaration["current"] ?: declaration["default"]) as? JsonObject
      val encoded =
        (declared?.get("value") ?: declared?.get("argb"))?.jsonPrimitive?.contentOrNull
          ?: return@mapNotNull null
      (if (index == null) key else "$key[$index]") to encoded
    }
    .toMap()

internal fun parseQuery(search: String): Map<String, String> {
  val raw = search.removePrefix("?")
  if (raw.isBlank()) return emptyMap()
  return raw
    .split('&')
    .mapNotNull { pair ->
      val separator = pair.indexOf('=')
      if (separator <= 0) null
      else
        decodeComponent(pair.substring(0, separator)) to
          decodeComponent(pair.substring(separator + 1))
    }
    .toMap()
}

private fun jsonString(value: String): String = buildString {
  append('"')
  value.forEach { char ->
    when (char) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(char)
    }
  }
  append('"')
}

internal suspend fun fetchText(url: String): String = awaitPromise(fetchTextPromise(url))

internal suspend fun fetchBase64(url: String): String = awaitPromise(fetchBase64Promise(url))

private suspend fun awaitPromise(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise
      .then { value ->
        if (continuation.isActive) continuation.resume(value.toString())
        null
      }
      .catch { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(error.toString()))
        }
        null
      }
  }

private fun fetchTextPromise(url: String): Promise<JsString> =
  js(
    """fetch(url).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.text();
    })"""
  )

private fun fetchBase64Promise(url: String): Promise<JsString> =
  js(
    """fetch(url).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.arrayBuffer();
    }).then(function (buffer) {
      var bytes = new Uint8Array(buffer), chunks = [], size = 0x8000;
      for (var i = 0; i < bytes.length; i += size) {
        chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + size)));
      }
      return btoa(chunks.join(''));
    })"""
  )

/** One browser-owned socket and a promise queue, kept outside the Compose canvas. */
private fun openBrowserStream(path: String): Unit =
  js(
    """(function () {
      if (window.__cpPreviewWasmStream && window.__cpPreviewWasmStream.socket) {
        window.__cpPreviewWasmStream.socket.close();
      }
      var state = { queue: [], waiters: [], socket: null };
      function push(value) {
        var waiter = state.waiters.shift();
        if (waiter) waiter(value); else state.queue.push(value);
      }
      var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      var socket = new WebSocket(protocol + '//' + window.location.host + path);
      state.socket = socket;
      socket.onopen = function () { push('{\"type\":\"opened\"}'); };
      socket.onmessage = function (event) { push(String(event.data)); };
      socket.onerror = function () { push('{\"type\":\"error\",\"message\":\"WebSocket connection failed\"}'); };
      socket.onclose = function (event) {
        push(JSON.stringify({ type: 'closed', code: event.code, reason: event.reason || '' }));
      };
      window.__cpPreviewWasmStream = state;
    })()"""
  )

private fun nextBrowserStreamEvent(): Promise<JsString> =
  js(
    """new Promise(function (resolve) {
      var state = window.__cpPreviewWasmStream;
      if (!state) { resolve('{\"type\":\"closed\",\"code\":1006,\"reason\":\"not connected\"}'); return; }
      if (state.queue.length) resolve(state.queue.shift()); else state.waiters.push(resolve);
    })"""
  )

private fun sendBrowserStream(message: String): Unit =
  js(
    """(function () {
      var state = window.__cpPreviewWasmStream;
      if (state && state.socket && state.socket.readyState === WebSocket.OPEN) state.socket.send(message);
    })()"""
  )

private fun closeBrowserStream(): Unit =
  js(
    """(function () {
      var state = window.__cpPreviewWasmStream;
      window.__cpPreviewWasmStream = null;
      if (state && state.socket) state.socket.close();
    })()"""
  )

private fun replaceBrowserQuery(query: String): Unit =
  js("window.history.replaceState(null, '', window.location.pathname + (query ? '?' + query : ''))")

private fun locationSearch(): String = js("window.location.search")

private fun encodeComponent(value: String): String = js("encodeURIComponent(value)")

private fun decodeComponent(value: String): String = runCatching {
  decodeComponentUnsafe(value.replace('+', ' '))
}
  .getOrDefault(value)

private fun decodeComponentUnsafe(value: String): String = js("decodeURIComponent(value)")
