package ee.schimke.composeai.cli

import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Drives a short-lived [RenderSession] for one module, walks every preview through `data/fetch` for
 * `a11y/atf`, aggregates the findings into the canonical `build/compose-previews
 * /accessibility.json` shape that [A11yReportRenderer] reads, and closes the session.
 *
 * Lives in the CLI rather than the render-session library because the aggregation shape is a CLI /
 * agent contract — third-party consumers that want raw `a11y/atf` payloads use the
 * [RenderSession.fetchData] API directly without buying into this aggregation format.
 *
 * @param factory pluggable render-session factory; defaults to the subprocess backend. Test
 *   scaffolding can inject a fake by constructing a custom [RenderSessionFactory].
 */
internal class DaemonA11yFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
) {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Fetch a11y findings for [previewIds] in one module, aggregate into
   * `<projectDir>/build/compose-previews/accessibility.json`, return the result.
   *
   * [projectDir] is the module's project directory (i.e. [PreviewModule.projectDir]), already
   * resolved by the Tooling API — `daemon-launch.json` sits directly under
   * `<projectDir>/build/compose-previews/` regardless of the module's gradle path, so we don't
   * route through [SubprocessRenderSessions.descriptorFile] (which derives the dir from a workspace
   * root + gradle path).
   *
   * [workspaceRoot] is the repository root the daemon reports back through the initialize
   * handshake; defaults to [projectDir] when the caller doesn't have a separate workspace root
   * handy (single-module projects).
   */
  fun fetch(
    projectDir: File,
    modulePath: String,
    moduleName: String,
    previewIds: List<String>,
    workspaceRoot: File = projectDir,
  ): Outcome {
    val descriptorFile = File(projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptorFile.isFile) return Outcome.DescriptorMissing(descriptorFile)

    val config =
      RenderSessionConfig(
        descriptorPath = descriptorFile,
        workspaceRoot = workspaceRoot.absoluteFile,
        workspaceName = workspaceRoot.name.ifBlank { moduleName },
        logSink = onLog,
      )

    val session: RenderSession =
      try {
        factory.open(config)
      } catch (e: RenderSessionException) {
        return Outcome.OpenFailed(reason = e.message ?: e.javaClass.simpleName)
      }

    return session.use { live ->
      // The daemon registers `a11y` as inactive metadata; `extensions/enable` flips it on so
      // `data/fetch` for `a11y/atf` resolves instead of returning `kind not advertised`. Mirrors
      // the MCP supervisor's handshake. Failures here are logged but non-fatal — we still try
      // the fetches so the user sees the error mode per preview rather than a single blanket
      // open-failed message.
      try {
        live.enableExtensions(listOf("a11y"))
      } catch (e: RenderSessionException) {
        onLog("extensions/enable for 'a11y' failed: ${e.message}")
      }
      val entries = mutableListOf<AccessibilityEntry>()
      for (previewId in previewIds) {
        val payload =
          try {
            live
              .fetchData(
                previewId = previewId,
                kind = ATF_KIND,
                inline = true,
                timeout = 120.seconds,
              )
              .payload
          } catch (e: DataProductException) {
            onLog("a11y fetch for '$previewId' failed: code=${e.code} ${e.wireMessage}")
            null
          } catch (e: RenderSessionException) {
            onLog("a11y fetch for '$previewId' transport error: ${e.message}")
            null
          }
        val findings = payload?.let(::parseFindings).orEmpty()
        entries.add(
          AccessibilityEntry(
            previewId = previewId,
            findings = findings,
            annotatedPath = relativeOverlayPath(projectDir, previewId),
          )
        )
      }
      val reportFile = projectDir.resolve("build/compose-previews/accessibility.json")
      reportFile.parentFile?.mkdirs()
      val report = AccessibilityReport(module = moduleName, entries = entries)
      reportFile.writeText(json.encodeToString(AccessibilityReport.serializer(), report))
      Outcome.Ok(reportFile = reportFile, entryCount = entries.size)
    }
  }

  /**
   * Resolve the daemon-side overlay PNG (`a11y-overlay.png`) for [previewId] relative to the
   * accessibility.json that will be written. Returns null when the file is absent.
   */
  private fun relativeOverlayPath(projectDir: File, previewId: String): String? {
    val overlay = projectDir.resolve("build/compose-previews/data/$previewId/a11y-overlay.png")
    return overlay.takeIf { it.isFile }?.let { "data/$previewId/a11y-overlay.png" }
  }

  private fun parseFindings(payload: JsonElement): List<AccessibilityFinding> {
    val obj = payload as? JsonObject ?: return emptyList()
    val findings = obj["findings"] ?: return emptyList()
    return try {
      json.decodeFromJsonElement(
        kotlinx.serialization.builtins.ListSerializer(AccessibilityFinding.serializer()),
        findings,
      )
    } catch (_: Exception) {
      emptyList()
    }
  }

  sealed interface Outcome {
    data class Ok(val reportFile: File, val entryCount: Int) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  companion object {
    private const val ATF_KIND = "a11y/atf"
  }
}
