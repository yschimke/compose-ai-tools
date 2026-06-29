@file:OptIn(ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.example.cmpwasmcatalog

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

/**
 * Browser entrypoint for the in-browser CMP catalog (Workstream C / model 1).
 *
 * Reads `?id=<component>&uiMode=<light|dark>` from the page URL and mounts the matching catalog
 * component into the `#composeApp` container. This is what the `serve` viewer embeds in a sandboxed
 * `<iframe>` at the `data-mode="live"` seam — the Wasm runs in the browser sandbox, so it's safe to
 * execute even for an unverified session (no code runs on our server).
 */
fun main() {
  val params = parseQuery(locationSearch())
  val id = params["id"] ?: catalogComponents.keys.first()
  val dark = params["uiMode"] == "dark"
  ComposeViewport(viewportContainerId = "composeApp") { CatalogApp(id, dark) }
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
