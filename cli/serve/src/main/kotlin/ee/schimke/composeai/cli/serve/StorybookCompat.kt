package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * Storybook-compatibility shim for the `compose-preview serve` surface. Emits the two tiny
 * contracts the whole downstream Storybook ecosystem (Chromatic, Percy, storycap/reg-suit,
 * BackstopJS, the `@storybook/test-runner`, the various Storybook MCP servers) is built on:
 *
 * 1. **`/index.json`** — the stories index: a `{ "v": 5, "entries": { <storyId>: … } }` manifest a
 *    tool crawls to enumerate every renderable unit and its stable id. This is Storybook's
 *    build-time-generated, documented, versioned contract (renamed from `stories.json` in SB7,
 *    parameter-free since SB8). See [Index] / [Entry].
 * 2. **`iframe.html?id=<storyId>`** — render one story in isolation, no chrome. A screenshot tool
 *    navigates a browser here and captures the viewport. We answer with a minimal HTML page that
 *    embeds the freshly-rendered preview PNG as a `data:` URI ([iframePage]) — self-contained, so
 *    no token has to be threaded onto a sub-resource `<img src>`.
 *
 * The **story id** is the join key between the two, exactly as in Storybook. We mint it from the
 * preview the way CSF's `toId(title, name)` does — `sanitize(title)--sanitize(name)`, kebab-cased —
 * so ids look native to a Storybook consumer. Resolution back to our native preview id ([Story.id])
 * goes through [resolvePreviewId], which also accepts a raw native id verbatim so our own tools /
 * humans can deep-link `iframe.html?id=<fqn>` without knowing the minted form.
 *
 * Pure and IO-free (no ktor types) so the id/index/page logic is unit-testable in isolation,
 * mirror- ing [ServeUrls]; the HTTP glue lives in [ServeHttpServer].
 */
object StorybookCompat {

  /**
   * Storybook `index.json` schema version. Storybook has emitted `"v": 5` since SB8 (SB7 used 4,
   * the legacy `stories.json` used 3 with a different, parameter-carrying shape). Consumers key off
   * the `entries` map regardless; the version is advisory.
   */
  const val INDEX_VERSION: Int = 5

  /**
   * Synthetic `importPath` prefix. We have no CSF source file, so we encode the native preview id
   * here — informative for a human, and ignored by the visual/remote-URL tools that only navigate
   * `iframe.html?id=`.
   */
  private const val IMPORT_PATH_PREFIX = "virtual:compose-preview/"

  /** Tag stamped on every entry so a consumer can tell these stories are Compose previews. */
  private val TAGS = listOf("compose-preview")

  /** The Storybook stories index served at `/index.json`. */
  @Serializable data class Index(val v: Int = INDEX_VERSION, val entries: Map<String, Entry>)

  /**
   * One entry in the [Index]. Fields mirror a Storybook `'story'` index entry: [id] is the stable
   * story id (also the map key), [title] the sidebar grouping path, [name] the story name, and
   * [importPath] the (here synthetic) source module. [type] is always `"story"` — we emit no docs
   * entries.
   */
  @Serializable
  data class Entry(
    val id: String,
    val title: String,
    val name: String,
    val importPath: String,
    val type: String = "story",
    val tags: List<String> = TAGS,
  )

  /**
   * A resolved story: its minted Storybook [storyId] and the native compose-preview [previewId].
   */
  data class Story(val storyId: String, val previewId: String, val title: String, val name: String)

