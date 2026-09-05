package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * The **published** Remote Compose player comparison, served from a catalog's delivery branch.
 *
 * The offline `rc-compare` pipeline (`scripts/design-artifacts/rc-compare.mjs`) already renders
 * every `ir/<id>.rc` document through every player it can reach — the vendored TypeScript
 * `RC.RcdPlayer`, AndroidX's Compose-embedded `RcPlayer`, the Compose Desktop / Skiko player, the
 * CMP/Wasm player — pixel-diffs each against the baked render, and publishes the lot beside the
 * catalog: one PNG per lane per preview plus `rc-compare-summary.json`. That is the same data
 * `rc-compare.html` is built from.
 *
 * The serve page reuses it rather than re-deriving it. Rendering a document in the visitor's
 * browser is the slow path (one `.rc` fetch + a canvas render per preview, and only for the one
 * player that runs in a browser); replaying the published renders is a handful of `<img>` loads and
 * shows **every** player, with the build-time `pixelmatch` numbers already computed.
 *
 * ## Shape
 *
 * [RcCompareManifest] is what [ServeCatalogStore] stages into `<catalog>/rc-compare/index.json`
 * after fetching the lane PNGs, and what [ServeWeb] inlines into the compare page for the client
 * script. It is **catalog-keyed**: the published summary keys rows by the daemon preview id
 * (`…CatalogPreviewsKt.AppCardRemote_width_320dp…`), the served routes by the catalog id
 * (`appcard__ideal__default__compact`), so staging re-keys through the catalog's alias exactly like
 * [ServeCatalogStore.extractCatalogRcDocs] does for the documents themselves.
 *
 * Staged image names are `<lane-id>/<slot>.png` — a fixed lane vocabulary and an integer slot, so
 * no published id ever reaches the filesystem or a URL and there is nothing to escape. Slots are
 * per *daemon* id, so two catalog ids sharing a source preview share one set of files.
 */
@Serializable
data class RcCompareManifest(
  val schema: String = SCHEMA,
  /**
   * The pixelmatch threshold the build-time diffs used, carried so the client-side player↔player
   * diff (which nothing precomputed can answer) scores on the same scale.
   */
  val threshold: Double = DEFAULT_THRESHOLD,
  /** The player columns this catalog actually has, in display order. `baked` is always first. */
  val lanes: List<RcCompareLane> = emptyList(),
  val rows: List<RcCompareRow> = emptyList(),
) {
  companion object {
    const val SCHEMA = "compose-preview-rc-compare/v1"
    const val DEFAULT_THRESHOLD = 0.1
  }
}

/** One player column. [short] is the compact label used on the per-row score chips. */
@Serializable data class RcCompareLane(val id: String, val label: String, val short: String)

/** One preview's row: the published renders keyed by lane id. */
@Serializable
data class RcCompareRow(
  /** Served catalog preview id — the same id `/p/<id>` and `/render/<id>.png` use. */
  val previewId: String,
  val width: Int = 0,
  val height: Int = 0,
  /**
   * The baked render carries no opaque pixel at all, so it is no reference: a player that also drew
   * nothing would score a perfect 0%. Such a row is shown but reads `no reference` whenever the
   * baked lane is the selected reference (two *player* renders still compare normally).
   */
  val referenceBlank: Boolean = false,
  val lanes: Map<String, RcCompareCell> = emptyMap(),
)

/** One lane's published result for one preview. */
@Serializable
data class RcCompareCell(
  val rendered: Boolean = false,
  /**
   * Staged image name (`<lane>/<slot>.png`), served under `/<system>/rc-compare/`. Empty ⇒ none.
   */
  val render: String = "",
  /** The build-time pixel diff against the baked render. Empty for that lane itself. */
  val diff: String = "",
  /** Build-time mismatch against the baked render. Null when unrendered or unscorable. */
  val mismatchPct: Double? = null,
  val mismatchPx: Long? = null,
  /** Why this lane has no render, when it doesn't (the player's own reason). */
  val note: String = "",
)

