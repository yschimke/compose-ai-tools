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
  /**
   * Optional **catalog-id → daemon-preview-id** alias map, set only for a trusted-catalog live
   * session ([ServeCatalogStore] / [ServeBundleDaemon]). The daemon knows previews by their
   * function-based descriptor id (`FilledButton_Dark`), but the published catalog links and image
   * routes use the componentId-slug id (`button-filled__ideal__default__dark`). This maps the
   * latter to the former so the live host answers the published URLs. Empty for plain project /
   * revision sessions (whose ids already match). See [bakedFallback].
   */
  val previewAliases: Map<String, String> = emptyMap(),
  /**
   * Optional factory for a **baked-PNG fallback host** covering catalog ids the daemon can't render
   * (e.g. the Android-only inset focus-ring variant, absent from the desktop bundle). Set only for
   * a trusted-catalog live session: [openHost][ServeCommand] wraps the daemon [ServeRenderHost] and
   * this fallback in a [ServeCatalogLiveHost] so browsing, deep links, and thumbnails resolve to
   * the baked catalog exactly as before while the mapped ids gain a live lane. Rebuilt on each
   * resume (the baked dir persists), so suspend/resume is preserved. Null for plain sessions.
   */
  val bakedFallback: (() -> ServeHost)? = null,
)
