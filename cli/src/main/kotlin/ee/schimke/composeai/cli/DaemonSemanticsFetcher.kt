package ee.schimke.composeai.cli

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import kotlin.time.Duration.Companion.seconds
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Drives a short-lived [RenderSession] for one module, renders the requested previews so the
 * daemon's always-on `compose/semantics` extension writes each one's `compose-semantics.json`
 * sidecar, then reads those sidecars back off disk. Used by `compose-preview bundle pack
 * --with-semantics` to carry the per-preview semantics blob (the [ComposeSemanticsProduct] tree
 * with resolved foreground/background colours) inside the bundle as `previews/<id>.semantics.json`
 * — the shape the design-parity static bundle reader consumes (issue #1843).
 *
 * The standalone `composePreviewRender` Gradle task is "normal render only" and never produces
 * semantics — the daemon is the single producer (see `docs/AGENTS.md`). So unlike the rest of
 * `pack` (which is pure Gradle), the semantics blob is obtained by spinning up the same daemon the
 * VS Code extension / MCP server / `compose-preview a11y` use, fetching, and shutting it down.
 *
 * @param factory pluggable render-session factory; defaults to the subprocess backend. Test
 *   scaffolding can inject a fake by constructing a custom [RenderSessionFactory].
 */
internal class DaemonSemanticsFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  /**
   * Render [previewIds] through a temporary daemon and return each preview's
   * `compose-semantics.json` bytes, keyed by preview id. Previews whose sidecar never materialised
   * (a `@PreviewParameter` fan-out whose data dir is keyed by a per-value base name, a render that
   * failed, an unsupported backend) are simply absent from the returned map — the caller carries
   * what it got and reports the rest.
   *
   * [projectDir] is the module's project directory (`PreviewModule.projectDir`);
   * `daemon-launch.json` and the daemon's `build/compose-previews/data/<id>/` output both sit under
   * it regardless of the module's gradle path.
   */
  fun fetch(
    projectDir: File,
    moduleName: String,
    previewIds: List<String>,
    workspaceRoot: File = projectDir,
  ): Outcome {
    if (previewIds.isEmpty()) return Outcome.Ok(emptyMap())

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
      // Render so the always-on ComposeSemanticsExtension writes each preview's sidecar. A failure
      // here is non-fatal — some previews may still have rendered, so we fall through to the disk
      // read and carry whatever materialised.
      try {
        live.renderNow(
          previewIds = previewIds,
          reason = "bundle pack semantics",
          timeout = SEMANTICS_RENDER_TIMEOUT,
        )
      } catch (e: RenderSessionException) {
        onLog("renderNow for semantics failed: ${e.message}")
      }

      val byId = LinkedHashMap<String, ByteArray>()
      for (previewId in previewIds) {
        val file = sidecarFile(projectDir, previewId)
        if (file.isFile && file.length() > 0) {
          byId[previewId] = fileSystem.read(file.path.toPath()) { readByteArray() }
        } else {
          onLog("no ${ComposeSemanticsProduct.FILE} for '$previewId'")
        }
      }
      Outcome.Ok(semanticsById = byId)
    }
  }

  private fun sidecarFile(projectDir: File, previewId: String): File =
    File(projectDir, "build/compose-previews/data/$previewId/${ComposeSemanticsProduct.FILE}")

  sealed interface Outcome {
    /**
     * Session opened and renders attempted. [semanticsById] holds one entry per preview whose
     * `compose-semantics.json` materialised — possibly empty if every render failed.
     */
    data class Ok(val semanticsById: Map<String, ByteArray>) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  private companion object {
    /**
     * Generous per-`renderNow` budget — the daemon renders every selected preview in one call, and
     * the first render also pays the sandbox cold-start.
     */
    val SEMANTICS_RENDER_TIMEOUT = 180.seconds
  }
}
