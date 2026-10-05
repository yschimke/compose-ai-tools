package ee.schimke.composeai.render.session

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Factory for opening [RenderSession]s. Lives in `:render-session-api` so consumers can hold the
 * factory shape without committing to a specific backend module at compile time; backend
 * implementations register themselves through this interface.
 *
 * Typical usage from `:render-session-subprocess`:
 * ```kotlin
 * val factory: RenderSessionFactory = SubprocessRenderSessions
 * val session = factory.open(
 *   RenderSessionConfig(
 *     descriptorPath = File("samples/android/build/compose-previews/daemon-launch.json"),
 *     workspaceRoot = File("/path/to/repo"),
 *   ),
 * )
 * session.use { … }
 * ```
 */
public interface RenderSessionFactory {
  /** Backend this factory produces. */
  public val backendKind: RenderSessionBackend

  /**
   * Open and initialize a session. Throws [RenderSessionException] (or subclass) on transport /
   * descriptor / handshake failure — callers don't observe an un-initialized session.
   */
  public fun open(config: RenderSessionConfig): RenderSession
}

/**
 * Inputs shared by every backend's [RenderSessionFactory.open]. Backend-specific knobs (e.g.
 * subprocess JVM args overrides) live on per-backend extensions; this base record holds the minimum
 * every backend needs.
 */
public data class RenderSessionConfig(
  /**
   * The `build/compose-previews/daemon-launch.json` written by `composePreviewDaemonStart`: the
   * source of truth for classpath and JVM args, for every backend.
   */
  val descriptorPath: File,
  /**
   * Workspace root reported to the daemon as the user's project root. Defaults to inferring from
   * [descriptorPath] (two directories up from the `build/compose-previews/` subdirectory). Pass
   * explicitly when the descriptor lives outside the workspace tree.
   */
  val workspaceRoot: File = inferWorkspaceRoot(descriptorPath),
  /**
   * Workspace name surfaced in diagnostic messages and used to derive the workspace id the backend
   * reports back. Defaults to the directory name of [workspaceRoot].
   */
  val workspaceName: String = workspaceRoot.name.ifBlank { "workspace" },
  /**
   * Ignore the descriptor's `enabled = false`, a VS Code launcher gate that shouldn't apply to
   * explicit CLI / library opens.
   */
  val forceEnabled: Boolean = true,
  /**
   * System properties merged over the descriptor, e.g. a separate output root per daemon when
   * several are opened from one descriptor.
   */
  val systemPropertyOverrides: Map<String, String> = emptyMap(),
  /**
   * Log sink for the backend's diagnostic output (subprocess stderr, embedded driver
   * stderr-equivalent). Defaults to forwarding to `System.err` with a `[render-session]` prefix.
   */
  val logSink: (String) -> Unit = { System.err.println("[render-session] $it") },
  /** Upper bound on the initialize handshake. */
  val initializeTimeout: Duration = 60.seconds,
  /**
   * Per-render deadline advertised to the daemon; batch clients should match their own budget so
   * the daemon's internal timeout can't fire first. Null keeps the daemon default.
   */
  val maxRenderTime: Duration? = null,
  /** Total shutdown budget for a subprocess session; null keeps the graceful default. */
  val shutdownTimeout: Duration? = null,
) {
  public companion object {
    private fun inferWorkspaceRoot(descriptorPath: File): File =
      descriptorPath.parentFile?.parentFile?.parentFile ?: descriptorPath.absoluteFile
  }
}
