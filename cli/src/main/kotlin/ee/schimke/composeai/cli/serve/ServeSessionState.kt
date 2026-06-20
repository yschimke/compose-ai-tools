package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * The cheap, durable "instance state" of a serve session — everything needed to (re)open its
 * daemon-backed [ServeRenderHost] *without* rebuilding. Produced once by a [ServeSessionFactory]
 * (the expensive discover/build step) and retained across suspend/resume, so an idle session can
 * release its daemon subprocess and be brought back from this state alone — like an Activity
 * restoring from saved instance state rather than being recreated from scratch.
 *
 * Everything here already persists on disk (the `daemon-launch.json` descriptor + the discovered
 * preview list), so holding it costs almost nothing while the daemon is suspended.
 */
data class ServeSessionState(
  /** `build/compose-previews/daemon-launch.json` the daemon relaunches from. */
  val descriptor: File,
  val workspaceRoot: File,
  val workspaceName: String,
  val previews: List<ServePreview>,
  /** Human label for the tenant (e.g. the module's Gradle path, or `module@rev`). */
  val label: String,
)
