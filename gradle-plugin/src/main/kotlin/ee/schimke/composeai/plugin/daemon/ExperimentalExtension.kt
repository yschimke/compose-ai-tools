package ee.schimke.composeai.plugin.daemon

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

/**
 * `composePreview.experimental { … }` namespace. Holds in-progress / unstable features whose DSL
 * shape is not yet covered by the plugin's compatibility promise. Today: just [daemon] (Phase 1,
 * Stream A — `docs/daemon/`).
 *
 * Kept as its own type (rather than dumping `daemon` directly under
 * [ee.schimke.composeai.plugin.PreviewExtension]) so that consumers writing
 * `composePreview.experimental.daemon { … }` get a clear "this is experimental" signal both in the
 * build script and in IDE autocomplete. Future experimental features land here without polluting
 * the top-level namespace.
 */
abstract class ExperimentalExtension @Inject constructor(objects: ObjectFactory) {
  val daemon: DaemonExtension = objects.newInstance(DaemonExtension::class.java)

  fun daemon(action: Action<DaemonExtension>) {
    action.execute(daemon)
  }
}
