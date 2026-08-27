package ee.schimke.composeai.data.render

/**
 * Per-render context passed from a renderer backend to data products.
 *
 * Renderer objects stay opaque. Compose-backed renderers may attach slot-table roots (currently
 * `androidx.compose.runtime.tooling.CompositionData`) through [inspection]; data products that
 * understand that runtime can cast and inspect them, while standalone renderers, daemon adapters,
 * and non-Compose producers stay decoupled from Compose internals.
 */
public data class PreviewContext(
  val previewId: String?,
  val backend: String?,
  val renderMode: String?,
  val outputBaseName: String?,
  val device: PreviewDeviceContext = PreviewDeviceContext(),
  val frameTime: PreviewFrameTime = PreviewFrameTime(),
  val inspection: PreviewInspectionContext = PreviewInspectionContext(),
  val animation: PreviewAnimationContext? = null,
) {
  public class Builder(
    private val previewId: String?,
    private val backend: String?,
    private val renderMode: String?,
    private val outputBaseName: String?,
  ) {
    private var device: PreviewDeviceContext = PreviewDeviceContext()
    private var frameTime: PreviewFrameTime = PreviewFrameTime()
    private var animation: PreviewAnimationContext? = null
    private val slotTables = mutableListOf<Any>()
    private val inspectionValues = linkedMapOf<String, Any>()
    private var parameterInformationCollected: Boolean = false
    private var rootForTest: Any? = null

    public fun device(device: PreviewDeviceContext): Builder = apply { this.device = device }

    public fun deviceFromRenderPixels(
      device: String?,
      widthPx: Int,
      heightPx: Int,
      density: Float,
      resolvedDevice: PreviewDeviceSpec? = null,
    ): Builder = apply {
      this.device =
        PreviewDeviceContext.fromRenderPixels(device, widthPx, heightPx, density, resolvedDevice)
    }

    public fun frameTime(frameTime: PreviewFrameTime): Builder = apply {
      this.frameTime = frameTime
    }

    public fun animation(animation: PreviewAnimationContext?): Builder = apply {
      this.animation = animation
    }

    public fun addSlotTables(tables: Iterable<Any>): Builder = apply { slotTables.addAll(tables) }

    public fun rootForTest(root: Any?): Builder = apply { rootForTest = root }

    public fun putInspectionValue(key: String, value: Any): Builder = apply {
      inspectionValues[key] = value
    }

    public fun parameterInformationCollected(): Builder = apply {
      parameterInformationCollected = true
    }

    public fun build(): PreviewContext =
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
            rootForTest = rootForTest,
            values = inspectionValues.toMap(),
            parameterInformationCollected = parameterInformationCollected,
          ),
        animation = animation,
      )
  }
}

public object PreviewBackends {
  public const val DESKTOP: String = "desktop"
  public const val ANDROID: String = "android"
}

/**
 * Device and size metadata resolved for a preview render.
 *
 * [device] is the raw `@Preview(device = ...)` string when known. [widthDp], [heightDp], and
 * [density] describe the effective rendered surface. [resolvedDevice] carries catalog metadata such
 * as roundness; dimensions may differ from [resolvedDevice] when runtime overrides change the
 * render size.
 */
public data class PreviewDeviceContext(
  val device: String? = null,
  val widthDp: Double? = null,
  val heightDp: Double? = null,
  val density: Float? = null,
  val resolvedDevice: PreviewDeviceSpec? = null,
) {
  val isRound: Boolean
    get() = resolvedDevice?.isRound == true

  public companion object {
    public fun fromRenderPixels(
      device: String?,
      widthPx: Int,
      heightPx: Int,
      density: Float,
      resolvedDevice: PreviewDeviceSpec? = null,
    ): PreviewDeviceContext {
      val safeDensity = density.takeIf { it > 0f }
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

public data class PreviewDeviceSpec(
  val widthDp: Int,
  val heightDp: Int,
  val density: Float,
  val isRound: Boolean = false,
)

/**
 * Frame-clock semantics for the data represented by a [PreviewContext].
 *
 * Static one-shot products normally read [Mode.INITIAL_SETTLED_FRAME]. Animated products that
 * sample every frame should publish one context per sampled frame, or attach a
 * [PreviewAnimationContext] describing the sampled window.
 */
public data class PreviewFrameTime(
  val mode: Mode = Mode.INITIAL_SETTLED_FRAME,
  val virtualTimeMs: Long? = null,
  val frameIndex: Int? = null,
) {
  public enum class Mode {
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
 * this module must not expose a Compose dependency. The capture wrapper must call Compose's
 * parameter-info collection before composing content when consumers need call-site values from the
 * slot table.
 *
 * [rootForTest] is likewise opaque: on Android Compose it is a `RootForTest`, which lets
 * Compose-aware data products inspect the same LayoutNode/semantics tree used by tooling without
 * adding a Compose UI dependency to the core render model.
 */
public data class PreviewInspectionContext(
  val slotTables: List<Any> = emptyList(),
  val rootForTest: Any? = null,
  val values: Map<String, Any> = emptyMap(),
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
public data class PreviewAnimationContext(
  val showCurves: Boolean,
  val requestedDurationMs: Int,
  val effectiveDurationMs: Int?,
  val frameIntervalMs: Int,
  val sampleTimesMs: List<Long> = emptyList(),
)
