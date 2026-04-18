package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable

/**
 * Which @Preview flavour the entry came from. Drives renderer selection —
 * [COMPOSE] previews are `@Composable` functions invoked through the normal
 * Compose machinery; [TILE] previews are plain functions returning
 * `androidx.wear.tiles.tooling.preview.TilePreviewData` that need to be
 * inflated via `androidx.wear.tiles.renderer.TileRenderer`.
 */
enum class PreviewKind {
    COMPOSE,
    TILE,
}

@Serializable
data class PreviewParams(
    val name: String? = null,
    val device: String? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val fontScale: Float = 1.0f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val locale: String? = null,
    val group: String? = null,
    /** FQN of the `PreviewWrapperProvider` from `@PreviewWrapper`, if any. */
    val wrapperClassName: String? = null,
    val kind: PreviewKind = PreviewKind.COMPOSE,
    /**
     * Virtual-time offset to advance `mainClock` by before capture, sourced from
     * Roborazzi's `@RoboComposePreviewOptions(manualClockOptions = [...])`. `null`
     * means "use the renderer's default" (a small fixed step). A preview with
     * `manualClockOptions = [ManualClockOptions(500L), ManualClockOptions(1000L)]`
     * expands at discovery time into two entries, each carrying one of these
     * values — one render per variant.
     */
    val advanceTimeMillis: Long? = null,
)

@Serializable
data class PreviewInfo(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: PreviewParams = PreviewParams(),
    val renderOutput: String? = null,
)

@Serializable
data class PreviewManifest(
    val module: String,
    val variant: String,
    val previews: List<PreviewInfo>,
    /**
     * Relative path (from this manifest's parent directory) to a sidecar
     * accessibility report JSON, when `composePreview.accessibilityChecks.enabled`
     * is `true`. `null` signals the feature is off — downstream tools should
     * treat the absence of this pointer as "no a11y data" rather than probing
     * for the file on disk.
     */
    val accessibilityReport: String? = null,
)
