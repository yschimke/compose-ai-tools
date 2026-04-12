package ee.schimke.composeai.renderer

import kotlinx.serialization.Serializable

@Serializable
data class RenderManifest(
    val module: String,
    val variant: String,
    val previews: List<RenderPreviewEntry>,
)

@Serializable
data class RenderPreviewEntry(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: RenderPreviewParams = RenderPreviewParams(),
    val renderOutput: String? = null,
)

@Serializable
data class RenderPreviewParams(
    val name: String? = null,
    val device: String? = null,
    val widthDp: Int = 0,
    val heightDp: Int = 0,
    val fontScale: Float = 1.0f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val locale: String? = null,
    val group: String? = null,
    /** FQN of the `PreviewWrapperProvider` from `@PreviewWrapper`, if any. */
    val wrapperClassName: String? = null,
)