/**
 * A player lane as it exists **on the delivery branch**: which directories hold its renders and its
 * build-time diffs, and how to read its fields out of a published summary row.
 *
 * This mirrors `render-rc-compare-html.mjs`'s `LANES` — the same columns in the same order, so the
 * serve page and the published `rc-compare.html` show the same thing. `baked` is not a player (it
 * is the reference everything was scored against) but it is a first-class column and a first-class
 * reference choice, so it lives in the same list.
 */
// Public with `ServeRcCompare` above, which exposes `LANES: List<RcLaneSource>`: `internal` is
// module-scoped and the `:server` call sites moved out of this module.
data class RcLaneSource(
  val id: String,
  val label: String,
  val short: String,
  /** Branch dir holding this lane's renders. */
  val renderDir: String,
  /** Branch dir holding its build-time diff against the baked render; null for that lane. */
  val diffDir: String?,
  /** Whether this lane ran at all for a given row — null ⇒ the lane was not part of the run. */
  val rendered: (RcSummaryRow) -> Boolean?,
  val mismatchPct: (RcSummaryRow) -> Double?,
  val mismatchPx: (RcSummaryRow) -> Long?,
  val note: (RcSummaryRow) -> String?,
)

// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
object ServeRcCompare {
  /** Staged subdir under the served catalog root, and the URL segment it is served at. */
  const val DIRECTORY = "rc-compare"
  const val INDEX_FILE = "index.json"

  /**
   * The manifest a catalog with nothing to show writes anyway — no lanes, no rows.
   *
   * It is the *settled* marker: it says the background lane ran and this catalog has no published
   * comparison, which is a different state from "the lane hasn't finished yet". Only the second one
   * makes the compare page's shape provisional, and only that one must stay out of caches.
   */
  val NONE = RcCompareManifest()

  /**
   * Whether the staging lane runs for a session whose catalog-id → daemon-id bridge is [alias].
   *
   * The whole view is re-keyed through that bridge, so a catalog with an empty one publishes
   * nothing and the lane is never scheduled. Two callers must agree on this — the scheduler, which
   * would otherwise do a pointless fetch, and the host, whose "still pending" answer is only
   * meaningful when a lane is actually coming — so they share this one expression rather than each
   * spelling it out.
   */
  fun stagesFor(alias: Map<String, String>): Boolean = alias.isNotEmpty()

  /** The published summary, branch-relative — the source this whole view is derived from. */
  const val SUMMARY_FILE = "rc-compare-summary.json"

