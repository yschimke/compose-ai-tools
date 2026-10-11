@file:OptIn(
  ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlin.js.ExperimentalJsExport::class,
  ExperimentalEncodingApi::class,
)

package com.example.cmpwasmcatalog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.example.designcatalogm3.shared.CatalogApp
import com.example.designcatalogm3.shared.LocalWasmCatalogKnobs
import com.example.designcatalogm3.shared.catalogComponentIds
import ee.schimke.composeai.screen.CompileCheck
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Browser entrypoint for the in-browser CMP catalog.
 *
 * Reads `?id=<component>&uiMode=<light|dark>&fontScale=<f>&localeTag=<bcp47>` (plus
 * `knob.<key>=<value>` edits) and mounts the matching catalog component into `#composeApp`. The
 * `serve` viewer embeds this in a sandboxed `<iframe>`, so it runs only in the browser sandbox.
 *
 * Viewer controls update the render in place: [applyOverrides] (via `postMessage`) merges a patch
 * over the page's initial [baseParams] into [renderParams], so an absent key reverts to the
 * deep-link default without a reload.
 */
private var baseParams: Map<String, String> = emptyMap()
private val renderParams = mutableStateOf<Map<String, String>>(emptyMap())

fun main() {
  // The `?…` query is the clean baked default; the viewer's initial overrides travel in the `#…`
  // fragment, so clearing a control later reverts to the true default.
  baseParams = parseQuery(locationSearch())
  renderParams.value = baseParams + parseQuery(locationHash())
  ComposeViewport(viewportContainerId = "composeApp") {
    // Hold the composition (and the first-frame signal) until the `fonts.json` fonts load, so the
    // first revealed frame matches the baked snapshot's typefaces. Failure or timeout falls back to
    // the bundled font.
    var fonts by remember { mutableStateOf<FontsState>(FontsState.Loading) }
    LaunchedEffect(Unit) {
      fonts =
        withTimeoutOrNull(FONT_LOAD_TIMEOUT_MS) { loadCatalogFonts() } ?: FontsState.Ready(null)
    }
    val loaded = fonts as? FontsState.Ready ?: return@ComposeViewport
    val params by renderParams
    // `?mode=builder` mounts the UI builder over the same catalog and knob lookups.
    if (params["mode"] == "builder") {
      MaterialTheme(
        colorScheme = if (params["uiMode"] == "dark") darkColorScheme() else lightColorScheme()
      ) {
        ScreenBuilderApp(compileHost = CompileCheck.hostFrom(params))
      }
      return@ComposeViewport
    }
    val id = params["id"] ?: catalogComponentIds.first()
    val dark = params["uiMode"] == "dark"
    // Clamp to the viewer slider's range so a crafted query can't blow up layout.
    val fontScale = params["fontScale"]?.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1f
    val rtl = isRtlLocale(params["localeTag"])
    // The sticker surface is always transparent, so there's no `background` handling.
    // `bgPhase=<x>,<y>` is the viewer's checkerboard origin in this frame's CSS px, so the app's
    // checkerboard continues the page's cells.
    val checkerPhase = parsePhase(params["bgPhase"])
    // The viewer's resolved stage colour; absent means the page shows its checkerboard, which the
    // app continues.
    val stageColor = parseStageColor(params["stageBg"])
    // `knob.<key>=<value>` params, stripped of the prefix for the catalog's `catalogOverride*`
    // lookups; absent knobs render their author default.
    val knobs = knobOverrides(params)
    CompositionLocalProvider(LocalWasmCatalogKnobs provides knobs) {
      CatalogApp(
        id,
        dark,
        fontScale,
        rtl,
        checkerPhase,
        stageColor,
        loaded.family,
        loaded.generics,
        loaded.named,
        ::postFirstFrame,
      )
    }
  }
}

/**
 * Project the `knob.<key>=<value>` params out of [params], stripped to `<key>` (the runtime's
 * `seedKey`: base key or `key[index]`). Values stay raw strings for each `catalogOverride*` to
 * parse.
 */
