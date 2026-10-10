package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.GuidelineEvidenceHost
import ee.schimke.composeai.guidelines.GuidelineSubjectKind
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.PreviewCheck
import ee.schimke.composeai.guidelines.PreviewGuidelineRequests
import ee.schimke.composeai.guidelines.PreviewNode
import ee.schimke.composeai.guidelines.PreviewSubject
import ee.schimke.composeai.guidelines.SubjectPicture
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.previewdata.AccessibilityNode
import ee.schimke.composeai.previewdata.AccessibilityReport
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.render.matrix.MatrixCell
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a handoff run (no Gradle, no daemon) judges: the subjects, their nodes and their renders,
 * read from the files a CI publish job holds.
 */
internal data class HandoffInputs(
  val subjects: List<PreviewSubject>,
  val nodes: Map<String, List<AccessibilityNode>>,
  val renders: Map<String, File?>,
  /** Each preview's size in dp, for the pictures a follow-up round attaches. */
  val sizes: Map<String, Pair<Int, Int>> = emptyMap(),
  /** Each preview's measured accessibility checks, served with its [nodes] as `a11y` evidence. */
  val checks: Map<String, List<PreviewCheck>> = emptyMap(),
) {
  /**
   * What a follow-up round may ask for in handoff mode: only what the render job already made and
   * staged — the long screenshot of a preview's scrolling content, and its accessibility data
   * (nodes and ATF checks, `a11y`). The job holding the key never builds or renders, so nothing
   * else is fetchable.
   */
  val host: GuidelineEvidenceHost
    get() = HandoffEvidenceHost(renders, sizes, nodes, checks)

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * [previewsJson] is either a flat list of ids (strings, or objects with an `id`) narrowing the
     * PNGs in [rendersDir], or a module's `previews.json`, whose captures name each render
     * (resolved beside the file, then by name in [rendersDir]) and whose `sourceFile`/`bodyLine`
     * give each preview's source under [sourceRoot]. [a11yJson] is an `accessibility.json`.
     *
     * Each preview's surface and profile come from its manifest entry ([GuidelineSurfaces.of]);
     * [surfaceOverride] and [profileOverride] replace them for every preview, for a manifest that
     * predates the signal or a flat id list, which has none.
     */
    fun read(
      previewsJson: File?,
      rendersDir: File?,
      a11yJson: File?,
      sourceRoot: File?,
      surfaceOverride: String?,
      profileOverride: String? = null,
    ): HandoffInputs {
      val nodes = readNodes(a11yJson)
      val checks = readChecks(a11yJson)
      val parsed =
        previewsJson?.let { runCatching { JSON.parseToJsonElement(it.readText()) } }?.getOrNull()
      val manifestPreviews = ((parsed as? JsonObject)?.get("previews") as? JsonArray)
      val entries: List<Entry> =
        if (manifestPreviews != null) {
          val base = previewsJson.absoluteFile.parentFile
          manifestPreviews.mapNotNull { element ->
            val preview = element as? JsonObject ?: return@mapNotNull null
            val id = preview.text("id") ?: return@mapNotNull null
            val capture =
              (preview["captures"] as? JsonArray)?.firstNotNullOfOrNull {
                (it as? JsonObject)?.takeIf { c -> c.text("renderOutput") != null }
              }
            val output = capture?.text("renderOutput")
            val scrollMode = (capture?.get("scroll") as? JsonObject)?.text("mode")
            val render = output?.let { path ->
              within(base, path)?.takeIf { it.isFile }
                ?: rendersDir?.let { within(it, File(path).name) }?.takeIf { it.isFile }
            }
            val params = preview["params"] as? JsonObject
            Entry(
              id = id,
              label = preview.text("functionName") ?: id,
              render = render,
              sourceFile = preview.text("sourceFile"),
              bodyLine = (preview["bodyLine"] as? JsonPrimitive)?.intOrNull,
              kind = GuidelineSurfaces.of(preview),
              scrollMode = scrollMode,
              widthDp = (params?.get("widthDp") as? JsonPrimitive)?.intOrNull ?: 0,
              heightDp = (params?.get("heightDp") as? JsonPrimitive)?.intOrNull ?: 0,
            )
          }
        } else {
          val ids =
            (parsed as? JsonArray)?.mapNotNull {
              (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("id")
            }
          rendersDir
            ?.listFiles { file ->
              file.extension == "png" &&
                !file.name.endsWith(".guidelines.png") &&
                !file.name.endsWith(LONG_SUFFIX)
            }
            .orEmpty()
            .sortedBy { it.name }
            .filter { ids == null || it.nameWithoutExtension in ids }
            .map {
              Entry(id = it.nameWithoutExtension, label = it.nameWithoutExtension, render = it)
            }
        }
      val subjects = entries.mapNotNull { entry ->
        val png = entry.render ?: return@mapNotNull null
        val bytes = png.readBytes()
        val source =
          entry.sourceFile?.let { path ->
            val line = entry.bodyLine ?: return@let null
            val root = sourceRoot ?: return@let null
            within(root, path)?.let {
              PreviewSourceReader.readWithCallees(
                it,
                line,
                index = SourceIndex.forSourceFile(it, within = root),
              )
            }
          }
        PreviewSubject(
          previewId = entry.id,
          label = entry.label,
          surface = surfaceOverride ?: entry.kind.surface,
          profile = profileOverride ?: entry.kind.profile,
          // The long screenshot is served later, from the host, so the subject's pictures do not
          // carry it; its bytes join the identity the result is cached under, or a changed (or
          // newly staged) capture would be answered from a verdict that never saw it.
          renderHash =
            renderHash(
              sha256(bytes),
              entry.render?.let(::longCapture),
              a11y =
                nodes[entry.id].orEmpty().isNotEmpty() || checks[entry.id].orEmpty().isNotEmpty(),
            ),
          pictures =
            listOf(
              SubjectPicture(
                "device",
                bytes,
                entry.widthDp,
                entry.heightDp,
                description = describeCapture(entry.widthDp, entry.heightDp, entry.scrollMode),
              )
            ),
          // Accessibility data is evidence the model asks for (`a11y`), served from the host,
          // not sent with every subject; the request shows only the host's one-line summary.
          // It is derived from the same render, so it does not join the cache identity.
          source = source,
        )
      }
      return HandoffInputs(
        subjects,
        nodes,
        entries.associate { it.id to it.render },
        entries.associate { it.id to (it.widthDp to it.heightDp) },
        checks,
      )
    }

    /**
     * Each preview's surface and profile ([GuidelineSurfaces.of]) by id, from a module's
     * `previews.json`; empty when it cannot be read.
     */
    fun readKinds(previewsJson: File?): Map<String, GuidelineSubjectKind> {
      if (previewsJson == null || !previewsJson.isFile) return emptyMap()
      val parsed =
        runCatching { JSON.parseToJsonElement(previewsJson.readText()) }.getOrNull() as? JsonObject
      return (parsed?.get("previews") as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
          val preview = element as? JsonObject ?: return@mapNotNull null
          val id = preview.text("id") ?: return@mapNotNull null
          id to GuidelineSurfaces.of(preview)
        }
        .toMap()
    }

    fun readNodes(file: File?): Map<String, List<AccessibilityNode>> {
      if (file == null || !file.isFile) return emptyMap()
      return runCatching {
          JSON.decodeFromString(AccessibilityReport.serializer(), file.readText())
            .entries
            .associate { it.previewId to it.nodes }
        }
        .getOrDefault(emptyMap())
    }

    /**
     * Each preview's Accessibility Test Framework results from an `accessibility.json`, as
     * [PreviewCheck]s: measured evidence for the touch-target and contrast rules.
     */
    fun readChecks(file: File?): Map<String, List<PreviewCheck>> {
      if (file == null || !file.isFile) return emptyMap()
      return runCatching {
          JSON.decodeFromString(AccessibilityReport.serializer(), file.readText())
            .entries
            .associate { entry ->
              entry.previewId to
                entry.findings.map {
                  PreviewCheck(
                    type = it.type,
                    level = it.level,
                    message = it.message,
                    element = it.viewDescription,
                    bounds = it.boundsInScreen,
                  )
                }
            }
        }
        .getOrDefault(emptyMap())
    }

    /**
     * A render's identity in the result cache: its bytes' hash, plus its long screenshot's when it
     * has one. A Gradle run and a handoff run compute it the same way for the same files, so a PR
     * check (handoff) can be answered from a catalog publish's cache (Gradle).
     */
    internal fun renderHash(renderSha256: String, long: File?, a11y: Boolean = false): String =
      renderSha256 +
        (long?.let { "+scroll:" + sha256(it.readBytes()) } ?: "") +
        // Whether accessibility evidence could be asked for: a verdict reached without it (no
        // a11y pipeline, a daemon that failed) must not answer a run that has it. The data itself
        // is derived from the render, so its availability, not its bytes, is what differs.
        (if (a11y) "+a11y" else "")

    /** The `render/scroll/long` data product's kind, as the manifest names it. */
    internal const val LONG_KIND: String = "render/scroll/long"

    /**
     * In a Gradle run, the long screenshot the render job would stage for [preview] (the apply
     * action's `guidelines-stage.py`): its `render/scroll/long` data product under [buildDir]
     * (`build/compose-previews`), else `<render>_SCROLL_long.png` beside [render]; null when there
     * is none, or it is a link or too large to send.
     */
    internal fun longCaptureOf(preview: PreviewInfo?, render: File?, buildDir: File): File? {
      val products =
        preview
          ?.dataProducts
          .orEmpty()
          .filter { it.kind == LONG_KIND && it.output.endsWith(".png") }
          .mapNotNull { within(buildDir, it.output) }
      val sibling = render?.resolveSibling(render.nameWithoutExtension + LONG_SUFFIX)
      return (products + listOfNotNull(sibling)).firstOrNull {
        it.isFile &&
          !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
          it.length() <= MAX_LONG_BYTES
      }
    }

    /** The sidecar the renderer writes beside a scrolled capture: the whole scrolling content. */
    internal const val LONG_SUFFIX: String = "_SCROLL_long.png"

    /** Past this a long screenshot is left out rather than crowd the request. */
    private const val MAX_LONG_BYTES: Long = 2L * 1024 * 1024

    /**
     * The long screenshot beside [render] (`<name>_SCROLL_long.png`), or null when there is none,
     * it is a link, or it is too large to send. It lies beside the render, so it is confined to the
     * same staged directory.
     */
    internal fun longCapture(render: File): File? =
      render.resolveSibling(render.nameWithoutExtension + LONG_SUFFIX).takeIf {
        it.isFile &&
          !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
          it.length() <= MAX_LONG_BYTES
      }

    /** [file], a long screenshot, as the picture a scroll-capture need is answered with. */
    internal fun longPicture(file: File, widthDp: Int, heightDp: Int): SubjectPicture =
      SubjectPicture(
        PreviewGuidelineRequests.KIND_SCROLL_CAPTURE,
        file.readBytes(),
        widthDp,
        heightDp,
        description = describeCapture(widthDp, heightDp, "LONG"),
      )

    /**
     * How the picture was captured, so the model does not read a scrolled frame as the first one:
     * at the END of a scroll, content that scrolls away with the list (time text, a list header) is
     * out of view, not missing.
     */
    internal fun describeCapture(widthDp: Int, heightDp: Int, scrollMode: String?): String? {
      val size = if (widthDp > 0 && heightDp > 0) " at ${widthDp}×${heightDp}dp" else ""
      return when (scrollMode?.uppercase()) {
        null -> if (size.isEmpty()) null else "device render$size, its first frame"
        "END" ->
          "device render$size, captured scrolled to the END of its scrolling content: anything " +
            "that scrolls away with the content (time text, list headers) is out of view here, " +
            "not missing"
        "LONG" ->
          "a stitched long screenshot$size of the whole scrolling content, top to bottom; it is " +
            "taller than any screen, so judge content, not what fits on one screen"
        else -> "device render$size, captured at scroll mode $scrollMode"
      }
    }

    /**
     * [path] under [root], or null when it is absolute or leaves [root]. A handoff's
     * `previews.json` comes from the PR — possibly a fork — and names these paths, so a crafted
     * `../../` must not make the publish job read, and send to a model, a file outside the staged
     * tree.
     */
    internal fun within(root: File, path: String): File? {
      if (File(path).isAbsolute) return null
      val base = root.canonicalFile
      val resolved = base.resolve(path).canonicalFile
      return resolved.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun JsonObject.text(name: String): String? =
      (this[name] as? JsonPrimitive)?.contentOrNull

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }

  private data class Entry(
    val id: String,
    val label: String,
    val render: File?,
    val sourceFile: String? = null,
    val bodyLine: Int? = null,
    val kind: GuidelineSubjectKind = GuidelineSubjectKind(GuidelineSurfaces.COMPONENT),
    val widthDp: Int = 0,
    val heightDp: Int = 0,
    val scrollMode: String? = null,
  )
}

/**
 * Handoff mode's evidence: a preview's long screenshot, when the render job staged one beside its
 * render, served from that file in a follow-up round. Offered only for previews that have one.
 */
internal class HandoffEvidenceHost(
  renders: Map<String, File?>,
  private val sizes: Map<String, Pair<Int, Int>> = emptyMap(),
  nodes: Map<String, List<AccessibilityNode>> = emptyMap(),
  private val checks: Map<String, List<PreviewCheck>> = emptyMap(),
) : GuidelineEvidenceHost {
  private val captures: Map<String, File> =
    renders
      .mapNotNull { (id, render) -> render?.let(HandoffInputs::longCapture)?.let { id to it } }
      .toMap()

  /**
   * The previews with accessibility data to serve. An entry with neither nodes nor checks is a
   * fetch that produced nothing (the daemon files one per attempted preview), not a clean preview.
   */
  private val a11y: Map<String, List<PreviewNode>> =
    (nodes.keys + checks.keys)
      .filter { id -> nodes[id].orEmpty().isNotEmpty() || checks[id].orEmpty().isNotEmpty() }
      .associateWith { id ->
        nodes[id].orEmpty().mapIndexedNotNull { index, node -> node.toPreviewNode(index) }
      }

  override val available: List<String> =
    listOfNotNull(
      PreviewGuidelineRequests.KIND_SCROLL_CAPTURE.takeIf { captures.isNotEmpty() },
      PreviewGuidelineRequests.KIND_A11Y.takeIf { a11y.isNotEmpty() },
    )

  override fun available(previewId: String): List<String> =
    listOfNotNull(
      PreviewGuidelineRequests.KIND_SCROLL_CAPTURE.takeIf { previewId in captures },
      PreviewGuidelineRequests.KIND_A11Y.takeIf { previewId in a11y },
    )

  override fun nodes(previewId: String): List<PreviewNode>? = a11y[previewId]

  override fun checks(previewId: String): List<PreviewCheck>? =
    if (previewId in a11y) checks[previewId].orEmpty() else null

  override fun summary(previewId: String): String? =
    a11y[previewId]?.let { PreviewGuidelineRequests.a11ySummary(it, checks[previewId].orEmpty()) }

  override fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture? {
    if (need.kind != PreviewGuidelineRequests.KIND_SCROLL_CAPTURE) return null
    val file = captures[previewId] ?: return null
    val (width, height) = sizes[previewId] ?: (0 to 0)
    return HandoffInputs.longPicture(file, width, height)
  }
}

/** A preview's accessibility data as a live run fetched it: nodes and ATF checks, by preview id. */
internal data class A11yEvidence(
  val nodes: Map<String, List<AccessibilityNode>> = emptyMap(),
  val checks: Map<String, List<PreviewCheck>> = emptyMap(),
)

/**
 * What a live CLI run can fetch when the model asks for more: the source it already read, a render
 * at other settings (theme, font scale, device, locale) through the module's render daemon, and a
 * preview's accessibility data (`a11y`: nodes and ATF checks).
 *
 * Accessibility data is fetched on request, for the previews that ask, through [a11yFetch] (the
 * `a11y` command's daemon fetch narrowed to those ids) — not for every preview before the first
 * request, which on a catalog costs one ATF render per preview whether or not any rule needed it.
 * [GuidelineEvidenceHost.prefetch] hands it every preview a round asks about at once, so a round
 * costs one fetch, not one per preview. [nodes] are nodes already in hand, served without a fetch.
 *
 * Each render opens a short session ([MatrixRenderFetcher]); follow-up renders are few, so that is
 * cheaper to keep simple than a session held for the whole run.
 */
internal class CliEvidenceHost(
  private val projectDir: File,
  private val moduleName: String,
  private val nodes: Map<String, List<AccessibilityNode>>,
  private val sources: (String) -> String?,
  private val fetcher: MatrixRenderFetcher =
    MatrixRenderFetcher(onLog = { System.err.println("[guidelines render] $it") }),
  private val a11yFetch: ((List<String>) -> A11yEvidence)? = null,
) : GuidelineEvidenceHost {
  private val fetchedNodes = mutableMapOf<String, List<AccessibilityNode>>()
  private val fetchedChecks = mutableMapOf<String, List<PreviewCheck>>()
  private val attempted = mutableSetOf<String>()

  override val available: List<String> =
    listOfNotNull(
      PreviewGuidelineRequests.KIND_A11Y.takeIf { a11yFetch != null || nodes.isNotEmpty() },
      GuidelineEvidenceNeedV1.KIND_SOURCE,
      GuidelineEvidenceNeedV1.KIND_RENDER,
    )

  /**
   * Once a fetch has produced nothing at all (a desktop module, whose daemon has no ATF; a daemon
   * that would not start), `a11y` is no longer offered, so later rounds and batches do not ask for
   * what cannot come. A preview fetched without data is not offered it again either.
   */
  private var a11yUnavailable = false

  override fun available(previewId: String): List<String> =
    if ((a11yUnavailable || previewId in attempted) && !hasData(previewId))
      available - PreviewGuidelineRequests.KIND_A11Y
    else available

  override fun prefetch(needs: Map<String, List<GuidelineEvidenceNeedV1>>) {
    fetchA11y(
      needs.filterValues { list -> list.any { it.kind == PreviewGuidelineRequests.KIND_A11Y } }.keys
    )
  }

  private fun fetchA11y(ids: Collection<String>) {
    val fetch = a11yFetch ?: return
    val wanted = ids.filter { it !in attempted && it !in nodes }
    if (wanted.isEmpty()) return
    attempted += wanted
    val got = runCatching { fetch(wanted) }.getOrElse { A11yEvidence() }
    wanted.forEach { id ->
      got.nodes[id]?.let { fetchedNodes[id] = it }
      got.checks[id]?.let { fetchedChecks[id] = it }
    }
    if (wanted.none(::hasData)) a11yUnavailable = true
  }

  /** Every accessibility node this run holds, fetched ones included: what an overlay outlines. */
  fun knownNodes(): Map<String, List<AccessibilityNode>> = nodes + fetchedNodes

  private fun hasData(previewId: String): Boolean =
    (nodes[previewId] ?: fetchedNodes[previewId]).orEmpty().isNotEmpty() ||
      fetchedChecks[previewId].orEmpty().isNotEmpty()

  override fun nodes(previewId: String): List<PreviewNode>? {
    fetchA11y(listOf(previewId))
    if (!hasData(previewId)) return null
    return (nodes[previewId] ?: fetchedNodes[previewId]).orEmpty().mapIndexedNotNull { index, node
      ->
      node.toPreviewNode(index)
    }
  }

  override fun checks(previewId: String): List<PreviewCheck>? {
    fetchA11y(listOf(previewId))
    return if (hasData(previewId)) fetchedChecks[previewId].orEmpty() else null
  }

  /** Only what is already in hand: summarising a preview not yet fetched would cost the fetch. */
  override fun summary(previewId: String): String? =
    if (hasData(previewId))
      PreviewGuidelineRequests.a11ySummary(
        nodes(previewId).orEmpty(),
        fetchedChecks[previewId].orEmpty(),
      )
    else null

  override fun source(previewId: String): String? = sources(previewId)

  override fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture? {
    val cell = cellFor(need) ?: return null
    val outcome = fetcher.fetch(projectDir, moduleName, previewId, listOf(cell))
    val png = (outcome as? MatrixRenderFetcher.Outcome.Ok)?.cells?.firstOrNull()?.png ?: return null
    return SubjectPicture(
      kind = "render",
      png = png,
      widthDp = need.widthDp ?: 0,
      heightDp = need.heightDp ?: 0,
      theme = need.theme,
      fontScale = need.fontScale,
      device = need.device,
      description =
        listOfNotNull(
            need.theme?.let { "$it theme" },
            need.fontScale?.let { "font scale $it" },
            need.device?.let { "device $it" },
            need.locale?.let { "locale $it" },
          )
          .joinToString(prefix = "re-rendered with ")
          .ifEmpty { null },
    )
  }

  companion object {
    /** The render cell for [need], or null when it asks for nothing a cell can change. */
    fun cellFor(need: GuidelineEvidenceNeedV1): MatrixCell? {
      val uiMode = need.theme?.lowercase()?.takeIf { it == "dark" || it == "light" }
      val cell =
        MatrixCell(
          device = need.device,
          locale = need.locale,
          uiMode = uiMode,
          fontScale = need.fontScale?.toFloat(),
        )
      return cell.takeIf {
        it.device != null || it.locale != null || it.uiMode != null || it.fontScale != null
      }
    }
  }
}

/**
 * [report] (an `accessibility.json`) without the entries of [ids], every other field kept. Run
 * before a narrowed fetch of [ids]: the fetch keeps a preview's previous entry when its own fetch
 * fails, and that entry may be of an older render. Leaves an unreadable file alone.
 */
internal fun dropA11yEntries(report: File, ids: Set<String>) {
  if (!report.isFile || ids.isEmpty()) return
  val root =
    runCatching { Json.parseToJsonElement(report.readText()) }.getOrNull() as? JsonObject ?: return
  val entries = root["entries"] as? JsonArray ?: return
  val kept = entries.filterNot { entry ->
    ((entry as? JsonObject)?.get("previewId") as? JsonPrimitive)?.contentOrNull in ids
  }
  if (kept.size == entries.size) return
  report.writeText(JsonObject(root + ("entries" to JsonArray(kept))).toString())
}