  val LANES: List<RcLaneSource> =
    listOf(
      RcLaneSource(
        id = "baked",
        // The catalog's own capture, rendered offline under Robolectric/Skiko. Named for the
        // player rather than for the file it arrives as: "baked PNG" said how it got here, not
        // what drew it, which is the only thing a reader comparing it against four other players
        // cares about.
        // That player is now the embedded `RcPlayer`: `RemoteOverridablePreview` defaults to
        // `RemoteComposePlayerKind.EMBEDDED`, so a capture goes through it unless the preview pins
        // the view-backed lane with `RemoteViewPreviewWrapper`. [ServeHost.bakedRcPlayer] is where
        // that question is now asked, and a bundle answers it from its `previews.json` pin — but
        // `rc-compare-summary.json` still records no per-row renderer, and a published catalog's
        // inline `previewParams` does not carry the wrapper either. So a *catalog* whose previews
        // mix the two is still mislabelled in this column rather than detected: carry the player
        // per row into the published catalog before scoring such a catalog.
        label = "AndroidX Embedded · baked",
        short = "baked",
        renderDir = "rc-baked",
        diffDir = null,
        // The driver writes a baked copy for every row it processes, rendered or not.
        rendered = { true },
        mismatchPct = { null },
        mismatchPx = { null },
        note = { null },
      ),
      RcLaneSource(
        id = "js",
        label = "RC · JS player",
        short = "js",
        renderDir = "rc",
        diffDir = "rc-diff",
        rendered = { it.rendered },
        mismatchPct = { it.mismatchPct },
        mismatchPx = { it.mismatchPx },
        note = { it.note },
      ),
      RcLaneSource(
        id = "embedded",
        label = "AndroidX Embedded · vendored Android",
        short = "vendored",
        renderDir = "rc-embedded",
        diffDir = "rc-embedded-diff",
        rendered = { it.embeddedRendered },
        mismatchPct = { it.embeddedMismatchPct },
        mismatchPx = { it.embeddedMismatchPx },
        note = { it.embeddedNote },
      ),
      RcLaneSource(
        id = "androidx-embedded",
        label = "AndroidX Embedded · androidx.dev",
        short = "androidx.dev",
        renderDir = "rc-androidx-embedded",
        diffDir = "rc-androidx-embedded-diff",
        rendered = { it.androidxEmbeddedRendered },
        mismatchPct = { it.androidxEmbeddedMismatchPct },
        mismatchPx = { it.androidxEmbeddedMismatchPx },
        note = { it.androidxEmbeddedNote },
      ),
      RcLaneSource(
        id = "cmp-jvm",
        label = "RC · cmp-jvm player",
        short = "cmp-jvm",
        renderDir = "rc-embedded-jvm",
        diffDir = "rc-embedded-jvm-diff",
        rendered = { it.embeddedJvmRendered },
        mismatchPct = { it.embeddedJvmMismatchPct },
        mismatchPx = { it.embeddedJvmMismatchPx },
        note = { it.embeddedJvmNote },
      ),
      RcLaneSource(
        id = "cmp-wasm",
        label = "RC · cmp-wasm player",
        short = "cmp-wasm",
        renderDir = "rc-cmp-wasm",
        diffDir = "rc-cmp-wasm-diff",
        rendered = { it.cmpWasmRendered },
        mismatchPct = { it.cmpWasmMismatchPct },
        mismatchPx = { it.cmpWasmMismatchPx },
        note = { it.cmpWasmNote },
      ),
    )

  /** Lane ids, for validating a served image path against the fixed vocabulary. */
  private val LANE_IDS = LANES.map { it.id }.toSet()

  private val JSON = Json { ignoreUnknownKeys = true }

  /**
   * The row model the compare page's client script diffs over, inlined as `application/json`.
   *
   * Keeping it as data — rather than having the script scrape the DOM — is what lets the client
   * pick the *build-time* diff whenever the selected reference is the baked lane (exact
   * `pixelmatch` numbers, zero work) and fall back to a canvas diff only for the player↔player
   * question nothing precomputed can answer.
   */
  @Serializable
  data class ClientModel(
    val threshold: Double,
    val lanes: List<RcCompareLane>,
    val rows: List<ClientRow>,
  )

  /** One row as the browser sees it: [RcCompareCell]s whose paths have been resolved to URLs. */
  @Serializable
  data class ClientRow(
    val label: String,
    val referenceBlank: Boolean,
    val lanes: Map<String, RcCompareCell>,
  )

  private val MODEL_JSON = Json { encodeDefaults = true }

  /** Encode a [ClientModel] for inlining — `<` escaped so a player note can't close the script. */
  fun encodeClientModel(model: ClientModel): String =
    MODEL_JSON.encodeToString(ClientModel.serializer(), model).replace("<", "\\u003c")

  fun parseSummary(bytes: ByteArray): RcSummary? = runCatching {
    JSON.decodeFromString<RcSummary>(bytes.decodeToString())
  }
    .getOrNull()
    ?.takeIf { it.rows.isNotEmpty() }

