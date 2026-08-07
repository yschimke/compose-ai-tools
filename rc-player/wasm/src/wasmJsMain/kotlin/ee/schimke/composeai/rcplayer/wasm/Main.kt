@file:OptIn(
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package ee.schimke.composeai.rcplayer.wasm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcFontFace
import ee.schimke.composeai.rcplayer.compose.RcFontFaces
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.rcplayer.trace.RcTraceCategory
import ee.schimke.composeai.rcplayer.trace.rcTrace
import ee.schimke.composeai.rcplayer.trace.setRcPlatformTracingEnabled
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private sealed interface LoadState {
  data object Loading : LoadState

  data class Ready(
    val document: RcDocument,
    val fontFamilies: Map<String, RcFontFaces>,
    val namedValues: Map<String, RcNamedValue>,
  ) : LoadState

  data class Failed(val message: String) : LoadState
}

private var loadState by mutableStateOf<LoadState>(LoadState.Loading)

public fun main() {
  // Browser User Timing marks are opt-in: `performance`'s entry buffer is finite and shared with
  // whatever else the embedding page measures, so a player embedded in someone's dashboard should
  // not be filling it on every frame. `?rcTrace=1` turns the player's spans on for a session; the
  // span names match the ones the desktop player writes into Perfetto.
  setRcPlatformTracingEnabled(queryParameter("rcTrace") == "1")
  val source = queryParameter("src")
  val theme =
    when (queryParameter("theme")?.lowercase()) {
      "light" -> RcTheme.LIGHT
      "dark" -> RcTheme.DARK
      else -> RcTheme.UNSPECIFIED
    }
  ComposeViewport(viewportContainerId = "rcPlayer") {
    LaunchedEffect(source) {
      loadState =
        if (source == null) LoadState.Failed("Missing ?src=<document.rc>")
        else
          runCatching {
              val bytes =
                rcTrace(RcTraceCategory.DOCUMENT, "rc:fetchDocument") { fetchBytes(source) }
              val document = RcDocumentCodec.decode(bytes)
              val fontFamilies = withTimeout(8_000) { loadHostFontFamilies() }
              document
                .composeSupportReport(
                  RcOperationProfiles.CMP_WASM_ALPHA16,
                  availableFontFamilies = fontFamilies.keys,
                )
                .requireFullyRenderable()
              LoadState.Ready(document, fontFamilies, namedValuesFromLocation())
            }
            .fold(onSuccess = { it }, onFailure = { LoadState.Failed(it.message ?: "load failed") })
    }

    when (val state = loadState) {
      LoadState.Loading -> Unit
      is LoadState.Failed -> LaunchedEffect(state.message) { reportFailure(state.message) }
      is LoadState.Ready -> {
        RcComposePlayer(
          state.document,
          Modifier.fillMaxSize().drawWithContent {
            drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            drawContent()
          },
          theme = theme,
          namedValues = state.namedValues,
          onEvent = ::postPlayerEvent,
          fontFamilies = state.fontFamilies,
        )
        LaunchedEffect(state.document) {
          // Compose schedules Skiko's raster work after composition. One frame only proves the
          // composition ran; waiting through two further browser frames prevents the host from
          // revealing an iframe whose backing surface is still blank on a cold Wasm start.
          repeat(3) { withFrameNanos {} }
          // Chromium can acknowledge those frames before the Skiko surface is presented to the
          // compositor. Keep the parent snapshot visible through that short cold-start tail.
          delay(1_500)
          postReady()
        }
      }
    }
  }
}

private fun fetchAsBase64(url: String): Promise<JsString> =
  js(
    """fetch(url).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.arrayBuffer();
    }).then(function (buffer) {
      var bytes = new Uint8Array(buffer), chunks = [], chunkSize = 0x8000;
      for (var i = 0; i < bytes.length; i += chunkSize) {
        chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize)));
      }
      return btoa(chunks.join(''));
    })"""
  )

private suspend fun fetchBytes(url: String): ByteArray =
  suspendCancellableCoroutine { continuation ->
    fetchAsBase64(url)
      .then { encoded ->
        if (continuation.isActive) continuation.resume(Base64.decode(encoded.toString()))
        null
      }
      .catch { failure ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(failure.toString()))
        }
        null
      }
  }

private data class ManifestFont(
  val role: String,
  val family: String,
  val file: String,
  val weight: Int,
  val italic: Boolean,
)

/**
 * The host's font families, keyed by lowercase family name.
 *
 * Every role in the manifest is loaded, `default` included. A `default`-role family is a real,
 * nameable family — the catalog's own text face — and a document is free to name it the way it
 * names any other (`google:Roboto Flex`). Loading it only as "the fallback" left that name
 * unresolvable: `RcComposeSupport` checks a named family against exactly this set, so a document
 * naming the default face failed to load rather than rendering in it.
 *
 * Faces are kept as [RcFontFace] — bytes, not a built `FontFamily` — so a document that also names
 * font-variation axes can be given the instance it asked for. See [RcFontFaces].
 */
