package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * The published Remote Compose player comparison, served from a catalog's delivery branch.
 *
 * The offline `rc-compare` pipeline (`@design-parity/export-driver/rc-compare.mjs`) renders every
 * `ir/<id>.rc` through every reachable player, pixel-diffs each against the baked render, and
 * publishes lane PNGs plus `rc-compare-summary.json`. The serve page replays those instead of
 * rendering in the browser: every player, with precomputed `pixelmatch` numbers.
 *
 * [RcCompareManifest] is staged by [ServeCatalogStore] into `<catalog>/rc-compare/index.json` and
 * inlined by [ServeWeb]. It is catalog-keyed: summary rows keyed by daemon preview id are re-keyed
 * through the catalog alias (as [ServeCatalogStore.extractCatalogRcDocs] does). Staged images are
 * `<lane-id>/<slot>.png` (fixed vocabulary, integer slot per daemon id), so no published id reaches
 * the filesystem or a URL.
 */
@Serializable
public data class RcCompareManifest(
  val schema: String = SCHEMA,
  /**
   * The pixelmatch threshold of the build-time diffs, so client-side player↔player diffs use the
   * same scale.
   */
  val threshold: Double = DEFAULT_THRESHOLD,
  /** The player columns this catalog actually has, in display order. `baked` is always first. */
  val lanes: List<RcCompareLane> = emptyList(),
  val rows: List<RcCompareRow> = emptyList(),
) {
  public companion object {
    public const val SCHEMA: String = "compose-preview-rc-compare/v1"
    public const val DEFAULT_THRESHOLD: Double = 0.1
  }
}

/** One player column. [short] is the compact label used on the per-row score chips. */
@Serializable public data class RcCompareLane(val id: String, val label: String, val short: String)

/** One preview's row: the published renders keyed by lane id. */
@Serializable
public data class RcCompareRow(
  /** Served catalog preview id — the same id `/p/<id>` and `/render/<id>.png` use. */
  val previewId: String,
  val width: Int = 0,
  val height: Int = 0,
  /**
   * The baked render has no opaque pixel, so it can't be a reference (an empty player would score
   * 0%); reads `no reference` when baked is the selected reference.
   */
  val referenceBlank: Boolean = false,
  val lanes: Map<String, RcCompareCell> = emptyMap(),
)

