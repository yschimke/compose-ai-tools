@file:OptIn(
  ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlin.js.ExperimentalJsExport::class,
  ExperimentalEncodingApi::class,
)

package com.example.cmpwasmcatalog

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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
  // The `?…` query is the clean baked default (the viewer's `data-wasm-src`); the viewer carries
  // its
  // initial session overrides in the `#…` fragment instead, so [baseParams] stays the true default
  // and clearing a control later (an empty [applyOverrides] patch) reverts to it rather than
  // sticking.
  baseParams = parseQuery(locationSearch())
  renderParams.value = baseParams + parseQuery(locationHash())
  ComposeViewport(viewportContainerId = "composeApp") {
    // Text parity with the baked snapshot needs the same typefaces the Android renderer
    // rasterizes: load the fonts the `fonts.json` manifest declares (self-hosted beside the app,
    // `?fontsBase=` overridable) and hold the catalog composition — and therefore the first-frame
    // signal the embedding viewer swaps on — until they resolve, so the first revealed frame is
    // already shaped by the right fonts. A fetch failure or the timeout degrades to the CMP
    // bundled font instead of blocking the reveal.
    var fonts by remember { mutableStateOf<FontsState>(FontsState.Loading) }
    LaunchedEffect(Unit) {
      fonts = FontsState.Ready(withTimeoutOrNull(FONT_LOAD_TIMEOUT_MS) { loadCatalogFonts() })
    }
    val loaded = fonts as? FontsState.Ready ?: return@ComposeViewport
    val params by renderParams
    val id = params["id"] ?: catalogComponents.keys.first()
    val dark = params["uiMode"] == "dark"
    // Clamp to the viewer slider's range so a crafted query can't blow up layout.
    val fontScale = params["fontScale"]?.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1f
    val rtl = isRtlLocale(params["localeTag"])
    // `background=off` drops the sticker's surface fill so just the component shows on the
    // stage-matching checkerboard the app paints behind it (see CatalogApp — the surface can't be
    // truly transparent). `bgPhase=<x>,<y>` is the embedding viewer's stage-pattern tile origin in
    // this frame's CSS-px coordinates, so that checkerboard continues the page's cells exactly.
    val showBackground = params["background"] !in setOf("off", "false", "none", "transparent")
    val checkerPhase = parsePhase(params["bgPhase"])
    CatalogApp(
      id,
      dark,
      fontScale,
      rtl,
      showBackground,
      checkerPhase,
      loaded.family,
      ::postFirstFrame,
    )
  }
}

private const val FONT_LOAD_TIMEOUT_MS = 8_000L

/** Font loading state: the catalog composes only once resolved (family null ⇒ bundled default). */
private sealed interface FontsState {
  data object Loading : FontsState

  data class Ready(val family: FontFamily?) : FontsState
}

/**
 * Load the catalog's fonts **by URL**, driven by the `fonts.json` manifest served beside them: for
 * each `role: "default"` family entry, fetch its files and build one [FontFamily] used for the
 * whole M3 type scale. The default base is `./fonts/` — vendored beside the app (self-hosted,
 * Apache 2.0), so the bundle stays offline-clean behind an egress proxy — and an operator can point
 * `?fontsBase=` at any http(s) origin that serves the same layout with CORS (the sandboxed iframe
 * has an opaque origin, so cross-origin fonts need `ACAO`). A base without a manifest falls back to
 * the fixed Roboto pair (the pre-manifest `?fontsBase=` contract); null on any failure ⇒ the caller
 * falls back to the CMP bundled font.
 *
 * The host `index.html` starts these same fetches at document load (`__cpPrefetch*`), in parallel
 * with the Wasm boot, and the fetch bridge below consumes those in-flight promises — so by the time
 * this runs the bytes are usually already here. (It must be the *iframe's own* prefetch: the
 * sandbox's opaque origin gets its own HTTP-cache partition, so the embedding viewer page can't
 * warm anything for it.)
 */