internal fun knobOverrides(params: Map<String, String>): Map<String, String> {
  val out = mutableMapOf<String, String>()
  for ((k, v) in params) {
    if (k.startsWith(KNOB_PREFIX) && k.length > KNOB_PREFIX.length) {
      out[k.substring(KNOB_PREFIX.length)] = v
    }
  }
  return out
}

private const val KNOB_PREFIX = "knob."

private const val FONT_LOAD_TIMEOUT_MS = 8_000L

/** Font loading state: the catalog composes only once resolved (family null ⇒ bundled default). */
private sealed interface FontsState {
  data object Loading : FontsState

  data class Ready(
    val family: FontFamily?,
    val generics: Map<String, FontFamily> = emptyMap(),
    val named: Map<String, FontFamily> = emptyMap(),
  ) : FontsState
}

/**
 * Load the catalog's fonts by URL from the `fonts.json` manifest: each `role: "default"` family
 * becomes the [FontFamily] for the whole M3 type scale. Defaults to the vendored `./fonts/`
 * (offline-clean); `?fontsBase=` may point at any CORS-enabled origin (the sandboxed iframe has an
 * opaque origin). A base without a manifest falls back to the fixed Roboto pair.
 *
 * The host `index.html` starts these fetches at load (`__cpPrefetch*`) in parallel with Wasm boot,
 * and the bridge consumes those promises. It must be the iframe's own prefetch, since the opaque
 * origin has its own HTTP-cache partition.
 */
private suspend fun loadCatalogFonts(): FontsState.Ready {
  val raw = baseParams["fontsBase"] ?: "./fonts/"
  // A fetch URL, not code — but still refuse non-http(s) absolute schemes (javascript:, data:).
  val base =
    (if (raw.endsWith("/")) raw else "$raw/").takeIf {
      !it.contains(":") || it.startsWith("http:") || it.startsWith("https:")
    } ?: "./fonts/"
  val entries = runCatching {
    parseFontsManifest(fetchText(base + "fonts.json"))
  }
    .getOrDefault(emptyList())
  suspend fun load(e: ManifestFont) =
    Font(
      identity = e.file,
      data = fetchBytes(base + e.file),
      weight = FontWeight(e.weight),
      style = if (e.italic) FontStyle.Italic else FontStyle.Normal,
    )
  if (entries.isEmpty()) {
    // Legacy layout: a fontsBase serving bare TTFs without a manifest (the #2174 contract).
    return try {
      FontsState.Ready(
        FontFamily(
          Font(identity = "Roboto-Regular", data = fetchBytes(base + "Roboto-Regular.ttf")),
          Font(
            identity = "Roboto-Medium",
            data = fetchBytes(base + "Roboto-Medium.ttf"),
            weight = FontWeight.Medium,
          ),
        )
      )
    } catch (e: Throwable) {
      consoleWarn("compose-ai wasm catalog: font load failed (${e.message}); using bundled font")
      FontsState.Ready(null)
    }
  }
  // Each family loads fail-soft in isolation, so a missing generic TTF doesn't take down the
  // default.
  val default =
    try {
      entries
        .filter { it.role == "default" }
        .takeIf { it.isNotEmpty() }
        ?.let { list -> FontFamily(list.map { load(it) }) }
    } catch (e: Throwable) {
      consoleWarn(
        "compose-ai wasm catalog: default font load failed (${e.message}); using bundled font"
      )
      null
    }
  // Generic-family substitutes, grouped by the name genericFontFamily() looks up (`serif`, …).
  val generics = mutableMapOf<String, FontFamily>()
  entries
    .filter { it.role == "generic" && it.family.isNotEmpty() }
    .groupBy { it.family }
    .forEach { (name, list) ->
      try {
        generics[name] = FontFamily(list.map { load(it) })
      } catch (e: Throwable) {
        consoleWarn(
          "compose-ai wasm catalog: generic family '$name' load failed (${e.message}); " +
            "using platform fallback"
        )
      }
    }
  // Named GoogleFont families (`role: "named"`), keyed by display name for namedFontFamily(); same
  // fail-soft isolation.
  val named = mutableMapOf<String, FontFamily>()
  entries
    .filter { it.role == "named" && it.family.isNotEmpty() }
    .groupBy { it.family }
    .forEach { (name, list) ->
      try {
        named[name] = FontFamily(list.map { load(it) })
      } catch (e: Throwable) {
        consoleWarn(
          "compose-ai wasm catalog: named family '$name' load failed (${e.message}); " +
            "using fallback font"
        )
      }
    }
  return FontsState.Ready(default, generics, named)
}

