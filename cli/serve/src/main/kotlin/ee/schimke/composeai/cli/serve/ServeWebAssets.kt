package ee.schimke.composeai.cli.serve

import java.security.MessageDigest

/** Static browser assets for [ServeWeb], served from classpath resources under `/assets/serve/`. */
internal object ServeWebAssets {
  private const val RESOURCE_DIR = "/ee/schimke/composeai/cli/serve/assets"
  private const val URL_BASE = "/assets/serve"

  private val contentTypes =
    mapOf(
      "serve.css" to "text/css; charset=utf-8",
      "playground.css" to "text/css; charset=utf-8",
      // Vendored CodeMirror 5 (MIT), loaded ONLY by the playground page — the catalog browsing
      // pages never pay for it. Served from our own origin rather than a CDN: this host is a
      // public preview server, so an external script would add a third-party dependency to a
      // code-running surface and leak visitors to it.
      "codemirror.css" to "text/css; charset=utf-8",
      "codemirror.js" to "text/javascript; charset=utf-8",
      // Vue is one cacheable runtime followed by exactly one page-sized component bundle. Keeping
      // these committed lets the Gradle and release chains stay node-free; `npm run verify`
      // rebuilds
      // all of them and enforces both source sync and per-page byte budgets.
      "vue-runtime.js" to "text/javascript; charset=utf-8",
      "catalog-components.js" to "text/javascript; charset=utf-8",
      "compare-components.js" to "text/javascript; charset=utf-8",
      "design-components.js" to "text/javascript; charset=utf-8",
      "parity-components.js" to "text/javascript; charset=utf-8",
      "viewer-components.js" to "text/javascript; charset=utf-8",
      // The standalone Remote Compose document player needs the shared font preloader but no Vue.
      "remote-compose.js" to "text/javascript; charset=utf-8",
      // The page-shell bundle, the second half of the same build. Carries what EVERY page needs —
      // `window.cpUrlState` (formerly `url-state.js`) and the Page theme setting (formerly
      // `page-theme.js`) — so `ServeWeb.document` emits it unconditionally, ahead of the surface's
      // own scripts, because they read those globals. Its one custom element is the small, non-Vue
      // report classifier used across index and component pages, so this bundle carries no Vue;
      // folding the shell into a surface bundle
      // would put Vue on the front door, whose imagery is prebaked precisely so a visit
      // costs the HTML and nothing else.
      "serve-chrome.js" to "text/javascript; charset=utf-8",
      // Opt-in site-wide power-user navigation. Separate from the Vue bundle because Settings is
      // on every page while only pages with Vue controls load `vue-runtime.js`.
      "keyboard-navigation.js" to "text/javascript; charset=utf-8",
      // The report capture tool: grab a frame of the current tab, crop it to a dragged region or a
      // pointed-at element, and hand the PNG to the clipboard. Its own bundle, and fetched only
      // when someone opens the report launcher — it is several kilobytes that matter to the
      // fraction of visits that file something, and `serve-chrome.js` is on every page including
      // the front door.
      "report-capture.js" to "text/javascript; charset=utf-8",
      "viewer.js" to "text/javascript; charset=utf-8",
      "format-compare.js" to "text/javascript; charset=utf-8",
      // The acceptance band and the engine behind it: the known-difference contract's whole
      // reference implementation, shared verbatim with `scripts/design-artifacts/` so the browser
      // and the offline driver cannot disagree about what an acceptance means. Its own bundle, and
      // emitted only by a focused comparison on a catalog that has actually published a document —
      // it is the heaviest asset on that page and there is nothing for it to do anywhere else.
      "known-differences.js" to "text/javascript; charset=utf-8",
    )

  private val cache = java.util.concurrent.ConcurrentHashMap<String, Asset>()

  data class Asset(
    val bytes: ByteArray,
    val contentType: String,
    val etag: String,
    val version: String,
  )

  fun href(name: String): String = "$URL_BASE/${load(name)?.version ?: "missing"}/$name"

  fun load(name: String): Asset? {
    if (name !in contentTypes) return null
    cache[name]?.let {
      return it
    }
    val asset = run {
      val bytes =
        ServeWebAssets::class.java.getResourceAsStream("$RESOURCE_DIR/$name")?.use {
          it.readBytes()
        } ?: return null
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      val etag =
        "\"" +
          bytes.size.toString(16) +
          "-" +
          digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } +
          "\""
      Asset(
        bytes = bytes,
        contentType = contentTypes.getValue(name),
        etag = etag,
        version = etag.trim('"'),
      )
    }
    cache.putIfAbsent(name, asset)
    return cache.getValue(name)
  }
}