  /**
   * Turn a published summary + the catalog's `catalog-id → daemon-id` alias into the manifest to
   * stage and the branch assets to fetch for it. Pure, so the re-keying and the lane arithmetic are
   * testable without a network or a filesystem.
   *
   * Only lanes that actually ran are kept: a catalog whose run had no cmp-wasm player simply has no
   * cmp-wasm column, rather than a column of empty cells. Only *rendered* cells plan a fetch, so a
   * player that choked on a document costs no round-trip — its note is shown in place of the image.
   *
   * Returns null when nothing published matches this catalog, which is the common case (most
   * systems ship no `ir/<id>.rc` at all).
   */
  fun plan(summary: RcSummary, alias: Map<String, String>): RcComparePlan? {
    val byDaemonId = summary.rows.associateBy { it.id }
    val matched = alias.mapNotNull { (catalogId, daemonId) ->
      byDaemonId[daemonId]?.let { catalogId to it }
    }
    if (matched.isEmpty()) return null

    // A lane is present when the run recorded a verdict for it on any row — the same test
    // `render-rc-compare-html.mjs` applies before it emits the column.
    val lanes = LANES.filter { lane -> matched.any { (_, row) -> lane.rendered(row) != null } }
    val slots = LinkedHashMap<String, Int>()
    val assets = LinkedHashMap<String, String>()

    fun stage(dir: String, daemonId: String, laneId: String, suffix: String, slot: Int): String {
      val staged = "$laneId$suffix/$slot.png"
      assets["$dir/$daemonId.png"] = staged
      return staged
    }

    val rows = matched.map { (catalogId, row) ->
      val slot = slots.getOrPut(row.id) { slots.size }
      val cells = LinkedHashMap<String, RcCompareCell>()
      for (lane in lanes) {
        val rendered = lane.rendered(row) == true
        val scorable = !row.referenceBlank
        cells[lane.id] =
          RcCompareCell(
            rendered = rendered,
            render = if (rendered) stage(lane.renderDir, row.id, lane.id, "", slot) else "",
            diff =
              if (rendered && scorable && lane.diffDir != null)
                stage(lane.diffDir, row.id, lane.id, "-diff", slot)
              else "",
            mismatchPct = if (scorable) lane.mismatchPct(row) else null,
            mismatchPx = if (scorable) lane.mismatchPx(row) else null,
            note = lane.note(row).orEmpty(),
          )
      }
      RcCompareRow(
        previewId = catalogId,
        width = row.width ?: 0,
        height = row.height ?: 0,
        referenceBlank = row.referenceBlank,
        lanes = cells,
      )
    }

    return RcComparePlan(
      manifest =
        RcCompareManifest(
          threshold = summary.threshold ?: RcCompareManifest.DEFAULT_THRESHOLD,
          lanes = lanes.map { RcCompareLane(it.id, it.label, it.short) },
          rows = rows,
        ),
      assets = assets,
    )
  }

  /**
   * Narrow a planned manifest to the images that actually landed. A lane whose render didn't arrive
   * reads as unrendered with a plain reason rather than a broken `<img>`; a lane that rendered but
   * lost its diff simply has no diff, and the page falls back to diffing it in the browser.
   *
   * Returns null when nothing survived — there is no page worth publishing then, and the absent
   * manifest is what makes the compare page keep its client-rendered lane.
   */
  fun retainStaged(manifest: RcCompareManifest, staged: Set<String>): RcCompareManifest? {
    val rows =
      manifest.rows.map { row ->
        row.copy(
          lanes =
            row.lanes.mapValues { (_, cell) ->
              val render = cell.render.takeIf { it in staged }.orEmpty()
              cell.copy(
                rendered = cell.rendered && render.isNotEmpty(),
                render = render,
                diff = cell.diff.takeIf { render.isNotEmpty() && it in staged }.orEmpty(),
                note =
                  if (cell.rendered && render.isEmpty()) "render was not published" else cell.note,
              )
            }
        )
      }
    if (rows.none { row -> row.lanes.values.any { it.render.isNotEmpty() } }) return null
    return manifest.copy(rows = rows)
  }

  /**
   * Whether [name] is a staged image this catalog could have written —
   * `<known-lane>[-diff]/<n>.png` and nothing else. The fixed vocabulary is what keeps the route
   * from being a file-read primitive: a request that isn't literally of this shape never reaches
   * the filesystem.
   */
  fun isStagedImageName(name: String): Boolean {
    val (dir, file) = name.split('/').takeIf { it.size == 2 } ?: return false
    val lane = dir.removeSuffix("-diff")
    if (lane !in LANE_IDS) return false
    val slot = file.removeSuffix(".png")
    return file.endsWith(".png") && slot.isNotEmpty() && slot.all { it.isDigit() }
  }
}

