package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Generates a **self-contained web embed** from a packed preview bundle — the "js bundle" sibling
 * of the PNG+ZIP polyglot. Where the polyglot is for image viewers / re-rendering tooling, the web
 * embed is for *putting the rendered previews on a web page*: an app drops one `.js` file into its
 * site, adds a `<compose-preview-gallery>` element, and the baked previews render with no build
 * step, no framework, and no network.
 *
 * # Output
 *
 * [generate] returns a map of relative path → bytes, written verbatim by the caller:
 * - **`compose-preview-embed.js`** — a framework-free ES/UMD script that defines and registers a
 *   `<compose-preview-gallery>`
 *   [custom element](https://developer.mozilla.org/docs/Web/API/Web_components/Using_custom_elements).
 *   The previews' metadata and (by default) their PNG bytes as `data:` URIs are baked into a single
 *   `COMPOSE_PREVIEW_DATA` constant inside the script, so the one file is fully self-contained.
 * - **`index.html`** — a ready-to-open demo page that loads the script and mounts the gallery, so a
 *   double-click on the extracted directory shows the previews immediately.
 * - **`previews/<id>.png`** — only in [InlineMode.EXTERNAL]: the PNGs are written beside the script
 *   and referenced by relative URL instead of inlined, for callers that prefer cacheable image
 *   assets over one fat script.
 *
 * # Embedding in an app's page
 *
 * ```html
 * <script src="compose-preview-embed.js"></script>
 * <compose-preview-gallery></compose-preview-gallery>
 * ```
 *
 * The element renders into a
 * [shadow root](https://developer.mozilla.org/docs/Web/API/Web_components/Using_shadow_DOM) so the
 * host page's CSS can't leak in and the embed's styles can't leak out. An optional
 * `only="<id>,<id>"` attribute filters to a subset of previews (e.g. show just the cover).
 *
 * Multiple embeds (from different bundles) can coexist on one page: each script registers its data
 * under a stable key, and a gallery selects its bundle with `embed="<key>"` — the generated
 * `index.html` sets this. A single embed needs no attribute.
 */
object WebEmbed {

  /** A single preview baked into the embed. */
  data class Preview(
    val id: String,
    /** Human-readable label (the preview's function name, falling back to its id). */
    val label: String,
    /** The rendered PNG bytes baked into `previews/<id>.png`. */
    val pngBytes: ByteArray,
    /** True for the bundle's cover preview — rendered first and tagged in the UI. */
    val isCover: Boolean = false,
  )

  /** How the previews' PNG bytes are carried in the generated output. */
  enum class InlineMode {
    /** PNGs baked into the script as `data:` URIs — one self-contained `.js` file. */
    INLINE,
    /** PNGs written as `previews/<id>.png` and referenced by relative URL. */
    EXTERNAL,
  }

  /** The generated file set, plus the cover dimensions for the caller's summary. */
  data class Output(val files: Map<String, ByteArray>, val previewCount: Int)

  const val SCRIPT_NAME: String = "compose-preview-embed.js"
  const val INDEX_NAME: String = "index.html"

  /**
   * Build the web-embed file set. [title] heads the demo page and the gallery; [modulePath] is
   * shown as provenance. [previews] are rendered in order — put the cover first. With [mode] =
   * [InlineMode.EXTERNAL] the PNGs are emitted as separate `previews/<id>.png` files and referenced
   * by URL; the default [InlineMode.INLINE] bakes them into the script as `data:` URIs.
   */
  fun generate(
    title: String,
    modulePath: String,
    previews: List<Preview>,
    mode: InlineMode = InlineMode.INLINE,
  ): Output {
    val files = LinkedHashMap<String, ByteArray>()

    val items = previews.map { p ->
      val src =
        when (mode) {
          InlineMode.INLINE ->
            "data:image/png;base64," + Base64.getEncoder().encodeToString(p.pngBytes)
          // The file is written under the raw id (a single path segment — discovery strips `/`),
          // but the URL must percent-encode it: an id can carry `#`, `?`, or spaces, and a raw `#`
          // in `src` would be parsed as a URL fragment, leaving the image broken. The browser
          // decodes the request back to the raw filename, so the static file still resolves.
          InlineMode.EXTERNAL -> "previews/${urlEncodeSegment(p.id)}.png"
        }
      if (mode == InlineMode.EXTERNAL) files["previews/${p.id}.png"] = p.pngBytes
      val (w, h) = pngDimensions(p.pngBytes)
      EmbedItem(id = p.id, label = p.label, src = src, width = w, height = h, cover = p.isCover)
    }

    // A stable per-bundle key so two embeds on one page stay isolated: each script registers its
    // data under this key and a `<compose-preview-gallery embed="<key>">` element selects it.
    val key = embedKey(title, modulePath, previews.map { it.id })
    val data = EmbedData(key = key, title = title, module = modulePath, previews = items)
    val dataJson = JSON.encodeToString(EmbedData.serializer(), data)

    files[SCRIPT_NAME] = script(dataJson).toByteArray(Charsets.UTF_8)
    files[INDEX_NAME] = indexHtml(title, key).toByteArray(Charsets.UTF_8)
    return Output(files = files, previewCount = previews.size)
  }

  /**
   * Width/height from a PNG's IHDR chunk (the first chunk after the 8-byte signature: 4-byte width,
   * 4-byte height, big-endian). Returns `0 to 0` when the bytes aren't a PNG we can read, in which
   * case the component falls back to the image's intrinsic size at render time.
   */
  internal fun pngDimensions(bytes: ByteArray): Pair<Int, Int> = WebEscaping.pngDimensions(bytes)

  @Serializable
  private data class EmbedData(
    val schema: String = "compose-preview-web-embed/v1",
    /** Stable id distinguishing this embed from others on the same page. */
    val key: String,
    val title: String,
    val module: String,
    val previews: List<EmbedItem>,
  )

  @Serializable
  private data class EmbedItem(
    val id: String,
    val label: String,
    val src: String,
    val width: Int,
    val height: Int,
    val cover: Boolean,
  )

  private val JSON = Json { encodeDefaults = true }

  /**
   * The web-component script. The `COMPOSE_PREVIEW_DATA` literal is the only generated part; the
   * rest is static.
   *
   * Each script registers its data into a shared `window.__composePreviewEmbeds__` array keyed by
   * the embed's `key`, rather than closing the element over a single module-level constant. The
   * element class is defined once (guarded with `customElements.get`); a second script from a
   * *different* bundle adds its own data to the registry and re-renders existing galleries, so a
   * page can host multiple embeds without the first one's previews leaking into the others. A
   * gallery picks its data via an optional `embed="<key>"` attribute (the generated `index.html`
   * sets it); with a single embed on the page the attribute is unnecessary.
   *
   * `</script>` can't safely sit in an inline `<script>` block, so we defensively split any `</`
   * sequence — keeping the script paste-safe inline as well as referenced as an external file.
   */
  private fun script(dataJson: String): String {
    // Defensive: if this script is ever pasted *inline* into an HTML <script> block, a literal
    // `</script>` in the data would close the block early. Split the sequence so the parser can't
    // see it; JS string concatenation reassembles it. Harmless for the external-file case.
    val safeJson = dataJson.replace("</", "<\\/")
    return """
      (function () {
        "use strict";
        const COMPOSE_PREVIEW_DATA = $safeJson;
        const REGISTRY = (window.__composePreviewEmbeds__ = window.__composePreviewEmbeds__ || []);
        if (!REGISTRY.some((d) => d.key === COMPOSE_PREVIEW_DATA.key)) REGISTRY.push(COMPOSE_PREVIEW_DATA);

        const STYLE = `
          :host { display: block; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; color: #1b1b1f; }
          .cp-header { margin: 0 0 12px; }
          .cp-title { font-size: 1.1rem; font-weight: 600; margin: 0; }
          .cp-module { font-size: 0.8rem; color: #6b6b70; margin: 2px 0 0; }
          .cp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px; }
          .cp-card { border: 1px solid #e3e3e8; border-radius: 10px; overflow: hidden; background: #fff; }
          .cp-imgwrap { display: flex; align-items: center; justify-content: center; background:
            repeating-conic-gradient(#f4f4f6 0% 25%, #fff 0% 50%) 50% / 16px 16px; padding: 8px; }
          .cp-imgwrap img { max-width: 100%; height: auto; display: block; }
          .cp-meta { padding: 8px 10px; font-size: 0.82rem; display: flex; align-items: center; gap: 6px; }
          .cp-label { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
          .cp-badge { font-size: 0.65rem; text-transform: uppercase; letter-spacing: 0.04em; color: #fff;
            background: #5b5bd6; border-radius: 4px; padding: 1px 5px; }
          .cp-empty { color: #6b6b70; font-size: 0.85rem; }
          @media (prefers-color-scheme: dark) {
            :host { color: #e6e6e9; }
            .cp-module, .cp-empty { color: #a0a0a8; }
            .cp-card { border-color: #34343a; background: #1d1d20; }
            .cp-imgwrap { background: repeating-conic-gradient(#26262b 0% 25%, #1d1d20 0% 50%) 50% / 16px 16px; }
          }
        `;

        const esc = (s) => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

        class ComposePreviewGallery extends HTMLElement {
          static get observedAttributes() { return ["only", "embed"]; }
          connectedCallback() { this.render(); }
          attributeChangedCallback() { if (this.shadowRoot) this.render(); }
          // Resolve which embed's data this element shows: an explicit embed="<key>" wins; otherwise
          // the sole registered embed. With several embeds and no key we render the first and warn,
          // so a missing attribute degrades visibly rather than silently showing the wrong bundle.
          dataFor() {
            const key = this.getAttribute("embed");
            if (key) return REGISTRY.find((d) => d.key === key) || null;
            if (REGISTRY.length > 1) {
              console.warn('compose-preview: ' + REGISTRY.length + ' embeds on this page; set embed="<key>" to pick one. Showing the first.');
            }
            return REGISTRY[0] || null;
          }
          render() {
            const root = this.shadowRoot || this.attachShadow({ mode: "open" });
            const data = this.dataFor();
            if (!data) {
              const key = this.getAttribute("embed");
              root.innerHTML = '<style>' + STYLE + '</style><p class="cp-empty">No compose-preview embed' +
                (key ? ' with key "' + esc(key) + '"' : '') + ' found on this page.</p>';
              return;
            }
            const only = (this.getAttribute("only") || "").split(",").map(s => s.trim()).filter(Boolean);
            const all = (data.previews || []);
            const previews = only.length ? all.filter(p => only.includes(p.id)) : all;
            const cards = previews.map(p => {
              const dim = (p.width > 0 && p.height > 0) ? ` width="${'$'}{p.width}" height="${'$'}{p.height}"` : "";
              const badge = p.cover ? '<span class="cp-badge">cover</span>' : "";
              return (
                '<figure class="cp-card">' +
                  '<div class="cp-imgwrap"><img loading="lazy" alt="' + esc(p.label) + '" src="' + esc(p.src) + '"' + dim + '></div>' +
                  '<figcaption class="cp-meta">' + badge + '<span class="cp-label" title="' + esc(p.id) + '">' + esc(p.label) + '</span></figcaption>' +
                '</figure>'
              );
            }).join("");
            const body = previews.length
              ? '<div class="cp-grid">' + cards + '</div>'
              : '<p class="cp-empty">No previews in this embed.</p>';
            root.innerHTML =
              '<style>' + STYLE + '</style>' +
              '<header class="cp-header">' +
                '<p class="cp-title">' + esc(data.title) + '</p>' +
                (data.module ? '<p class="cp-module">' + esc(data.module) + '</p>' : '') +
              '</header>' +
              body;
          }
        }

        if (typeof customElements !== "undefined" && !customElements.get("compose-preview-gallery")) {
          customElements.define("compose-preview-gallery", ComposePreviewGallery);
        }
        // A later script (a second bundle) registers its data after the element was already upgraded
        // by the first script's define(), so re-render existing galleries now that this embed exists.
        if (typeof document !== "undefined") {
          document.querySelectorAll("compose-preview-gallery").forEach((el) => {
            if (typeof el.render === "function") el.render();
          });
        }
      })();
      """
      .trimIndent() + "\n"
  }

  /** Minimal demo page that mounts the gallery from the sibling script. */
  private fun indexHtml(title: String, key: String): String {
    val safeTitle = htmlEscape(title)
    return """
      <!doctype html>
      <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>$safeTitle — compose-preview</title>
          <style>
            body { margin: 0; padding: 24px; background: #fafafb; }
            @media (prefers-color-scheme: dark) { body { background: #161618; } }
          </style>
        </head>
        <body>
          <compose-preview-gallery embed="$key"></compose-preview-gallery>
          <script src="$SCRIPT_NAME"></script>
        </body>
      </html>
      """
      .trimIndent() + "\n"
  }

  /**
   * Percent-encode [s] for safe use as a single URL path segment (RFC 3986). Used for
   * `--external-images` `src` URLs so a preview id containing `#`, `?`, `&`, or a space resolves to
   * its `previews/<id>.png` file instead of being mangled by URL parsing. Delegates to the shared
   * [WebEscaping.urlEncodeSegment].
   */
  internal fun urlEncodeSegment(s: String): String = WebEscaping.urlEncodeSegment(s)

  /**
   * A short, stable key distinguishing one embed from another on the same page. Derived from the
   * title, module, and preview ids so two embeds built from different bundles get different keys
   * (and re-generating the same bundle yields the same key). Hex, so it's safe in an HTML
   * attribute.
   */
  internal fun embedKey(title: String, modulePath: String, previewIds: List<String>): String {
    val material = (listOf(title, modulePath) + previewIds).joinToString("\u0000")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
    return digest.take(6).joinToString("") { "%02x".format(it) }
  }

  private fun htmlEscape(s: String): String = WebEscaping.htmlEscape(s)
}