/** One font file declared by `fonts.json`, flattened out of its family entry. */
internal data class ManifestFont(
  val role: String,
  val family: String,
  val file: String,
  val weight: Int,
  val italic: Boolean,
)

/**
 * Parse `fonts.json` (`{families: [{name, role, fonts: [{file, weight, style}]}]}`), flattened on
 * the JS side ([flattenFontsManifest]). Rows with a missing or unsafe `file` (traversal, absolute
 * scheme) are dropped; unknown roles are kept for the caller to filter.
 */
internal fun parseFontsManifest(json: String?): List<ManifestFont> {
  val flat = json?.let { flattenFontsManifest(it) }?.toString() ?: return emptyList()
  if (flat.isEmpty()) return emptyList()
  return flat.split(ROW_SEP).mapNotNull { row ->
    val f = row.split(FIELD_SEP)
    if (f.size != 5) return@mapNotNull null
    val file =
      f[2].takeIf { it.isNotEmpty() && ".." !in it.split("/") && !it.contains(":") }
        ?: return@mapNotNull null
    ManifestFont(
      role = f[0],
      family = f[1],
      file = file,
      weight = f[3].toIntOrNull()?.coerceIn(1, 1000) ?: 400,
      italic = f[4] == "italic",
    )
  }
}

private const val FIELD_SEP = "\u0000"
private const val ROW_SEP = "\u0001"

/**
 * `JSON.parse` the manifest into `role␀name␀file␀weight␀style` rows (␁-joined), one string across
 * the Wasm↔JS boundary. Null/empty on malformed JSON.
 */
private fun flattenFontsManifest(json: String): JsString? =
  js(
    """(function () {
      try {
        var m = JSON.parse(json), out = [];
        (m.families || []).forEach(function (fam) {
          (fam.fonts || []).forEach(function (f) {
            out.push([fam.role || 'default', fam.name || '', String(f.file || ''),
              String(f.weight || 400), String(f.style || 'normal')].join('\u0000'));
          });
        });
        return out.join('\u0001');
      } catch (e) { return null; }
    })()"""
  )

/**
 * `fetch(url)` → base64 of the body: Kotlin/Wasm can't take a `Uint8Array` as a `ByteArray`.
 * Chunked `String.fromCharCode` stays under the JS argument-count limit.
 */
private fun fetchAsBase64(url: String, timeoutMs: Int): Promise<JsString> =
  js(
    """((window.__cpPrefetchBuf && window.__cpPrefetchBuf[url]) ||
      fetch(url, { signal: AbortSignal.timeout(timeoutMs) })
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); }))
      .then(function (buf) {
        var bytes = new Uint8Array(buf), chunks = [], CHUNK = 0x8000;
        for (var i = 0; i < bytes.length; i += CHUNK)
          chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK)));
        return btoa(chunks.join(''));
      })"""
  )

/**
 * Cancellable, so `withTimeoutOrNull` actually unblocks on a stalled origin; `AbortSignal.timeout`
 * also kills the underlying request.
 */
private suspend fun fetchBytes(url: String): ByteArray = suspendCancellableCoroutine { cont ->
  fetchAsBase64(url, timeoutMs = (FONT_LOAD_TIMEOUT_MS + 2_000L).toInt())
    .then { s ->
      if (cont.isActive) cont.resume(Base64.decode(s.toString()))
      null
    }
    .catch { e ->
      if (cont.isActive) cont.resumeWithException(IllegalStateException(e.toString()))
      null
    }
}

private fun fetchAsText(url: String, timeoutMs: Int): Promise<JsString> =
  js(
    """((window.__cpPrefetchText && window.__cpPrefetchText[url]) ||
      fetch(url, { signal: AbortSignal.timeout(timeoutMs) })
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }))"""
  )

