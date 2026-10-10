package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.GuidelineEvidenceHost
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.PreviewCheck
import ee.schimke.composeai.guidelines.PreviewGuidelineRequests
import ee.schimke.composeai.guidelines.PreviewNode
import ee.schimke.composeai.guidelines.PreviewSubject
import ee.schimke.composeai.guidelines.SubjectPicture
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.previewdata.AccessibilityNode
import ee.schimke.composeai.previewdata.AccessibilityReport
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
) {
  /**
   * What a follow-up round may ask for in handoff mode: only captures the render job already made
   * and staged beside each render (the long screenshot of its scrolling content). The job holding
   * the key never builds or renders, so nothing else is fetchable.
   */
  val host: GuidelineEvidenceHost
    get() = HandoffEvidenceHost(renders, sizes)

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * [previewsJson] is either a flat list of ids (strings, or objects with an `id`) narrowing the
     * PNGs in [rendersDir], or a module's `previews.json`, whose captures name each render
     * (resolved beside the file, then by name in [rendersDir]) and whose `sourceFile`/`bodyLine`
     * give each preview's source under [sourceRoot]. [a11yJson] is an `accessibility.json`.
     */
    fun read(
      previewsJson: File?,
      rendersDir: File?,
      a11yJson: File?,
      sourceRoot: File?,
      surfaceOverride: String?,
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
              screen = params?.text("device") != null,
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
          surface =
            surfaceOverride
              ?: if (entry.screen) GuidelineSurfaces.SCREEN else GuidelineSurfaces.COMPONENT,
          renderHash = sha256(bytes),
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
          nodes =
            nodes[entry.id].orEmpty().mapIndexedNotNull { index, node ->
              node.toPreviewNode(index)
            },
          source = source,
          checks = checks[entry.id].orEmpty(),
        )
      }
      return HandoffInputs(
        subjects,
        nodes,
        entries.associate { it.id to it.render },
        entries.associate { it.id to (it.widthDp to it.heightDp) },
      )
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
    val screen: Boolean = false,
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
) : GuidelineEvidenceHost {
  private val captures: Map<String, File> =
    renders
      .mapNotNull { (id, render) -> render?.let(HandoffInputs::longCapture)?.let { id to it } }
      .toMap()

  override val available: List<String> =
    if (captures.isEmpty()) emptyList() else listOf(PreviewGuidelineRequests.KIND_SCROLL_CAPTURE)

  override fun available(previewId: String): List<String> =
    if (previewId in captures) available else emptyList()

  override fun render(previewId: String, need: GuidelineEvidenceNeedV1): SubjectPicture? {
    if (need.kind != PreviewGuidelineRequests.KIND_SCROLL_CAPTURE) return null
    val file = captures[previewId] ?: return null
    val (width, height) = sizes[previewId] ?: (0 to 0)
    return HandoffInputs.longPicture(file, width, height)
  }
}

/**
 * What a live CLI run can fetch when the model asks for more: the nodes and source it already read,
 * and a render at other settings (theme, font scale, device, locale) through the module's render
 * daemon. Each render opens a short session ([MatrixRenderFetcher]); follow-up renders are few, so
 * that is cheaper to keep simple than a session held for the whole run.
 */
internal class CliEvidenceHost(
  private val projectDir: File,
  private val moduleName: String,
  private val nodes: Map<String, List<AccessibilityNode>>,
  private val sources: (String) -> String?,
  private val fetcher: MatrixRenderFetcher =
    MatrixRenderFetcher(onLog = { System.err.println("[guidelines render] $it") }),
) : GuidelineEvidenceHost {
  override val available: List<String> =
    listOf(
      GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY,
      GuidelineEvidenceNeedV1.KIND_SOURCE,
      GuidelineEvidenceNeedV1.KIND_RENDER,
    )

  override fun nodes(previewId: String): List<PreviewNode>? =
    nodes[previewId]?.mapIndexedNotNull { index, node -> node.toPreviewNode(index) }

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
