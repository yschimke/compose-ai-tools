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
 * Writes `<module>/build/compose-previews/applied.json` so IDE tooling can find which modules apply
 * the plugin without parsing build scripts; `gradle composePreviewApplied` fans out to every
 * applying module. VS Code can only run tasks via vscode-gradle, not query the
 * [ComposePreviewModel] Tooling API, so a small JSON is the bridge.
 *
 * It also records `composePreview { variant; enabled }`, the only runtime-free record of
 * configuration for a config-only build (which doesn't register the Tooling model).
 *
 * Schema `compose-preview-applied/v1` (readers ignore unknown keys, so additive fields stay `v1`):
 * ```
 * {
 *   "schema": "compose-preview-applied/v1",
 *   "pluginVersion": "0.7.1",
 *   "modulePath": ":wearApp",
 *   "moduleName": "wearApp",
 *   "variant": "debug",
 *   "enabled": true
 * }
 * ```
 */
@CacheableTask
abstract class ComposePreviewAppliedTask : DefaultTask() {

  @get:Input abstract val pluginVersion: Property<String>

  @get:Input abstract val modulePath: Property<String>

  @get:Input abstract val moduleName: Property<String>

  /** Configured `composePreview.variant` (or its convention default). */
  @get:Input abstract val variant: Property<String>

  /**
   * Configured `composePreview.enabled`, written as the marker's `enabled`. Not named `enabled`,
   * which would clash with `Task.getEnabled()`.
   */
  @get:Input abstract val previewsEnabled: Property<Boolean>

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
        variant = variant.get(),
        enabled = previewsEnabled.get(),
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
  val variant: String,
  val enabled: Boolean,
)
