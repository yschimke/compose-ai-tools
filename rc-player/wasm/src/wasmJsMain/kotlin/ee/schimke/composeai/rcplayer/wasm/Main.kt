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
        RcComposePlayer(state.document, Modifier.fillMaxSize(), theme = theme)
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
      "console.error('[rc-player-wasm] ' + message), " +
      "window.parent.postMessage('cp-rc-wasm-error:' + message, '*'))"
  )