  /**
   * CSF `sanitize`: lowercase, collapse every run of non-`[a-z0-9]` characters to a single `-`, and
   * trim leading/trailing `-`. Our preview ids are ASCII Kotlin FQNs, so this reproduces
   * Storybook's id shape faithfully (Storybook's own regex replaces punctuation/space with `-` and
   * collapses).
   */
  fun sanitize(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  /**
   * CSF `toId(kind, name)` → `sanitize(kind)--sanitize(name)`. When either side sanitizes to blank
   * (e.g. a symbol-only title) it's dropped rather than emitting a dangling `--`, so the result is
   * always a usable slug.
   */
  fun toId(title: String, name: String): String =
    listOf(sanitize(title), sanitize(name)).filter { it.isNotBlank() }.joinToString("--")

  /**
   * Minted stories for [previews], in list order, with deterministic collision suffixes (`-2`,
   * `-3`, …) so the story id → preview id mapping stays 1:1 even when two previews derive the same
   * slug. Both [index] and [resolvePreviewId] go through this, so `/index.json` and
   * `iframe.html?id=` can never disagree.
   */
  fun stories(previews: List<ServePreview>): List<Story> {
    val used = HashSet<String>()
    return previews.map { preview ->
      val title = deriveTitle(preview.id)
      val name = deriveName(preview)
      val base = toId(title, name).ifBlank { sanitize(preview.id).ifBlank { "preview" } }
      var candidate = base
      var n = 2
      while (!used.add(candidate)) candidate = "$base-${n++}"
      Story(storyId = candidate, previewId = preview.id, title = title, name = name)
    }
  }

  /** Build the `/index.json` payload from a session's preview list. */
  fun index(previews: List<ServePreview>): Index =
    Index(
      entries =
        stories(previews).associate { s ->
          s.storyId to
            Entry(
              id = s.storyId,
              title = s.title,
              name = s.name,
              importPath = IMPORT_PATH_PREFIX + s.previewId,
            )
        }
    )

  /**
   * Resolve a story id from `iframe.html?id=` to a native preview id. The minted [stories] ids are
   * the `/index.json` contract, so they're matched **first** — an advertised entry always
   * round-trips even if some other preview's native id happens to equal this minted id. Only when
   * the id matches no advertised story does the raw-native-id escape hatch apply (deep-linking a
   * preview by its `<fqn>`), so the hatch can never shadow an indexed story. Returns null when
   * nothing matches.
   */
  fun resolvePreviewId(storyId: String, previews: List<ServePreview>): String? {
    stories(previews)
      .firstOrNull { it.storyId == storyId }
      ?.let {
        return it.previewId
      }
    return previews.firstOrNull { it.id == storyId }?.id
  }

  /**
   * The isolation page for `iframe.html?id=<storyId>`: a chrome-free HTML document that shows the
   * rendered [pngBytes] at their intrinsic pixel size on a white ground, so a screenshot tool
   * captures exactly the preview. The PNG is inlined as a `data:` URI — one request, no token on a
   * sub-resource. [storyId] is HTML-escaped into the title/alt for context.
   */
  fun iframePage(storyId: String, pngBytes: ByteArray): String {
    val (w, h) = WebEscaping.pngDimensions(pngBytes)
    val b64 = Base64.getEncoder().encodeToString(pngBytes)
    val esc = WebEscaping.htmlEscape(storyId)
    val sizeAttrs = if (w > 0 && h > 0) " width=\"$w\" height=\"$h\"" else ""
    return buildString {
      append("<!doctype html>\n")
      append("<html><head><meta charset=\"utf-8\">\n")
      append("<title>").append(esc).append(" · compose-preview</title>\n")
      append("<style>html,body{margin:0;padding:0;background:#fff}img{display:block}</style>\n")
      append("</head><body>\n")
      append("<img alt=\"").append(esc).append("\"").append(sizeAttrs)
      append(" src=\"data:image/png;base64,").append(b64).append("\">\n")
      append("</body></html>\n")
    }
  }

  /**
   * The SVG isolation page for `iframe.html?id=<storyId>&format=svg`: the figma-svg export
   * ([svgBytes] from `/render/<id>.svg`) embedded as an **`<img>` with a `data:image/svg+xml` URI**
   * — a still-**vector**, resolution-independent render that the DOM-serializing visual tools
   * (Percy, Chromatic, Applitools) re-render in their own cloud browsers, unlike the
   * fixed-resolution raster PNG page.
   *
   * **Deliberately an `<img>`, not inline `<svg>` markup** (security): a serve host can hand back
   * the `figma/<slug>.svg` bytes of an *unverified* catalog (repo-controlled, unsanitised —
   * `ServeCatalogStore.fetchFigmaSvgs`). Inlining that as markup into a same-origin document would
   * let a hostile SVG run `<script>` / `on*` handlers. SVG referenced through `<img>` is processed
   * in the browser's restricted mode — no script execution, no external fetches, everywhere
   * including the downstream tool's browser — so untrusted bytes are inert while still rendering as
   * vector. The bytes are base64'd verbatim (no need to strip the XML prolog for a data URI).
   * [storyId] is HTML-escaped into the title/alt.
   */
  fun iframeSvgPage(storyId: String, svgBytes: ByteArray): String {
    val b64 = Base64.getEncoder().encodeToString(svgBytes)
    val esc = WebEscaping.htmlEscape(storyId)
    return buildString {
      append("<!doctype html>\n")
      append("<html><head><meta charset=\"utf-8\">\n")
      append("<title>").append(esc).append(" · compose-preview</title>\n")
      append("<style>html,body{margin:0;padding:0;background:#fff}img{display:block}</style>\n")
      append("</head><body>\n")
      append("<img alt=\"").append(esc).append("\" src=\"data:image/svg+xml;base64,")
      append(b64).append("\">\n")
      append("</body></html>\n")
    }
  }

  /**
   * Sidebar grouping ([Entry.title]) for a native preview id: the enclosing class/file simple name
   * for an FQN (`com.example.PreviewsKt.RedBoxPreview…` → `Previews`, trailing `Kt` dropped), the
   * component slug for a catalog id (`button__dark` → `button`), else the id itself.
   */
  private fun deriveTitle(id: String): String {
    val beforeAxis = id.substringBefore("__")
    if (beforeAxis.contains('.')) {
      val container = beforeAxis.substringBeforeLast('.')
      val simple = container.substringAfterLast('.')
      return simple.removeSuffix("Kt").ifBlank { simple }.ifBlank { beforeAxis }
    }
    return beforeAxis
  }

  /**
   * Story [Entry.name] for a preview: its human label when it has one (the viewer already shows
   * it), else the trailing segment of the native id.
   */
  private fun deriveName(preview: ServePreview): String {
    val label = preview.label.trim()
    if (label.isNotBlank()) return label
    val beforeAxis = preview.id.substringBefore("__")
    return if (beforeAxis.contains('.')) beforeAxis.substringAfterLast('.') else beforeAxis
  }
}
