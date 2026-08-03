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
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

private sealed interface LoadState {
  data object Loading : LoadState

  data class Ready(val document: RcDocument) : LoadState

  data class Failed(val message: String) : LoadState
}

private var loadState by mutableStateOf<LoadState>(LoadState.Loading)

public fun main() {
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
              RcDocumentCodec.decode(fetchBytes(source)).also {
                it
                  .composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16)
                  .requireFullyRenderable()
              }
            }
            .fold(
              onSuccess = LoadState::Ready,
              onFailure = { LoadState.Failed(it.message ?: "load failed") },
            )
    }

    when (val state = loadState) {
      LoadState.Loading -> Unit
      is LoadState.Failed -> LaunchedEffect(state.message) { reportFailure(state.message) }
      is LoadState.Ready -> {
        RcComposePlayer(
          state.document,
          Modifier.fillMaxSize(),
          theme = theme,
          onEvent = ::postPlayerEvent,
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

private fun queryParameter(name: String): String? =
  queryParameterFromLocation(name).toString().takeUnless { it == "null" }

private fun queryParameterFromLocation(name: String): JsString? =
  js("new URL(window.location.href).searchParams.get(name)")

private fun postReady(): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerState = 'ready', " +
      "window.parent.postMessage('cp-rc-wasm-ready', '*'))"
  )

private fun reportFailure(message: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerState = 'error', " +
      "document.documentElement.dataset.rcPlayerError = message, " +
      "console.error('[rc-player-wasm] ' + message), " +
      "window.parent.postMessage('cp-rc-wasm-error:' + message, '*'))"
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
      "value: value, flags: flags }, '*'))"
  )

private fun postHostAction(actionId: Int): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerAction = String(actionId), " +
      "document.documentElement.dataset.rcPlayerActionTrace = " +
      "(document.documentElement.dataset.rcPlayerActionTrace ? " +
      "document.documentElement.dataset.rcPlayerActionTrace + ',' : '') + String(actionId), " +
      "window.parent.postMessage({ type: 'cp-rc-host-action', actionId: actionId }, '*'))"
  )

private fun postHostMetadataAction(actionId: Int, metadata: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerAction = String(actionId), " +
      "document.documentElement.dataset.rcPlayerActionTrace = " +
      "(document.documentElement.dataset.rcPlayerActionTrace ? " +
      "document.documentElement.dataset.rcPlayerActionTrace + ',' : '') + String(actionId), " +
      "document.documentElement.dataset.rcPlayerMetadata = metadata, " +
      "window.parent.postMessage({ type: 'cp-rc-host-action', actionId: actionId, " +
      "metadata: metadata }, '*'))"
  )

private fun postHostNamedActionNone(name: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'none', " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'none', value: null }, '*'))"
  )

private fun postHostNamedActionFloat(name: String, value: Float): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'float:' + String(value), " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'float', value: value }, '*'))"
  )

private fun postHostNamedActionInt(name: String, value: Int): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'int:' + String(value), " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'int', value: value }, '*'))"
  )

private fun postHostNamedActionText(name: String, value: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'string:' + value, " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'string', value: value }, '*'))"
  )

private fun postHostNamedActionFloatList(name: String, encoded: String): Unit =
  js(
    "(document.documentElement.dataset.rcPlayerNamedAction = name, " +
      "document.documentElement.dataset.rcPlayerNamedActionValue = 'float-array:' + encoded, " +
      "window.parent.postMessage({ type: 'cp-rc-host-named-action', name: name, " +
      "valueType: 'float-array', " +
      "value: encoded === '' ? [] : encoded.split(',').map(Number) }, '*'))"
  )
