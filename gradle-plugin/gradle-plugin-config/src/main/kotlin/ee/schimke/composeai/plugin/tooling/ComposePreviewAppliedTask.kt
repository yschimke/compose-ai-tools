package ee.schimke.composeai.plugin.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes a tiny applied-marker JSON to `<module>/build/compose-previews/applied.json`.
 *
 * Exists so IDE-side tooling (the VS Code extension) can reliably discover which modules apply the
 * plugin *without* parsing build scripts or the Gradle version catalog. `gradle
 * composePreviewApplied` (no module prefix) fans out to every applying subproject, producing one
 * marker per module.
 *
 * Parallels the sidecar-JSON approach used by [ComposePreviewDoctorTask]: the VS Code extension
 * uses the vscode-gradle API, which only exposes `runTask` — it cannot call the
 * [ComposePreviewModel] Tooling API directly the way the CLI does. A task that writes a small JSON
 * is the cheapest authoritative bridge.
 *
 * JSON shape (schema `compose-preview-applied/v1`):
 *
 *     {
 *       "schema": "compose-preview-applied/v1",
 *       "pluginVersion": "0.7.1",
 *       "modulePath": ":wearApp",
 *       "moduleName": "wearApp"
 *     }
 *
 * Kept deliberately minimal — downstream tools only need "does the plugin apply here?". If more
 * per-module metadata is needed later, extend the Tooling-API [ComposePreviewModel] rather than
 * widening the marker.
 */
@CacheableTask
abstract class ComposePreviewAppliedTask : DefaultTask() {

  @get:Input abstract val pluginVersion: Property<String>

  @get:Input abstract val modulePath: Property<String>

  @get:Input abstract val moduleName: Property<String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun write() {
    val out = outputFile.get().asFile
    out.parentFile.mkdirs()
    val marker =
      AppliedMarker(
        schema = SCHEMA,
        pluginVersion = pluginVersion.get(),
        modulePath = modulePath.get(),
        moduleName = moduleName.get(),
      )
    out.writeText(JSON.encodeToString(marker))
  }

  companion object {
    internal const val SCHEMA = "compose-preview-applied/v1"
    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }
  }
}

@Serializable
internal data class AppliedMarker(
  val schema: String,
  val pluginVersion: String,
  val modulePath: String,
  val moduleName: String,
)
