package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.BackendKind

/**
 * Per-render context passed from a renderer backend to data products.
 *
 * The core module deliberately keeps renderer objects opaque. Compose-backed renderers may attach
 * slot-table roots (currently `androidx.compose.runtime.tooling.CompositionData`) through
 * [inspection]; data products that understand that runtime can cast and inspect them, while the
 * daemon protocol and non-Compose producers stay decoupled from Compose internals.
 */
data class PreviewContext(
  val previewId: String?,
  val backend: BackendKind?,
  val renderMode: String?,
  val outputBaseName: String?,
  val device: PreviewDeviceContext = PreviewDeviceContext(),
  val frameTime: PreviewFrameTime = PreviewFrameTime(),
  val inspection: PreviewInspectionContext = PreviewInspectionContext(),
  val animation: PreviewAnimationContext? = null,
) {
  class Builder(
    private val previewId: String?,
    private val backend: BackendKind?,
    private val renderMode: String?,
    private val outputBaseName: String?,
  ) {
    private var device: PreviewDeviceContext = PreviewDeviceContext()
    private var frameTime: PreviewFrameTime = PreviewFrameTime()
    private var animation: PreviewAnimationContext? = null
    private val slotTables = mutableListOf<Any>()
    private var parameterInformationCollected: Boolean = false

    fun device(device: PreviewDeviceContext): Builder = apply { this.device = device }

    fun deviceFromRenderPixels(
      device: String?,
      widthPx: Int,
      heightPx: Int,
      density: Float,
    ): Builder = apply {
      this.device = PreviewDeviceContext.fromRenderPixels(device, widthPx, heightPx, density)
    }

    fun frameTime(frameTime: PreviewFrameTime): Builder = apply { this.frameTime = frameTime }

    fun animation(animation: PreviewAnimationContext?): Builder = apply {
      this.animation = animation
    }

    fun addSlotTables(tables: Iterable<Any>): Builder = apply { slotTables.addAll(tables) }

    fun parameterInformationCollected(): Builder = apply { parameterInformationCollected = true }

    fun build(): PreviewContext =
      PreviewContext(
        previewId = previewId,
        backend = backend,
        renderMode = renderMode,
        outputBaseName = outputBaseName,
        device = device,
        frameTime = frameTime,
        inspection =
          PreviewInspectionContext(
            slotTables = slotTables.toList(),
            parameterInformationCollected = parameterInformationCollected,
          ),
        animation = animation,
      )
  }
}

/**
 * Device and size metadata resolved for a preview render.
 *
 * [device] is the raw `@Preview(device = ...)` string when known. [widthDp], [heightDp], and
 * [density] describe the effective rendered surface. [resolvedDevice] carries catalog metadata such
 * as roundness; dimensions may differ from [resolvedDevice] when runtime overrides change the
 * render size.
 */
data class PreviewDeviceContext(
  val device: String? = null,
  val widthDp: Double? = null,
  val heightDp: Double? = null,
  val density: Float? = null,
  val resolvedDevice: DeviceDimensions.DeviceSpec? = null,
) {
  val isRound: Boolean
    get() = resolvedDevice?.isRound == true

  companion object {
    fun fromPreviewInfo(info: PreviewInfoDto): PreviewDeviceContext = fromPreviewParams(info.params)

    fun fromPreviewParams(params: PreviewParamsDto?): PreviewDeviceContext {
      val device = params?.device?.takeIf { it.isNotBlank() }
      val resolvedDevice = device?.let(DeviceDimensions::resolve)
      return PreviewDeviceContext(
        device = device,
        widthDp = params?.widthDp?.toDouble() ?: resolvedDevice?.widthDp?.toDouble(),
        heightDp = params?.heightDp?.toDouble() ?: resolvedDevice?.heightDp?.toDouble(),
        density = params?.density ?: resolvedDevice?.density,
        resolvedDevice = resolvedDevice,
      )
    }

    fun fromRenderPixels(
      device: String?,
      widthPx: Int,
      heightPx: Int,
      density: Float,
    ): PreviewDeviceContext {
      val safeDensity = density.takeIf { it > 0f }
      val resolvedDevice = device?.takeIf { it.isNotBlank() }?.let(DeviceDimensions::resolve)
      return PreviewDeviceContext(
        device = device?.takeIf { it.isNotBlank() },
        widthDp = safeDensity?.let { widthPx / it.toDouble() },
        heightDp = safeDensity?.let { heightPx / it.toDouble() },
        density = safeDensity,
        resolvedDevice = resolvedDevice,
      )
    }
  }
}

/**
 * Frame-clock semantics for the data represented by a [PreviewContext].
 *
 * Static one-shot products normally read [Mode.INITIAL_SETTLED_FRAME]. Animated products that
 * sample every frame should publish one context per sampled frame, or attach a
 * [PreviewAnimationContext] describing the sampled window.
 */
data class PreviewFrameTime(
  val mode: Mode = Mode.INITIAL_SETTLED_FRAME,
  val virtualTimeMs: Long? = null,
  val frameIndex: Int? = null,
) {
  enum class Mode {
    /** One-shot render after the backend has allowed effects/measure to settle. */
    INITIAL_SETTLED_FRAME,
    /** Deterministic animation or recording frame driven by virtual time. */
    VIRTUAL_ANIMATION_FRAME,
    /** Held live scene driven by wall-clock frame time. */
    WALL_CLOCK_FRAME,
  }
}

/**
 * Inspection capability captured during composition.
 *
 * [slotTables] is intentionally `Any`: on Compose backends each entry is a `CompositionData`, but
 * core must not expose a Compose dependency. The capture wrapper must call Compose's parameter-info
 * collection before composing content when consumers need call-site values from the slot table.
 */
data class PreviewInspectionContext(
  val slotTables: List<Any> = emptyList(),
  val parameterInformationCollected: Boolean = false,
)

/**
 * Animation sampling contract for data products.
 *
 * Structural products, such as theme extraction, should treat slot-table values as the initial
 * settled composition unless they explicitly opt into frame-sampled contexts. Curve/animation
 * products own the clock drive: they attach to the captured slot tables, seek their inspection
 * clock to each [sampleTimesMs] entry, and publish sampled values without independently advancing
 * the render clock.
 */
data class PreviewAnimationContext(
  val showCurves: Boolean,
  val requestedDurationMs: Int,
  val effectiveDurationMs: Int?,
  val frameIntervalMs: Int,
  val sampleTimesMs: List<Long> = emptyList(),
)
