package ee.schimke.composeai.cli

import ee.schimke.composeai.mcp.ContactSheet
import ee.schimke.composeai.mcp.MatrixAxes
import ee.schimke.composeai.mcp.MatrixCell
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path.Companion.toPath

/**
 * `compose-preview render-matrix` — the CLI counterpart of the `render_matrix` MCP tool
 * (issue #1788). Render one preview across a cross-product of display axes (`--device` × `--locale`
 * × `--ui-mode` × `--font-scale`) and report a token-frugal per-cell summary (`overrides`, `label`,
 * `sha256`, dimensions, `changed` vs the first cell), optionally writing a single stitched
 * contact-sheet PNG with `--contact-sheet`.
 *
 * Drives the same daemon `RenderSession` the a11y / semantics flows use: a standard
 * `composePreviewRenderAll` build to discover previews + a `composePreviewDaemonStart` to
 * materialise the launch descriptor, then a short-lived session that renders each cell with its
 * overrides.
 */
class RenderMatrixCommand(args: List<String>) : Command(args) {
  private val jsonOutput = "--json" in args

  /** `--contact-sheet` (default path) or `--contact-sheet=<path>` / `--contact-sheet <path>`. */
  private val contactSheetRequested = args.any {
    it == "--contact-sheet" || it.startsWith("--contact-sheet=")
  }
  private val contactSheetExplicitPath =
    args.firstOrNull { it.startsWith("--contact-sheet=") }?.substringAfter("=")
      ?: run {
        val idx = args.indexOf("--contact-sheet")
        if (idx >= 0 && idx + 1 < args.size && !args[idx + 1].startsWith("-")) args[idx + 1]
        else null
      }

  override fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    val devices = axisValues("--device")
    val locales = axisValues("--locale")
    val uiModes = axisValues("--ui-mode")
    val badUiMode = uiModes?.firstOrNull { it.lowercase() !in setOf("light", "dark") }
    if (badUiMode != null) {
      System.err.println("render-matrix: --ui-mode must be 'light' or 'dark', got '$badUiMode'")
      exitProcess(64)
    }
    val fontScaleRaw = axisValues("--font-scale")
    val fontScales = fontScaleRaw?.map { it.toFloatOrNull() }
    if (fontScales?.any { it == null } == true) {
      System.err.println(
        "render-matrix: --font-scale must be numbers, got '${fontScaleRaw!!.joinToString(",")}'"
      )
      exitProcess(64)
    }
    val fontScaleValues = fontScales?.filterNotNull()

    if (devices == null && locales == null && uiModes == null && fontScaleValues == null) {
      System.err.println(
        "render-matrix: set at least one axis: --device, --locale, --ui-mode, --font-scale"
      )
      printUsage()
      exitProcess(64)
    }

    val cellCount = MatrixAxes.cellCount(devices, locales, uiModes, fontScaleValues)
    if (cellCount > MatrixAxes.CELL_CAP) {
      System.err.println(
        "render-matrix: $cellCount cells exceeds the cap of ${MatrixAxes.CELL_CAP}; narrow the axes"
      )
      exitProcess(64)
    }
    val cells = MatrixAxes.expand(devices, locales, uiModes, fontScaleValues)

    // Standard render: builds the module(s) and writes each preview manifest so we can resolve the
    // single target preview. `--module` / `--id` / `--filter` narrow which modules render.
    val outcome = renderAllModules(silenceStdout = jsonOutput)
    if (!outcome.buildOk) {
      System.err.println("render-matrix: render build failed.")
      exitProcess(2)
    }

    val candidates =
      outcome.manifests.flatMap { (module, manifest) ->
        manifest.previews.map { Triple(module, manifest, it) }
      }
    val matched = candidates.filter { (_, _, preview) -> matchesPreview(preview.id) }
    if (matched.isEmpty()) {
      System.err.println(
        "render-matrix: no previews matched. Use --id <exact> or --filter <substr>."
      )
      exitProcess(3)
    }
    if (matched.size > 1) {
      System.err.println(
        "render-matrix: matched ${matched.size} previews; narrow to one with --id <exact> or " +
          "--filter <substr>:"
      )
      matched.take(20).forEach { (_, _, p) -> System.err.println("  ${p.id}") }
      exitProcess(1)
    }
    val (module, manifest, preview) = matched.single()

