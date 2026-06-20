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
 * The marker also records the configured render *intent* — the `composePreview { variant; enabled
 * }` values. This matters specifically for the **configuration-only** plugin: it deliberately does
 * not register the heavy [ComposePreviewModel] Tooling builder (no AGP / classpath resolution), so
 * for a config-only build the marker is the only runtime-free record of what was configured.
 * Consumers that want richer per-module state (resolved dependency versions, compat findings) still
 * go through the Tooling model, which the full runtime plugin registers.
 *
 * JSON shape (schema `compose-preview-applied/v1`):
 *
 *     {
 *       "schema": "compose-preview-applied/v1",
 *       "pluginVersion": "0.7.1",
 *       "modulePath": ":wearApp",
 *       "moduleName": "wearApp",
 *       "variant": "debug",
 *       "enabled": true
 *     }
 *
 * `variant` / `enabled` were added under the same `v1` schema: the additions are backwards
 * compatible (the VS Code reader pins to `v1` and ignores unknown keys), so bumping the schema
 * would needlessly break existing readers.
 */
@CacheableTask
abstract class ComposePreviewAppliedTask : DefaultTask() {

  @get:Input abstract val pluginVersion: Property<String>

  @get:Input abstract val modulePath: Property<String>

  @get:Input abstract val moduleName: Property<String>

  /** Configured `composePreview.variant` (or its convention default). */
  @get:Input abstract val variant: Property<String>

  /**
   * Configured `composePreview.enabled` (or its convention default). Named `previewsEnabled` rather
   * than `enabled` because every Gradle `Task` already exposes a final `enabled` boolean
   * (`Task.setEnabled`), which the input would clash with — Gradle can't decorate an abstract
   * `getEnabled()` over it. Serialized into the marker's `enabled` JSON field.
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
