package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.RcPlayerBackend
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.render.matrix.ContactSheet
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `compose-preview render-cases <cases.json> --id <host preview>`: renders a list of Remote Compose
 * documents, each with its own named values, size and mode ([RemoteDocumentCases]), on the daemon's
 * Android lane, so each draws on the platform's own player under Robolectric rather than a desktop
 * one. Each case is a carried document (`overrides.remoteCompose.documentBase64`), which the daemon
 * replays in place of the host preview's own content; the host is any Remote Compose preview in a
 * module with the Remote Compose connector on its render classpath.
 *
 * Writes `<id>.png` per case under `--out` (default `<cases dir>/platform`), a
 * `compose-preview-cases/v1` summary (`render-cases.json`), and with `--contact-sheet` one stitched
 * PNG. Exits 1 when any case didn't render, 2 when none did.
 */
class RenderCasesCommand(args: List<String>) : Command(args) {
  private val jsonOutput = "--json" in args
  private val contactSheetRequested = "--contact-sheet" in args

  override fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }
    val casesFile =
      CliFlags.positionals(args).firstOrNull()?.let(::File)
        ?: run {
          System.err.println(
            "render-cases: name the cases file: render-cases <cases.json> --id <preview>",
          )
          exitProcess(64)
        }
    val cases =
      try {
        RemoteDocumentCases.read(casesFile)
      } catch (e: RemoteDocumentCases.Invalid) {
        System.err.println("render-cases: ${e.message}")
        exitProcess(64)
      }
    RemoteDocumentCases.pathCollisions(cases, reserved = setOf(CONTACT_SHEET, SUMMARY)).let {
      clashes ->
      if (clashes.isNotEmpty()) {
        clashes.forEach { System.err.println("render-cases: $it") }
        exitProcess(64)
      }
    }
    if (cases.isEmpty()) {
      System.err.println("render-cases: ${casesFile.path} has no cases")
      exitProcess(64)
    }
    val density =
      args.flagValue("--density")?.toFloatOrNull()?.takeIf { it > 0f } ?: DEFAULT_DENSITY
    var player: RemoteComposePlayerKind? = null
    var playerId: String? = null
    args.flagValue("--player")?.let { raw ->
      val backend =
        RcPlayerBackend.serverSideFromParam(raw)
          ?: run {
            System.err.println(
              "render-cases: --player must be drawn by the daemon: androidx-embedded, " +
                "androidx-view or cmp-android, got '$raw'",
            )
            exitProcess(64)
          }
      player = backend.playerKind
      playerId = backend.daemonPlayerId
    }
    val out =
      args.flagValue("--out")?.let(::File) ?: File(casesFile.absoluteFile.parentFile, "platform")

    val outcome = renderAllModules(silenceStdout = jsonOutput)
    if (!outcome.buildOk) {
      System.err.println("render-cases: render build failed.")
      exitProcess(2)
    }
    val matched =
      outcome.manifests
        .flatMap { (module, manifest) -> manifest.previews.map { Triple(module, manifest, it) } }
        .filter { (_, _, p) ->
          previewIdMatchesRequest(
            p.id,
            exactId = exactId,
            filter = filter,
            previewRef = previewRef,
            className = p.className,
            functionName = p.functionName,
            ids = idFileIds,
            function = functionSelector,
          )
        }
    if (matched.size != 1) {
      System.err.println(
        "render-cases: name one host Remote Compose preview with --id, --filter or --preview " +
          "(matched ${matched.size}). Its module needs the Remote Compose connector.",
      )
      matched.take(20).forEach { (_, _, p) -> System.err.println("  ${p.id}") }
      exitProcess(if (matched.isEmpty()) 3 else 1)
    }
    val (module, manifest, host) = matched.single()
    var started = true
    withGradle(silenceStdout = jsonOutput) { gradle ->
      started =
        withGradleStdout(jsonOutput) {
          runGradle(
            gradle,
            ":${module.gradlePath}:composePreviewDaemonStart",
            arguments = gradleArgsWithForce(),
          )
        }
    }
    if (!started) {
      System.err.println("render-cases: composePreviewDaemonStart failed for ${module.gradlePath}.")
      exitProcess(2)
    }

    cases
      .filter { it.skipped.isNotEmpty() }
      .forEach {
        System.err.println(
          "render-cases: ${it.id}: no daemon type for ${it.skipped.joinToString()}, left at the document's default",
        )
      }
    val fetched =
      MatrixRenderFetcher(onLog = { System.err.println("[daemon render-cases] $it") })
        .fetchCells(
          projectDir = module.projectDir,
          moduleName = manifest.module,
          previewId = host.id,
          cells = cases,
          labelOf = { it.id },
          overridesOf = { it.toOverrides(density, player, playerId) },
        )
    val results =
      when (fetched) {
        is MatrixRenderFetcher.Outcome.Ok -> fetched.cells
        is MatrixRenderFetcher.Outcome.DescriptorMissing -> {
          System.err.println("render-cases: missing daemon-launch.json at ${fetched.expected.path}")
          exitProcess(2)
        }
        is MatrixRenderFetcher.Outcome.OpenFailed -> {
          System.err.println("render-cases: failed to open render session (${fetched.reason})")
          exitProcess(2)
        }
      }
    val summary = write(out, host.id, results)
    if (jsonOutput) println(summary) else printHuman(out, results)
    val rendered = results.count { it.png != null }
    if (rendered == 0) exitProcess(2)
    if (rendered < results.size) exitProcess(1)
  }

  /**
   * Each case's PNG, the summary and (when asked) the contact sheet into [out]; the summary JSON.
   */
  internal fun write(
    out: File,
    hostId: String,
    results: List<MatrixRenderFetcher.CellResult<DocumentCase>>,
  ): String {
    out.mkdirs()
    val payload = buildJsonObject {
      put("schema", "compose-preview-cases/v1")
      put("host", hostId)
      put("caseCount", results.size)
      putJsonArray("cases") {
        results.forEach { r ->
          add(
            buildJsonObject {
              put("id", r.cell.id)
              val png = r.png
              if (png == null) {
                put("rendered", false)
              } else {
                val path = RemoteDocumentCases.pngPath(r.cell.id)
                File(out, path).apply { parentFile.mkdirs() }.writeBytes(png)
                put("pngPath", path)
                put(
                  "sha256",
                  MessageDigest.getInstance("SHA-256").digest(png).joinToString("") {
                    "%02x".format(it)
                  },
                )
              }
              if (r.cell.skipped.isNotEmpty())
                putJsonArray("skippedValues") { r.cell.skipped.forEach { add(it) } }
            },
          )
        }
      }
      if (contactSheetRequested) {
        val sheet =
          ContactSheet.stitch(
            results.mapNotNull { r -> r.png?.let { ContactSheet.Cell(r.cell.id, it) } },
          )
        if (sheet != null) {
          File(out, CONTACT_SHEET).writeBytes(sheet)
          put("contactSheet", CONTACT_SHEET)
        }
      }
    }
    val text =
      summaryJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)
    File(out, SUMMARY).writeText(text)
    return text
  }

  private fun printHuman(out: File, results: List<MatrixRenderFetcher.CellResult<DocumentCase>>) {
    println("Rendered ${results.count { it.png != null }}/${results.size} case(s) into ${out.path}")
    results.filter { it.png == null }.forEach { println("  ✗ ${it.cell.id}") }
    if (contactSheetRequested) println("Contact sheet: ${File(out, CONTACT_SHEET).path}")
  }

  private fun printUsage() {
    println(
      """
      compose-preview render-cases <cases.json> --id <host preview> [options]

      Render Remote Compose documents, each with its own named values, size and light or dark
      mode, on the Android lane: each plays on the platform's own player under Robolectric.
      Each case is replayed in place of the host preview's content, so the host is any Remote
      Compose preview in a module with the Remote Compose connector on its render classpath.

      The cases file: { "cases": [ { "id", "document" (relative to the file), "widthDp",
      "heightDp", "dark", "profile" or "target", "values": { name: { "type", "value" } } } ] }.
      Livery's renderLiveryPreviews writes one (cases.json).

      Options:

        --player <id>        androidx-embedded (the default), androidx-view or cmp-android
        --density <n>        Pixels per dp (default $DEFAULT_DENSITY)
        --out <dir>          Where the PNGs go (default <cases dir>/platform): <id>.png per case
                             and $SUMMARY
        --contact-sheet      Also stitch every case into $CONTACT_SHEET
        --json               Print the compose-preview-cases/v1 summary

      Exits 1 when a case didn't render, 2 when none did.
      """
        .trimIndent(),
    )
  }

  private companion object {
    const val DEFAULT_DENSITY = 2f
    const val SUMMARY = "render-cases.json"
    const val CONTACT_SHEET = "contact-sheet.png"
    val summaryJson = Json { prettyPrint = true }
  }
}
