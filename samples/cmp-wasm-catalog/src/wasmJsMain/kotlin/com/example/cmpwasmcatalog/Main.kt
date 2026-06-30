@file:OptIn(
  ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlin.js.ExperimentalJsExport::class,
)

package com.example.cmpwasmcatalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

/**
 * Browser entrypoint for the in-browser CMP catalog (Workstream C / model 1).
 *
 * Reads `?id=<component>&uiMode=<light|dark>&fontScale=<f>&localeTag=<bcp47>` from the page URL and
 * mounts the matching catalog component into the `#composeApp` container. This is what the `serve`
 * viewer embeds in a sandboxed `<iframe>` at the `data-mode="live"` seam — the Wasm runs in the
 * browser sandbox, so it's safe to execute even for an unverified session (no code runs on our
 * server). The viewer's theme / font-scale / locale controls re-point these params, so they drive
 * the in-browser render live (device/orientation are server-render-only and stay disabled there).
 *
 * Those controls update the render **in place** rather than reloading the iframe: the page's
 * initial query is the [baseParams] floor and [applyOverrides] (called by the embedding viewer via
 * `postMessage`) merges a `?a=b` patch over it into [renderParams], which recomposes the live tree.
 * Recomputing from [baseParams] every time means an absent key reverts to the deep-link default
 * (e.g. clearing the Theme control falls back to the baked variant's `uiMode`), with no full
 * reload.
 */
private var baseParams: Map<String, String> = emptyMap()
private val renderParams = mutableStateOf<Map<String, String>>(emptyMap())

fun main() {
  baseParams = parseQuery(locationSearch())
  renderParams.value = baseParams
  ComposeViewport(viewportContainerId = "composeApp") {
    val params by renderParams
    val id = params["id"] ?: catalogComponents.keys.first()
    val dark = params["uiMode"] == "dark"
    // Clamp to the viewer slider's range so a crafted query can't blow up layout.
    val fontScale = params["fontScale"]?.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1f
    val rtl = isRtlLocale(params["localeTag"])
    CatalogApp(id, dark, fontScale, rtl)
  }
}

/**
 * Apply a live override patch (`?a=b&c=d`, no leading `?`) pushed by the embedding viewer through
 * `window.postMessage`. Exported to JS so the host page's message listener can forward it; merges
 * over [baseParams] so absent keys revert to the deep-link defaults, then recomposes in place.
 */
@JsExport
fun applyOverrides(query: String) {
  renderParams.value = baseParams + parseQuery(query)
}

/**
 * Whether [localeTag]'s primary language subtag is right-to-left. Layout direction is the locale
 * effect a single component actually shows in the browser (full locale formatting needs the server
 * renderer); covers the common RTL languages.
 */
internal fun isRtlLocale(localeTag: String?): Boolean {
  val lang =
    localeTag?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return false
  return lang in setOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi", "dv")
}

/** The raw `?…` query string, read straight from the browser's `window.location`. */
private fun locationSearch(): String = js("window.location.search")

/** Minimal `?a=b&c=d` parser — avoids a `kotlinx-browser` / URLSearchParams dependency. */
internal fun parseQuery(search: String): Map<String, String> {
  val trimmed = search.removePrefix("?")
  if (trimmed.isEmpty()) return emptyMap()
  return trimmed
    .split("&")
    .mapNotNull { pair ->
      val eq = pair.indexOf('=')
      if (eq <= 0) null else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
    }
    .toMap()
}

private fun decode(value: String): String = value.replace('+', ' ')
