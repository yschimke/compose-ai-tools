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
      // The Lit component bundle, built from `cli/serve-web/` and committed here so the Gradle
      // build and the release chain stay node-free (`npm run verify` in that directory, wired into
      // CI, fails if the committed bytes drift from the source). Carries every ported component:
      // `<cp-bg-toggle>` (the Transparent toggle shared by the catalog grid and the viewer),
      // `<cp-backend-badge>` (the viewer stage's provenance badge, formerly `backend-badge.js`) and
      // `<cp-group-memory>` (the control drawers' remembered open state, formerly
      // `viewer-groups.js`), `<cp-viewer-drawers>` (the viewer's drawers, phone row order, theme
      // toggle value and component filter, formerly `viewer-drawers.js`), plus `window.cpRcFonts`,
      // the Remote Compose font preloader that was `rc-fonts.js`. Loaded whole rather than
      // per-page: Lit is ~6 kB gzipped and an element
      // whose tag isn't on the page costs nothing but its bytes, so splitting would buy less than
      // it costs. The heavy per-page scripts selective loading exists for (`codemirror.js`,
      // `viewer.js`, `format-compare.js`) are untouched and keep their own tags.
      "serve-components.js" to "text/javascript; charset=utf-8",
      // The page-shell bundle, the second half of the same build. Carries what EVERY page needs —
      // `window.cpUrlState` (formerly `url-state.js`) and the Page theme setting (formerly
      // `page-theme.js`) — so `ServeWeb.document` emits it unconditionally, ahead of the surface's
      // own scripts, because they read those globals. Neither module is a custom element, so this
      // bundle carries no Lit and lands around 1 kB gzipped; folding it into the component bundle
      // would put Lit's 12 kB on the front door, whose imagery is prebaked precisely so a visit
      // costs the HTML and nothing else.
      "serve-chrome.js" to "text/javascript; charset=utf-8",
      // Opt-in site-wide power-user navigation. Separate from the Lit bundle because Settings is
      // on every page while only pages with Lit controls currently load serve-components.js.
      "keyboard-navigation.js" to "text/javascript; charset=utf-8",
      // The grid's long-press live lane; loaded only by a catalog page whose session can actually
      // stream (see [ServeWeb.catalogLiveScript]).
      "catalog-live.js" to "text/javascript; charset=utf-8",
      "viewer.js" to "text/javascript; charset=utf-8",
      "format-compare.js" to "text/javascript; charset=utf-8",
      // The design page's node measuring and render-swap controls; loaded only by a
      // `/{system}/pages/{id}` view, which exists only for a catalog that published one.
      "design-page.js" to "text/javascript; charset=utf-8",
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
