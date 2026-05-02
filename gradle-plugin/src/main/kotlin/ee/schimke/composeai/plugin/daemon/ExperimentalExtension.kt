package ee.schimke.composeai.plugin.daemon

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

/**
 * `composePreview.experimental { … }` namespace. Kept for backward source compatibility while
 * features graduate to stable top-level DSL blocks.
 *
 * The daemon configuration has moved to `composePreview.daemon { … }`; this legacy block is no
 * longer read by the plugin.
 */
abstract class ExperimentalExtension @Inject constructor(objects: ObjectFactory) {
  val daemon: DaemonExtension = objects.newInstance(DaemonExtension::class.java)

  fun daemon(action: Action<DaemonExtension>) {
    action.execute(daemon)
  }
}