/** The manifest to stage plus the branch assets (`source path → staged name`) it references. */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
data class RcComparePlan(val manifest: RcCompareManifest, val assets: Map<String, String>)

/**
 * A published `rc-compare-summary.json`, cut down to the per-row verdicts the serve page replays.
 * Extra fields (the coverage split, the cmp-wasm frame-pacing numbers, the CI gate) are ignored:
 * they belong to the parity gate, not to a side-by-side viewer.
 */
@Serializable
data class RcSummary(val threshold: Double? = null, val rows: List<RcSummaryRow> = emptyList())

/** One preview as the offline run scored it, keyed by the **daemon** preview id. */
@Serializable
data class RcSummaryRow(
  val id: String = "",
  val width: Int? = null,
  val height: Int? = null,
  val referenceBlank: Boolean = false,
  val rendered: Boolean? = null,
  val mismatchPct: Double? = null,
  val mismatchPx: Long? = null,
  val note: String? = null,
  val embeddedRendered: Boolean? = null,
  val embeddedMismatchPct: Double? = null,
  val embeddedMismatchPx: Long? = null,
  val embeddedNote: String? = null,
  val androidxEmbeddedRendered: Boolean? = null,
  val androidxEmbeddedMismatchPct: Double? = null,
  val androidxEmbeddedMismatchPx: Long? = null,
  val androidxEmbeddedNote: String? = null,
  val embeddedJvmRendered: Boolean? = null,
  val embeddedJvmMismatchPct: Double? = null,
  val embeddedJvmMismatchPx: Long? = null,
  val embeddedJvmNote: String? = null,
  val cmpWasmRendered: Boolean? = null,
  val cmpWasmMismatchPct: Double? = null,
  val cmpWasmMismatchPx: Long? = null,
  val cmpWasmNote: String? = null,
)

/**
 * Read-only view of a staged `rc-compare/index.json` + its images.
 *
 * Loaded **lazily and re-checked while absent**, unlike the reference/annotation manifests: the
 * lane PNGs are fetched on the catalog's background lane (they are an enrichment, not the catalog),
 * so a host built the moment `catalog.json` landed would otherwise cache "no rc-compare" forever
 * and only pick the page up on the next refresh. Once a manifest has been read it is kept — a
 * published comparison is a snapshot, and a refresh rebuilds the host anyway.
 */
class ServeRcCompareStore
private constructor(private val root: Path, private val fileSystem: FileSystem) {

  @Volatile private var settled: RcCompareManifest? = null

  fun manifest(): RcCompareManifest? = read()?.takeIf { it.rows.isNotEmpty() }

  /**
   * True while the background staging lane has yet to write anything — so this catalog's compare
   * page is showing a shape that may still change.
   *
   * The page is assembled from published metadata and is normally short-cached at the edge, which
   * would pin the pre-manifest shape in place for minutes after the lanes landed. A pending
   * catalog's page is served uncacheable instead; once the lane settles (with a comparison or with
   * [ServeRcCompare.NONE]) the page is stable and caches like every other one.
   */
  fun pending(): Boolean = read() == null

  private fun read(): RcCompareManifest? {
    settled?.let {
      return it
    }
    val path = root / ServeRcCompare.DIRECTORY / ServeRcCompare.INDEX_FILE
    val manifest =
      runCatching {
        if (!fileSystem.exists(path)) return null
        JSON.decodeFromString<RcCompareManifest>(fileSystem.read(path) { readUtf8() })
      }
        .getOrNull()
        ?.takeIf { it.schema == RcCompareManifest.SCHEMA } ?: return null
    settled = manifest
    return manifest
  }

  /** Bytes for a staged lane image, or null for anything this catalog didn't stage. */
  fun image(name: String): ByteArray? {
    if (!ServeRcCompare.isStagedImageName(name)) return null
    val path = root / ServeRcCompare.DIRECTORY / name
    if (!fileSystem.exists(path)) return null
    return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
  }

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ServeRcCompareStore =
      ServeRcCompareStore(bundleDir.toOkioPath(), fileSystem)
  }
}