/** One lane's published result for one preview. */
@Serializable
public data class RcCompareCell(
  val rendered: Boolean = false,
  /**
   * Staged image name (`<lane>/<slot>.png`), served under `/<system>/rc-compare/`; empty ⇒ none.
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
 * A player lane as published on the delivery branch: where its renders and diffs live and how to
 * read its summary fields. Mirrors `render-rc-compare-html.mjs`'s `LANES`. `baked` is the
 * reference, not a player, but is a column and a selectable reference too.
 */
// Public because `:server` call sites live in another module.
public data class RcLaneSource(
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

// Public because `:server` call sites live in another module; not a widened API by intent.
public object ServeRcCompare {
  /** Staged subdir under the served catalog root, and the URL segment it is served at. */
  public const val DIRECTORY: String = "rc-compare"
  public const val INDEX_FILE: String = "index.json"

  /**
   * The manifest for a catalog with nothing to show. It marks the lane as settled, unlike "not
   * finished yet", which is the only state that must stay out of caches.
   */
  public val NONE: RcCompareManifest = RcCompareManifest()

  /**
   * Whether the staging lane runs for a session with this catalog-id → daemon-id [alias] (empty
   * means nothing can be published). Shared by the scheduler and the host's "pending" answer so
   * they agree.
   */
  public fun stagesFor(alias: Map<String, String>): Boolean = alias.isNotEmpty()

  /** The published summary, branch-relative — the source this whole view is derived from. */
  public const val SUMMARY_FILE: String = "rc-compare-summary.json"

  /**
   * The published columns. [RcLaneSource.id]s are column ids, not [RcPlayerBackend.wire] ids, and
   * are frozen because they key already-published assets: `embedded` is the vendored AndroidX
   * embedded player ([RcPlayerBackend.ANDROIDX_EMBEDDED]), `androidx-embedded` the androidx.dev
   * build of it (no backend maps to it), `js` is [RcPlayerBackend.CAMAELON_JS].
   */
  public val LANES: List<RcLaneSource> =
    listOf(
      RcLaneSource(
        id = "baked",
        // The catalog's own capture, labelled by the player that drew it: the embedded `RcPlayer`
        // unless a preview pins `RemoteViewPreviewWrapper`. The published summary records no
        // per-row renderer, so a catalog mixing the two is mislabelled here; publish the player per
        // row before scoring one.
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
        label = "Camaelon JS",
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
        label = "CMP JVM",
        short = "jvm",
        // Directory names are frozen: they key assets in published catalogs.
        renderDir = "rc-embedded-jvm",
        diffDir = "rc-embedded-jvm-diff",
        rendered = { it.embeddedJvmRendered },
        mismatchPct = { it.embeddedJvmMismatchPct },
        mismatchPx = { it.embeddedJvmMismatchPx },
        note = { it.embeddedJvmNote },
      ),
      RcLaneSource(
        id = "cmp-wasm",
        label = "CMP Wasm",
        short = "wasm",
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
   * The row model the compare page's client script diffs over, inlined as `application/json`, so it
   * can use build-time diffs against the baked lane and canvas-diff only player↔player.
   */
  @Serializable
  public data class ClientModel(
    val threshold: Double,
    val lanes: List<RcCompareLane>,
    val rows: List<ClientRow>,
  )

  /** One row as the browser sees it: [RcCompareCell]s whose paths have been resolved to URLs. */
  @Serializable
  public data class ClientRow(
    val label: String,
    val referenceBlank: Boolean,
    val lanes: Map<String, RcCompareCell>,
  )

  private val MODEL_JSON = Json { encodeDefaults = true }

  /** Encode a [ClientModel] for inlining — `<` escaped so a player note can't close the script. */
  public fun encodeClientModel(model: ClientModel): String =
    MODEL_JSON.encodeToString(ClientModel.serializer(), model).replace("<", "\\u003c")

  public fun parseSummary(bytes: ByteArray): RcSummary? = runCatching {
    JSON.decodeFromString<RcSummary>(bytes.decodeToString())
  }
    .getOrNull()
    ?.takeIf { it.rows.isNotEmpty() }

  /**
   * Turn a published summary and the catalog's `catalog-id → daemon-id` alias into the manifest to
   * stage and the assets to fetch. Pure.
   *
   * Only lanes that ran are kept, and only rendered cells plan a fetch (a failed player shows its
   * note). Returns null when nothing published matches this catalog (the common case).
   */
  public fun plan(summary: RcSummary, alias: Map<String, String>): RcComparePlan? {
    val byDaemonId = summary.rows.associateBy { it.id }
    val matched = alias.mapNotNull { (catalogId, daemonId) ->
      byDaemonId[daemonId]?.let { catalogId to it }
    }
    if (matched.isEmpty()) return null

    // A lane is present when any row has a verdict for it, as in `render-rc-compare-html.mjs`.
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
   * Narrow a planned manifest to the images that actually landed: a missing render reads as
   * unrendered, a missing diff falls back to an in-browser diff. Null when nothing survived, so the
   * page keeps its client-rendered lane.
   */
  public fun retainStaged(manifest: RcCompareManifest, staged: Set<String>): RcCompareManifest? {
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
   * Whether [name] is `<known-lane>[-diff]/<n>.png` — the fixed shape that keeps this route from
   * being a file-read primitive.
   */
  public fun isStagedImageName(name: String): Boolean {
    val (dir, file) = name.split('/').takeIf { it.size == 2 } ?: return false
    val lane = dir.removeSuffix("-diff")
    if (lane !in LANE_IDS) return false
    val slot = file.removeSuffix(".png")
    return file.endsWith(".png") && slot.isNotEmpty() && slot.all { it.isDigit() }
  }
}

/** The manifest to stage plus the branch assets (`source path → staged name`) it references. */
// Public because `:server` call sites live in another module; not a widened API by intent.
public data class RcComparePlan(val manifest: RcCompareManifest, val assets: Map<String, String>)

/**
 * A published `rc-compare-summary.json`, cut to the per-row verdicts the page replays; parity-gate
 * fields are ignored.
 */
@Serializable
public data class RcSummary(
  val threshold: Double? = null,
  val rows: List<RcSummaryRow> = emptyList(),
)

/** One preview as the offline run scored it, keyed by the **daemon** preview id. */
@Serializable
public data class RcSummaryRow(
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
 * Read-only view of a staged `rc-compare/index.json` and its images. Loaded lazily and re-checked
 * while absent, since the lane PNGs arrive on a background lane after the host is built; once read,
 * it is kept.
 */
public class ServeRcCompareStore
private constructor(private val root: Path, private val fileSystem: FileSystem) {

  @Volatile private var settled: RcCompareManifest? = null

  public fun manifest(): RcCompareManifest? = read()?.takeIf { it.rows.isNotEmpty() }

  /**
   * True while the staging lane hasn't written anything yet, so the compare page is served
   * uncacheable until its shape settles (with a comparison or [ServeRcCompare.NONE]).
   */
  public fun pending(): Boolean = read() == null

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
  public fun image(name: String): ByteArray? {
    if (!ServeRcCompare.isStagedImageName(name)) return null
    val path = root / ServeRcCompare.DIRECTORY / name
    if (!fileSystem.exists(path)) return null
    return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
  }

  public companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    public fun load(
      bundleDir: File,
      fileSystem: FileSystem = SystemFileSystem,
    ): ServeRcCompareStore = ServeRcCompareStore(bundleDir.toOkioPath(), fileSystem)
  }
}