private suspend fun loadHostFontFamilies(): Map<String, RcFontFaces> {
  val rawBase = queryParameter("fontsBase") ?: "./fonts/"
  val base =
    (if (rawBase.endsWith('/')) rawBase else "$rawBase/").takeIf {
      !it.contains(':') || it.startsWith("http:") || it.startsWith("https:")
    } ?: "./fonts/"
  val entries = parseFontsManifest(fetchText(base + "fonts.json"))
  suspend fun load(entry: ManifestFont) =
    RcFontFace(
      identity = entry.file,
      data = fetchBytes(base + entry.file),
      weight = entry.weight,
      italic = entry.italic,
    )
  return buildMap {
    entries
      .groupBy { it.family }
      .forEach { (family, faces) ->
        runCatching { RcFontFaces(faces.map { load(it) }) }
          .onSuccess { put(family.lowercase(), it) }
      }
    // `role` names what a family *is for*, and the default-role family answers to two keys: its own
    // name, registered above so `google:Roboto Flex` resolves, and the literal "default" a document
    // asks for when it names no family at all — which is what every CoreText in the remote-m3
    // catalog does. Keying by name alone left "default" unresolvable, so all of that text silently
    // fell through to Compose's built-in face instead of the manifest's.
    entries
      .filter { it.role == "default" }
      .groupBy { it.family }
      .forEach { (family, faces) ->
        if (!containsKey("default")) {
          (get(family.lowercase())
              ?: runCatching { RcFontFaces(faces.map { load(it) }) }.getOrNull())
            ?.let { put("default", it) }
        }
      }
  }
}

private fun parseFontsManifest(json: String): List<ManifestFont> {
  val flat = flattenFontsManifest(json)?.toString().orEmpty()
  if (flat.isEmpty()) return emptyList()
  return flat.split('\u0001').mapNotNull { row ->
    val fields = row.split('\u0000')
    if (fields.size != 5) return@mapNotNull null
    val file =
      fields[2].takeIf { it.isNotEmpty() && ".." !in it.split('/') && !it.contains(':') }
        ?: return@mapNotNull null
    ManifestFont(
      role = fields[0],
      family = fields[1],
      file = file,
      weight = fields[3].toIntOrNull()?.coerceIn(1, 1000) ?: 400,
      italic = fields[4] == "italic",
    )
  }
}

private fun flattenFontsManifest(json: String): JsString? =
  js(
    """(function () {
      try {
        var manifest = JSON.parse(json), rows = [];
        (manifest.families || []).forEach(function (family) {
          (family.fonts || []).forEach(function (font) {
            rows.push([family.role || 'default', family.name || '', String(font.file || ''),
              String(font.weight || 400), String(font.style || 'normal')].join('\u0000'));
          });
        });
        return rows.join('\u0001');
      } catch (error) { return null; }
    })()"""
  )

private fun fetchTextPromise(url: String): Promise<JsString> =
  js(
    """fetch(url).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.text();
    })"""
  )

private suspend fun fetchText(url: String): String = suspendCancellableCoroutine { continuation ->
  fetchTextPromise(url)
    .then { value ->
      if (continuation.isActive) continuation.resume(value.toString())
      null
    }
    .catch { failure ->
      if (continuation.isActive) {
        continuation.resumeWithException(IllegalStateException(failure.toString()))
      }
      null
    }
}

private fun queryParameter(name: String): String? =
  queryParameterFromLocation(name).toString().takeUnless { it == "null" }

private fun queryParameterFromLocation(name: String): JsString? =
  js("new URL(window.location.href).searchParams.get(name)")

private fun namedValuesFromLocation(): Map<String, RcNamedValue> {
  val flat = flattenNamedValuesFromLocation()?.toString().orEmpty()
  if (flat.isEmpty()) return emptyMap()
  return buildMap {
    flat.split('\u0001').forEach { row ->
      val fields = row.split('\u0000')
      if (fields.size != 3) return@forEach
      val name = decodeUriComponent(fields[1])
      val value = decodeUriComponent(fields[2])
      val namedValue =
        when (fields[0]) {
          "string" -> RcNamedValue.Text(value)
          "float",
          "dp" -> value.toFloatOrNull()?.let(RcNamedValue::FloatValue)
          "int" -> value.toIntOrNull()?.let(RcNamedValue::Integer)
          "bool" -> RcNamedValue.Integer(if (value == "true") 1 else 0)
          "color" -> value.removePrefix("#").toULongOrNull(16)?.toInt()?.let(RcNamedValue::Color)
          "long" -> value.toLongOrNull()?.let(RcNamedValue::LongValue)
          else -> null
        }
      if (name.isNotEmpty() && namedValue != null) put("USER:$name", namedValue)
    }
  }
}