    if (!runDaemonStart(module)) {
      System.err.println(
        "render-matrix: composePreviewDaemonStart failed for ${module.gradlePath}."
      )
      exitProcess(2)
    }

    val fetcher = MatrixRenderFetcher(onLog = { System.err.println("[daemon render-matrix] $it") })
    when (
      val fetched =
        fetcher.fetch(
          projectDir = module.projectDir,
          moduleName = manifest.module,
          previewId = preview.id,
          cells = cells,
        )
    ) {
      is MatrixRenderFetcher.Outcome.DescriptorMissing -> {
        System.err.println("render-matrix: missing daemon-launch.json at ${fetched.expected.path}")
        exitProcess(2)
      }
      is MatrixRenderFetcher.Outcome.OpenFailed -> {
        System.err.println("render-matrix: failed to open render session (${fetched.reason})")
        exitProcess(2)
      }
      is MatrixRenderFetcher.Outcome.Ok -> {
        report(module, preview.id, fetched.cells)
      }
    }
  }

  /** Comma/semicolon-batched, repeatable axis flag → distinct values, or null when unset. */
  private fun axisValues(flag: String): List<String>? =
    args
      .flagValuesAll(flag)
      .flatMap { it.split(',', ';') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .takeIf { it.isNotEmpty() }

  /**
   * Match a preview id against `--id` (exact) / `--filter` (substring); all when neither is set.
   */
  private fun matchesPreview(id: String): Boolean =
    when {
      exactId != null -> id == exactId
      filter != null -> id.contains(filter!!, ignoreCase = true)
      else -> true
    }

  private fun runDaemonStart(module: PreviewModule): Boolean {
    var ok = true
    withGradle(silenceStdout = jsonOutput) { gradle ->
      ok =
        withGradleStdout(jsonOutput) {
          runGradle(
            gradle,
            ":${module.gradlePath}:composePreviewDaemonStart",
            arguments = gradleArgsWithForce(),
          )
        }
    }
    return ok
  }

  private fun report(
    module: PreviewModule,
    previewId: String,
    cells: List<MatrixRenderFetcher.CellResult>,
  ) {
    var baselineSha: String? = null
    val rows = cells.map { cr ->
      val sha = cr.png?.let { sha256Hex(it) }
      val dims = cr.png?.let { pngDimensions(it) }
      if (baselineSha == null && sha != null) baselineSha = sha
      Row(cr.cell, sha, dims, changed = sha != null && sha != baselineSha)
    }

    val contactSheetWritten =
      if (contactSheetRequested) {
        writeContactSheet(module, previewId, cells)
      } else {
        null
      }

    if (jsonOutput) {
      println(renderJson(module, previewId, rows, contactSheetWritten))
    } else {
      printHuman(module, previewId, rows, contactSheetWritten)
    }

    // Non-zero when no cell rendered at all; a partial render still exits 0 with the failures
    // logged.
    if (rows.none { it.sha != null }) exitProcess(2)
  }

  private fun writeContactSheet(
    module: PreviewModule,
    previewId: String,
    cells: List<MatrixRenderFetcher.CellResult>,
  ): File? {
    val tiles = cells.mapNotNull { cr -> cr.png?.let { ContactSheet.Cell(cr.cell.label, it) } }
    if (tiles.isEmpty()) {
      System.err.println("render-matrix: no cells rendered; skipping contact sheet.")
      return null
    }
    val sheet = ContactSheet.stitch(tiles) ?: return null
    // Production IO goes through the injected Okio FileSystem (docs/AGENTS.md), so tests can drive
    // the write through a FakeFileSystem; bridge back to File only for the reported path.
    val targetPath =
      contactSheetExplicitPath?.toPath()
        ?: (module.projectDir.path.toPath() /
          "build/compose-previews" /
          "${previewId.replace(Regex("[^A-Za-z0-9._-]"), "_")}-matrix.png")
    targetPath.parent?.let { fileSystem.createDirectories(it) }
    fileSystem.write(targetPath) { write(sheet) }
    return File(targetPath.toString())
  }

  private fun renderJson(
    module: PreviewModule,
    previewId: String,
    rows: List<Row>,
    contactSheet: File?,
  ): String {
    val payload = buildJsonObject {
      put("schema", "compose-preview-matrix/v1")
      put("module", module.gradlePath)
      put("preview", previewId)
      put("cellCount", rows.size)
      contactSheet?.let { put("contactSheet", it.path) }
      putJsonArray("cells") {
        rows.forEach { row ->
          add(
            buildJsonObject {
              put("overrides", row.cell.overridesJson())
              put("label", row.cell.label)
              if (row.sha != null) {
                put("sha256", row.sha)
                row.dims?.let {
                  put("widthPx", it.first)
                  put("heightPx", it.second)
                }
                put("changed", row.changed)
              } else {
                put("rendered", false)
              }
            }
          )
        }
      }
    }
    return matrixJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)
  }

  private fun printHuman(
    module: PreviewModule,
    previewId: String,
    rows: List<Row>,
    contactSheet: File?,
  ) {
    val ok = rows.count { it.sha != null }
    println("Rendered $ok/${rows.size} cell(s) for $previewId (${module.gradlePath})")
    val labelWidth = (rows.maxOfOrNull { it.cell.label.length } ?: 0).coerceAtMost(40)
    for (row in rows) {
      val mark = if (row.sha != null) "✓" else "✗"
      val label = row.cell.label.padEnd(labelWidth)
      val detail =
        if (row.sha != null) {
          val dims = row.dims?.let { "${it.first}x${it.second}" } ?: "?"
          val changed = if (row.changed) "  changed" else ""
          "${row.sha.take(8)}  $dims$changed"
        } else {
          "(render failed)"
        }
      println("  $mark $label  $detail")
    }
    contactSheet?.let { println("Contact sheet: ${it.path}") }
  }

  private fun printUsage() {
    println(
      """
      compose-preview render-matrix --id <preview> [axes] [--contact-sheet[=path]] [--json]

      Render one preview across a cross-product of display axes and report a per-cell summary
      (label, sha256, dimensions, and `changed` vs the first cell) — "does this survive small
      screen + RTL + large font?" in one command. The CLI counterpart of the render_matrix MCP
      tool (issue #1788). Bounded at ${MatrixAxes.CELL_CAP} cells.

      Target one preview with --id <exact> or --filter <substring> (and --module to scope the
      build). At least one axis is required:

        --device <ids>       @Preview(device=...) ids/specs, e.g. id:pixel_5,id:pixel_tablet
        --locale <tags>      BCP-47 locale tags, e.g. en,ar,ja-JP
        --ui-mode <modes>    light,dark
        --font-scale <nums>  font-scale multipliers, e.g. 1.0,2.0

      Each axis is comma-separated and repeatable. Other options:

        --contact-sheet[=path]  Also write a stitched grid PNG of every cell (default path:
                                <module>/build/compose-previews/<id>-matrix.png).
        --json                  Emit the compose-preview-matrix/v1 JSON summary.
      """
        .trimIndent()
    )
  }

  /** Parse a PNG's IHDR width/height (big-endian, byte offsets 16/20) without decoding pixels. */
  private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    fun int32(offset: Int): Int =
      ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
    return int32(16) to int32(20)
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private class Row(
    val cell: MatrixCell,
    val sha: String?,
    val dims: Pair<Int, Int>?,
    val changed: Boolean,
  )

  private companion object {
    val matrixJson = Json { prettyPrint = true }
  }
}
