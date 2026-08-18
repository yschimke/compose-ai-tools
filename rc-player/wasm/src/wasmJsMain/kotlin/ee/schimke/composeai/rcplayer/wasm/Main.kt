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
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
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

/**
 * Which document the player is showing, and how many times it has been asked.
 *
 * [generation] is not decoration: `?src` is usually a *stable* URL whose bytes change underneath it
 * (the parity driver serves every preview from one `/document.rc`), so the source alone cannot key
 * a reload — and two consecutive documents can decode to equal [RcDocument]s. Counting the requests
 * gives both the fetch and the readiness signal a key that always moves.
 */
private data class LoadRequest(val source: String?, val generation: Int)

private var loadRequest by mutableStateOf(LoadRequest(null, 0))
private var loadState by mutableStateOf<LoadState>(LoadState.Loading)

public fun main() {
  // Browser User Timing marks are opt-in: `performance`'s entry buffer is finite and shared with
  // whatever else the embedding page measures, so a player embedded in someone's dashboard should
  // not be filling it on every frame. `?rcTrace=1` turns the player's spans on for a session; the
  // span names match the ones the desktop player writes into Perfetto.
  setRcPlatformTracingEnabled(queryParameter("rcTrace") == "1")
  loadRequest = LoadRequest(queryParameter("src"), generation = 0)
  installDocumentSwap()
  // `?theme=` is part of the embed contract, so the accepted spellings stay exactly as they were;
  // only the type the player takes has changed. Anything else — including no parameter at all —
  // follows the browser's `prefers-color-scheme`, which is what `RcTheme.UNSPECIFIED` resolved to
  // here before.
  val theme =
    when (queryParameter("theme")?.lowercase()) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  ComposeViewport(viewportContainerId = "rcPlayer") {
    LaunchedEffect(Unit) {
      // One waiter, re-armed after every swap: `window.rcPlayerLoad(src)` hands the next source in
      // here instead of the host navigating the page again. See [installDocumentSwap].
      while (true) {
        val next = awaitDocumentSwap()
        loadRequest = LoadRequest(next, loadRequest.generation + 1)
      }
    }

    val request = loadRequest
    LaunchedEffect(request) {
      val source = request.source
      // Drop the previous document before fetching the next: the player leaves composition, so no
      // state from the document being replaced can reach the one replacing it, and a host that
      // waits on the readiness marker cannot mistake the outgoing render for the incoming one.
      loadState = LoadState.Loading
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
                  allowExternalImagePlaceholders =
                    queryParameter("allowExternalImagePlaceholders") == "1",
                )
                .requireFullyRenderable()
              LoadState.Ready(document, fontFamilies, namedValuesFromLocation())
            }
            .fold(onSuccess = { it }, onFailure = { LoadState.Failed(it.message ?: "load failed") })
    }

    when (val state = loadState) {
      LoadState.Loading -> Unit
      // Keyed by request as well as message: two documents can fail the same way, and the second
      // failure still has to be reported — `rcPlayerLoad` cleared the marker the first one set.
      is LoadState.Failed -> LaunchedEffect(request, state.message) { reportFailure(state.message) }
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
        LaunchedEffect(request) {
          // Compose schedules Skiko's raster work after composition. One frame only proves the
          // composition ran; waiting through two further browser frames prevents the host from
          // revealing an iframe whose backing surface is still blank on a cold Wasm start.
          repeat(3) { withFrameNanos {} }
          // Chromium can acknowledge those frames before the Skiko surface is presented to the
          // compositor. Keep the parent snapshot visible through that short cold-start tail.
          delay(handoffDelayMs)
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
  // The manifest and its faces belong to the *host*, not to the document, so a swapped-in document
  // (see `window.rcPlayerLoad`) reuses what the first load already fetched and decoded rather than
  // paying for the whole catalog's fonts again. `fontsBase` is fixed for the life of the page, but
  // it is kept in the key so the cache cannot answer for a base it was not filled from.
  cachedFontFamilies?.let { (cachedBase, families) -> if (cachedBase == base) return families }
  val entries = parseFontsManifest(fetchText(base + "fonts.json"))
  suspend fun load(entry: ManifestFont): RcFontFace =
    RcFontFace(
      identity = entry.file,
      data = fetchBytes(base + entry.file),
      weight = entry.weight,
      italic = entry.italic,
    )
  val families = buildMap {
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
  cachedFontFamilies = base to families
  return families
}

/**
 * The host faces from the last successful [loadHostFontFamilies], keyed by the base they came from.
 */
private var cachedFontFamilies: Pair<String, Map<String, RcFontFaces>>? = null

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

/** The default cold-start tail. Every render pays it, so a host that cannot flash should not. */
private const val DEFAULT_HANDOFF_DELAY_MS = 1_500L

/**
 * How long to keep saying "not ready" after the frames have gone through, so a host that reveals
 * this player on `ready` cannot swap a snapshot for a surface the compositor has not presented yet.
 *
 * `?handoffDelayMs=0` turns the tail off, and **only a host that composites the result itself**
 * should ask for that. The parity driver is the case that exists: it screenshots through CDP, which
 * drives its own compositor frame, then verifies the size and every pixel of what came back — so a
 * surface that was not presented yet cannot slip past it, and the 1.5 s is dead weight repeated
 * once per preview (~3 minutes across a 122-preview catalog). The viewer's iframe handoff has no
 * such check and keeps the default: it is the one that would show a blank frame to a human, and
 * that failure could not be reproduced under CDP capture in the first place — screenshots and
 * screencasts both drive frames of their own, so neither can observe it. An unverifiable hazard
 * keeps its guard.
 */
private val handoffDelayMs: Long
  get() =
    queryParameter("handoffDelayMs")?.toLongOrNull()?.coerceIn(0L, 10_000L)
      ?: DEFAULT_HANDOFF_DELAY_MS

/**
 * Install `window.rcPlayerLoad(src)`: show another document in the player that is already running,
 * instead of navigating the page again.
 *
 * A navigation is the honest way to load the *first* document, but it is a poor way to load the
 * next one — it throws away the instantiated Wasm module, the Compose runtime and the host fonts,
 * then rebuilds all three to draw a document that is usually a few dozen operations long. The
 * parity driver renders a whole catalog through one page, so it pays that teardown once per
 * preview; a 122-preview catalog spends minutes on it. Handing over just the source keeps the
 * player warm and leaves the reload contract unchanged: the marker on `<html>` goes back to
 * `loading` synchronously here, so a host that waits for `ready` cannot read the outgoing render's
 * marker and screenshot the document it just replaced.
 *
 * `?theme` and `?namedValues` are *not* re-read — they belong to the page, and a host that needs
 * different ones should navigate. Only the document changes.
 *
 * The handshake is a one-slot mailbox rather than an event listener because this module reaches the
 * browser exclusively through `js(...)` (no `kotlinx-browser` dependency): [awaitDocumentSwap]
 * parks a resolver here, and a call that arrives while the player is busy loading is held in
 * `pending` until the next waiter arms. Requests are last-one-wins, which is what a host driving
 * one render at a time wants.
 */
private fun installDocumentSwap(): Unit =
  js(
    """{
      window.__rcPlayerSwap = { pending: null, resolve: null };
      window.rcPlayerLoad = function (source) {
        var request = String(source);
        var root = document.documentElement;
        root.dataset.rcPlayerState = 'loading';
        delete root.dataset.rcPlayerError;
        var swap = window.__rcPlayerSwap;
        if (swap.resolve) {
          var resolve = swap.resolve;
          swap.resolve = null;
          resolve(request);
        } else {
          swap.pending = request;
        }
      };
    }"""
  )

private fun nextDocumentSwap(): Promise<JsString> =
  js(
    """new Promise(function (resolve) {
      var swap = window.__rcPlayerSwap;
      if (swap.pending !== null) {
        var pending = swap.pending;
        swap.pending = null;
        resolve(pending);
      } else {
        swap.resolve = resolve;
      }
    })"""
  )

private suspend fun awaitDocumentSwap(): String = suspendCancellableCoroutine { continuation ->
  nextDocumentSwap()
    .then { source ->
      if (continuation.isActive) continuation.resume(source.toString())
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
