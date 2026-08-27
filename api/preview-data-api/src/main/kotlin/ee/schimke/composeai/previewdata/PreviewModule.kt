package ee.schimke.composeai.previewdata

import java.io.File

/**
 * Handle for a subproject that applies `ee.schimke.composeai.preview`.
 *
 * [gradlePath] is the colon-separated project path **without** its leading colon (e.g. `"app"`,
 * `"auth:composables"`) — used to address Gradle tasks like
 * `":$gradlePath:composePreviewRenderAll"` and to identify the module in CLI output / persisted
 * state. [projectDir] is the actual filesystem directory of that subproject, resolved via Gradle's
 * Tooling API. Using it instead of `projectRoot/$gradlePath` is what makes nested subprojects
 * (`:foo:bar`) and any custom `project.projectDir` override work correctly — see issue #157.
 */
public data class PreviewModule(val gradlePath: String, val projectDir: File) : java.io.Serializable