private suspend fun loadCatalogFonts(): FontFamily? {
  val raw = baseParams["fontsBase"] ?: "./fonts/"
  // A fetch URL, not code — but still refuse non-http(s) absolute schemes (javascript:, data:).
  val base =
    (if (raw.endsWith("/")) raw else "$raw/").takeIf {
      !it.contains(":") || it.startsWith("http:") || it.startsWith("https:")
    } ?: "./fonts/"
  val entries =
    runCatching { parseFontsManifest(fetchText(base + "fonts.json")) }
      .getOrDefault(emptyList())
      .filter { it.role == "default" }
  return try {
    if (entries.isEmpty()) {
      // Legacy layout: a fontsBase serving bare TTFs without a manifest (the #2174 contract).
      FontFamily(
        Font(identity = "Roboto-Regular", data = fetchBytes(base + "Roboto-Regular.ttf")),
        Font(
          identity = "Roboto-Medium",
          data = fetchBytes(base + "Roboto-Medium.ttf"),
          weight = FontWeight.Medium,
        ),
      )
    } else {
      FontFamily(
        entries.map { e ->
          Font(
            identity = e.file,
            data = fetchBytes(base + e.file),
            weight = FontWeight(e.weight),
            style = if (e.italic) FontStyle.Italic else FontStyle.Normal,
          )
        }
      )
    }
  } catch (e: Throwable) {
    consoleWarn("compose-ai wasm catalog: font load failed (${e.message}); using bundled font")
    null
  }
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
 * Parse `fonts.json` (`{families: [{name, role, fonts: [{file, weight, style}]}]}`). JSON parsing
 * happens on the JS side ([flattenFontsManifest] — no serialization dependency for one small
 * manifest); this validates each flattened row. Rows with a missing/unsafe `file` (path traversal,
 * absolute scheme) are dropped; unknown roles are kept for the caller to filter, so future roles
 * (generic-family mappings, named families) stay additive.
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
 * `JSON.parse` the manifest and flatten it to `role␀name␀file␀weight␀style` rows (␁-joined) — the
 * shape that crosses the Wasm↔JS boundary as one string. Null/empty on malformed JSON.
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
 * `fetch(url)` → base64 of the response body. Base64 is the bridge shape because Kotlin/Wasm can't
 * take a `Uint8Array` across the interop boundary as a `ByteArray`; the chunked
 * `String.fromCharCode` keeps each `apply` under the JS argument-count limit.
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
 * Cancellable, so `withTimeoutOrNull` around the font load actually unblocks on a stalled origin (a
 * plain `suspendCoroutine` never observes cancellation and would hold the first frame forever). The
 * JS-side `AbortSignal.timeout` additionally kills the underlying request itself, slightly after
 * the Kotlin timeout would have abandoned it.
 */
private suspend fun fetchBytes(url: String): ByteArray = suspendCancellableCoroutine { cont ->
  fetchAsBase64(url, timeoutMs = (FONT_LOAD_TIMEOUT_MS + 2_000L).toInt())
    .then { s ->
      if (cont.isActive) cont.resume(Base64.decode(s.toString()))
      null
    }
    .catch { e ->
      if (cont.isActive)
        cont.resumeWithException(IllegalStateException(e?.toString() ?: "fetch failed"))
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
      if (cont.isActive)
        cont.resumeWithException(IllegalStateException(e?.toString() ?: "fetch failed"))
      null
    }
}

private fun consoleWarn(message: String): Unit = js("console.warn(message)")

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
 * Tell the embedding `serve` viewer the first real frame is on the canvas ("cp-wasm-ready"): it
 * keeps the baked snapshot on-stage until this arrives, so ticking "Run in browser (Wasm)" swaps
 * with no blank/white flash while the ~MBs of Wasm load. A no-op when the page is top-level
 * (`window.parent === window`; the string bounces to our own message listener, where it parses to
 * an empty override patch).
 */
private fun postFirstFrame() {
  postToParent("cp-wasm-ready")
}

private fun postToParent(message: String): Unit = js("window.parent.postMessage(message, '*')")

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
 * Decode one query key/value: `+` → space, then `%XX` percent-escapes (UTF-8). The embedding viewer
 * builds patches with `encodeURIComponent`, which escapes even the comma inside `bgPhase`
 * (`12.00,4.00` → `12.00%2C4.00`) — without this decode the phase parser would silently fall back
 * to `Offset.Zero` and the checkerboard would seam. Hand-rolled (not JS `decodeURIComponent`) so a
 * malformed escape degrades to literal text instead of throwing across the JS boundary.
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