/** `fetch(url)` → response text; cancellable like [fetchBytes] so timeouts genuinely unblock. */
private suspend fun fetchText(url: String): String = suspendCancellableCoroutine { cont ->
  fetchAsText(url, timeoutMs = (FONT_LOAD_TIMEOUT_MS + 2_000L).toInt())
    .then { s ->
      if (cont.isActive) cont.resume(s.toString())
      null
    }
    .catch { e ->
      if (cont.isActive) cont.resumeWithException(IllegalStateException(e.toString()))
      null
    }
}

private fun consoleWarn(message: String): Unit = js("console.warn(message)")

/**
 * Parse the viewer's `stageBg=#rrggbb`. Null otherwise (absent, `checker`, malformed), leaving the
 * app on its checkerboard.
 */
internal fun parseStageColor(raw: String?): Color? {
  val hex = raw?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
  val rgb = hex.toLongOrNull(16) ?: return null
  return Color(0xFF000000L or rgb)
}

/** Parse the viewer's `bgPhase=<x>,<y>` (CSS px, possibly fractional/negative). */
internal fun parsePhase(raw: String?): Offset {
  val parts = raw?.split(",") ?: return Offset.Zero
  if (parts.size != 2) return Offset.Zero
  val x = parts[0].toFloatOrNull() ?: return Offset.Zero
  val y = parts[1].toFloatOrNull() ?: return Offset.Zero
  if (!x.isFinite() || !y.isFinite()) return Offset.Zero
  return Offset(x, y)
}

/**
 * Tell the embedding viewer the first real frame is drawn ("cp-wasm-ready"), so it can swap from
 * the baked snapshot without a blank flash. Harmless when top-level (it parses to an empty patch).
 */
private fun postFirstFrame() {
  postToParent("cp-wasm-ready")
}

private fun postToParent(message: String): Unit = js("window.parent.postMessage(message, '*')")

/**
 * Apply a live override patch (`a=b&c=d`) pushed by the viewer via `window.postMessage`, merged
 * over [baseParams] and recomposed in place. Exported for the host page's message listener.
 */
@JsExport
fun applyOverrides(query: String) {
  renderParams.value = baseParams + parseQuery(query)
}

/**
 * Whether [localeTag]'s primary language subtag is right-to-left — layout direction being the
 * locale effect a single component shows in the browser.
 */
internal fun isRtlLocale(localeTag: String?): Boolean {
  val lang =
    localeTag?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return false
  return lang in setOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi", "dv")
}

/** The raw `?…` query string, read straight from the browser's `window.location`. */
private fun locationSearch(): String = js("window.location.search")

/** The `#…` fragment without its leading `#` — the viewer's initial session overrides at load. */
private fun locationHash(): String = js("window.location.hash.replace(/^#/, '')")

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

/**
 * Decode one query key/value: `+` → space, then UTF-8 `%XX` escapes (`encodeURIComponent` escapes
 * the comma in `bgPhase`). Hand-rolled so a malformed escape degrades to literal text instead of
 * throwing across the JS boundary.
 */
internal fun decode(value: String): String {
  val plusDecoded = value.replace('+', ' ')
  if ('%' !in plusDecoded) return plusDecoded
  val out = StringBuilder(plusDecoded.length)
  val bytes = ArrayList<Byte>()
  fun flushBytes() {
    if (bytes.isNotEmpty()) {
      out.append(bytes.toByteArray().decodeToString())
      bytes.clear()
    }
  }
  var i = 0
  while (i < plusDecoded.length) {
    val c = plusDecoded[i]
    if (c == '%' && i + 2 < plusDecoded.length) {
      val hi = plusDecoded[i + 1].digitToIntOrNull(16)
      val lo = plusDecoded[i + 2].digitToIntOrNull(16)
      if (hi != null && lo != null) {
        bytes.add(((hi shl 4) or lo).toByte())
        i += 3
        continue
      }
    }
    flushBytes()
    out.append(c)
    i++
  }
  flushBytes()
  return out.toString()
}
