package ee.schimke.composeai.cli

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
          InlineMode.EXTERNAL -> "previews/${p.id}.png"
        }
      if (mode == InlineMode.EXTERNAL) files["previews/${p.id}.png"] = p.pngBytes
      val (w, h) = pngDimensions(p.pngBytes)
      EmbedItem(id = p.id, label = p.label, src = src, width = w, height = h, cover = p.isCover)
    }

    val data = EmbedData(title = title, module = modulePath, previews = items)
    val dataJson = JSON.encodeToString(EmbedData.serializer(), data)

    files[SCRIPT_NAME] = script(dataJson).toByteArray(Charsets.UTF_8)
    files[INDEX_NAME] = indexHtml(title).toByteArray(Charsets.UTF_8)
    return Output(files = files, previewCount = previews.size)
  }

  /**
   * Width/height from a PNG's IHDR chunk (the first chunk after the 8-byte signature: 4-byte width,
   * 4-byte height, big-endian). Returns `0 to 0` when the bytes aren't a PNG we can read, in which
   * case the component falls back to the image's intrinsic size at render time.
   */
  internal fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
    // 8 (sig) + 4 (len) + 4 ("IHDR") + 4 (w) + 4 (h) = need at least 24 bytes.
    if (bytes.size < 24) return 0 to 0
    if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte()) return 0 to 0
    fun be(off: Int) =
      ((bytes[off].toInt() and 0xff) shl 24) or
        ((bytes[off + 1].toInt() and 0xff) shl 16) or
        ((bytes[off + 2].toInt() and 0xff) shl 8) or
        (bytes[off + 3].toInt() and 0xff)
    return be(16) to be(20)
  }

  @Serializable
  private data class EmbedData(
    val schema: String = "compose-preview-web-embed/v1",
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
   * rest is static. Wrapped in an IIFE and guarded with `customElements.get` so loading it twice
   * (or alongside another embed) is harmless. `</script>` can't appear in the JSON (kotlinx escapes
   * `<`? — no; it does not, so we defensively split any `</` sequence) which keeps the script safe
   * to also paste inline into a page.
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

        class ComposePreviewGallery extends HTMLElement {
          static get observedAttributes() { return ["only"]; }
          connectedCallback() { this.render(); }
          attributeChangedCallback() { if (this.shadowRoot) this.render(); }
          render() {
            const root = this.shadowRoot || this.attachShadow({ mode: "open" });
            const only = (this.getAttribute("only") || "").split(",").map(s => s.trim()).filter(Boolean);
            const all = (COMPOSE_PREVIEW_DATA.previews || []);
            const previews = only.length ? all.filter(p => only.includes(p.id)) : all;
            const esc = (s) => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
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
                '<p class="cp-title">' + esc(COMPOSE_PREVIEW_DATA.title) + '</p>' +
                (COMPOSE_PREVIEW_DATA.module ? '<p class="cp-module">' + esc(COMPOSE_PREVIEW_DATA.module) + '</p>' : '') +
              '</header>' +
              body;
          }
        }

        if (typeof customElements !== "undefined" && !customElements.get("compose-preview-gallery")) {
          customElements.define("compose-preview-gallery", ComposePreviewGallery);
        }
        if (typeof window !== "undefined") { window.composePreviewData = COMPOSE_PREVIEW_DATA; }
      })();
      """
      .trimIndent() + "\n"
  }

  /** Minimal demo page that mounts the gallery from the sibling script. */
  private fun indexHtml(title: String): String {
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
          <compose-preview-gallery></compose-preview-gallery>
          <script src="$SCRIPT_NAME"></script>
        </body>
      </html>
      """
      .trimIndent() + "\n"
  }

  private fun htmlEscape(s: String): String =
    buildString(s.length) {
      for (c in s) {
        when (c) {
          '&' -> append("&amp;")
          '<' -> append("&lt;")
          '>' -> append("&gt;")
          '"' -> append("&quot;")
          else -> append(c)
        }
      }
    }
}