private fun flattenNamedValuesFromLocation(): JsString? =
  js(
    """(function () {
      try {
        var raw = new URL(window.location.href).searchParams.get('namedValues') || '[]';
        var values = JSON.parse(raw);
        if (!Array.isArray(values)) return null;
        return values.map(function (value) {
          return [String(value.kind || ''), encodeURIComponent(String(value.name || '')),
            encodeURIComponent(String(value.value == null ? '' : value.value))].join('\u0000');
        }).join('\u0001');
      } catch (error) { return null; }
    })()"""
  )

private fun decodeUriComponent(value: String): String = decodeUriComponentJs(value).toString()

private fun decodeUriComponentJs(value: String): JsString = js("decodeURIComponent(value)")

private fun postReady(): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerState = 'ready', " +
      "window.parent.postMessage('cp-rc-wasm-ready', window.location.origin))"
  )

private fun reportFailure(message: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerState = 'error', " +
      "document.documentElement.dataset.rcPlayerError = message, " +
      "console.error('[rc-player-wasm] ' + message), " +
      "window.parent.postMessage('cp-rc-wasm-error:' + message, window.location.origin))"
  )

private fun postPlayerEvent(event: RcPlayerEvent) {
  when (event) {
    is RcPlayerEvent.DebugMessage -> postDebugMessage(event.message, event.value, event.flags)
    is RcPlayerEvent.HostAction -> postHostAction(event.actionId)
    is RcPlayerEvent.HostActionMetadata -> postHostMetadataAction(event.actionId, event.metadata)
    is RcPlayerEvent.HostNamedAction ->
      when (val value = event.value) {
        RcHostActionValue.None -> postHostNamedActionNone(event.name)
        is RcHostActionValue.FloatValue -> postHostNamedActionFloat(event.name, value.value)
        is RcHostActionValue.IntegerValue -> postHostNamedActionInt(event.name, value.value)
        is RcHostActionValue.TextValue -> postHostNamedActionText(event.name, value.value)
        is RcHostActionValue.FloatListValue ->
          postHostNamedActionFloatList(event.name, value.value.joinToString(","))
      }
  }
}

private fun postDebugMessage(message: String, value: Float, flags: Int): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerDebugMessage = message, " +
      "document.documentElement.dataset.rcPlayerDebugValue = String(value), " +
      "document.documentElement.dataset.rcPlayerDebugFlags = String(flags), " +
      "console.debug('[rc-player-wasm] ' + message + ' ' + String(value)), " +
      "window.parent.postMessage({ type: 'cp-rc-debug-message', message: message, " +
      "value: value, flags: flags }, window.location.origin))"
  )

private fun postHostAction(actionId: Int): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerAction = String(actionId), " +
      "document.documentElement.dataset.rcPlayerActionTrace = " +
      "(document.documentElement.dataset.rcPlayerActionTrace ? " +
      "document.documentElement.dataset.rcPlayerActionTrace + ',' : '') + String(actionId), " +
      "window.parent.postMessage({ type: 'cp-rc-host-action', actionId: actionId }, " +
      "window.location.origin))"
  )

private fun postHostMetadataAction(actionId: Int, metadata: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerAction = String(actionId), " +
      "document.documentElement.dataset.rcPlayerActionTrace = " +
      "(document.documentElement.dataset.rcPlayerActionTrace ? " +
      "document.documentElement.dataset.rcPlayerActionTrace + ',' : '') + String(actionId), " +
      "document.documentElement.dataset.rcPlayerMetadata = metadata, " +
      "window.parent.postMessage({ type: 'cp-rc-host-action', actionId: actionId, " +
      "metadata: metadata }, window.location.origin))"
  )

private fun postHostNamedActionNone(name: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'none', " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'none', value: null }, window.location.origin))"
  )

private fun postHostNamedActionFloat(name: String, value: Float): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'float:' + String(value), " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'float', value: value }, window.location.origin))"
  )

private fun postHostNamedActionInt(name: String, value: Int): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'int:' + String(value), " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'int', value: value }, window.location.origin))"
  )

private fun postHostNamedActionText(name: String, value: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'string:' + value, " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'string', value: value }, window.location.origin))"
  )

private fun postHostNamedActionFloatList(name: String, encoded: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'float-array:' + encoded, " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'float-array', " +
      "value: encoded === '' ? [] : encoded.split(',').map(Number) }, window.location.origin))"
  )
