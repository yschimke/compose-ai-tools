package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composable
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension

/**
 * Compose-facing data-extension hook surface.
 *
 * Design rule: when an extension needs reflection or Compose runtime internals, keep that access
 * behind a small facade owned by the extension and expose the normal path as a simple composable
 * API. Typical extensions should look like regular Compose wrappers or extractors: read
 * CompositionLocals, install effects, and emit values through [ExtensionCompositionSink].
 */
data class ExtensionComposeContext(
  val extensionId: DataExtensionId,
  val previewId: String?,
  val renderMode: String?,
  val attributes: Map<String, Any?> = emptyMap(),
)

interface ExtensionCompositionSink {
  fun put(extensionId: DataExtensionId, key: String, value: Any?)
}

class RecordingExtensionCompositionSink : ExtensionCompositionSink {
  private val valuesByExtension: MutableMap<DataExtensionId, MutableMap<String, Any?>> =
    linkedMapOf()

  override fun put(extensionId: DataExtensionId, key: String, value: Any?) {
    valuesByExtension.getOrPut(extensionId, ::linkedMapOf)[key] = value
  }

  fun values(extensionId: DataExtensionId): Map<String, Any?> =
    valuesByExtension[extensionId]?.toMap() ?: emptyMap()

  fun values(): Map<DataExtensionId, Map<String, Any?>> =
    valuesByExtension.mapValues { (_, values) ->
      values.toMap()
    }
}

interface AroundComposableHook : PlannedDataExtension {
  @Composable fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit)
}

/**
 * Convenience base for the common case where an extension is just a composable wrapper.
 *
 * Use this for extensions like device background or theme overrides: the extension metadata
 * declares that it participates as [DataExtensionHookKind.AroundComposable], and the implementation
 * stays shaped like ordinary Compose code.
 *
 * ```kotlin
 * class DeviceBackgroundExtension(
 *   private val background: Color,
 * ) : AroundComposableExtension(DataExtensionId("render-device-background")) {
 *   @Composable
 *   override fun AroundComposable(content: @Composable () -> Unit) {
 *     Box(Modifier.background(background)) {
 *       content()
 *     }
 *   }
 * }
 * ```
 */
abstract class AroundComposableExtension(
  override val id: DataExtensionId,
  override val constraints: DataExtensionConstraints = DataExtensionConstraints(),
) : AroundComposableHook {
  final override val hooks: Set<DataExtensionHookKind> =
    setOf(DataExtensionHookKind.AroundComposable)

  @Composable
  final override fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit) {
    AroundComposable(content)
  }

  @Composable abstract fun AroundComposable(content: @Composable () -> Unit)
}

interface ComposableExtractorHook : PlannedDataExtension {
  @Composable fun Extract(context: ExtensionComposeContext, sink: ExtensionCompositionSink)
}

/**
 * Convenience base for extensions that read Compose state and emit data.
 *
 * Use this for extensions like theme capture or CompositionLocal-backed metadata extraction. The
 * implementation can stay focused on normal Compose reads and sink writes; reflection, when needed,
 * should remain behind the extension's own typed facade.
 */
abstract class ComposableExtractorExtension(
  override val id: DataExtensionId,
  override val constraints: DataExtensionConstraints = DataExtensionConstraints(),
) : ComposableExtractorHook {
  final override val hooks: Set<DataExtensionHookKind> =
    setOf(DataExtensionHookKind.ComposableExtractor)

  @Composable
  final override fun Extract(context: ExtensionComposeContext, sink: ExtensionCompositionSink) {
    Extract(sink)
  }

  @Composable abstract fun Extract(sink: ExtensionCompositionSink)
}

interface CompositionObserverHook : PlannedDataExtension {
  @Composable fun Observe(context: ExtensionComposeContext, sink: ExtensionCompositionSink)
}

/**
 * Convenience base for extensions that install Compose effects or observers.
 *
 * Use this for extensions like recomposition observation where the extension participates in the
 * composition lifecycle but does not wrap user content.
 */
abstract class CompositionObserverExtension(
  override val id: DataExtensionId,
  override val constraints: DataExtensionConstraints = DataExtensionConstraints(),
) : CompositionObserverHook {
  final override val hooks: Set<DataExtensionHookKind> =
    setOf(DataExtensionHookKind.CompositionObserver)

  @Composable
  final override fun Observe(context: ExtensionComposeContext, sink: ExtensionCompositionSink) {
    Observe(sink)
  }

  @Composable abstract fun Observe(sink: ExtensionCompositionSink)
}

data class ExtensionFrameContext(
  val extensionId: DataExtensionId,
  val previewId: String?,
  val renderMode: String?,
  val attributes: Map<String, Any?> = emptyMap(),
)

data class ExtensionFrame(
  val index: Int,
  val fraction: Float,
  val timeMillis: Long,
  val delayMillis: Int,
) {
  init {
    require(index >= 0) { "Frame index must be non-negative." }
    require(fraction in 0f..1f) { "Frame fraction must be in the 0..1 range." }
    require(timeMillis >= 0L) { "Frame time must be non-negative." }
    require(delayMillis >= 0) { "Frame delay must be non-negative." }
  }
}

fun interface ExtensionFrameCurve {
  fun transform(fraction: Float): Float

  companion object {
    val Linear: ExtensionFrameCurve = ExtensionFrameCurve { fraction -> fraction }
  }
}

data class ExtensionFrameSequence(
  val frames: List<ExtensionFrame>,
  val curve: ExtensionFrameCurve = ExtensionFrameCurve.Linear,
  val extras: Map<String, Any?> = emptyMap(),
)

interface FrameDriverHook : PlannedDataExtension {
  fun frames(context: ExtensionFrameContext): ExtensionFrameSequence
}

/**
 * Convenience base for extensions that own a normalized multi-frame walk.
 *
 * Use this for live scroll or animation capture: one hook plans frame fractions from `0..1`, with
 * optional extras describing the curve or discovered animation metadata. The renderer decides how
 * to render those frames efficiently.
 */
abstract class NormalizedFrameDriverExtension(
  override val id: DataExtensionId,
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(
      phase = DataExtensionPhase.Scenario,
      lifecycle = DataExtensionLifecycle.MultiFrame,
    ),
) : FrameDriverHook {
  final override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.ScenarioDriver)
}

data class ImageFrameTransformInput<ImageT>(
  val image: ImageT,
  val frame: ExtensionFrame,
  val sequence: ExtensionFrameSequence,
  val extras: Map<String, Any?> = emptyMap(),
)

interface ImageFrameTransformHook<ImageT> : PlannedDataExtension {
  fun transform(input: ImageFrameTransformInput<ImageT>): ImageT
}

/**
 * Convenience base for efficient per-frame image overlays.
 *
 * A concrete integration can bind [ImageT] to `ImageBitmap`, `BufferedImage`, Skia `Image`, or a
 * platform-native image. Extension code receives the captured frame plus typed frame metadata and
 * returns the transformed image without re-entering the Compose pipeline.
 */
abstract class ImageFrameTransformExtension<ImageT>(
  override val id: DataExtensionId,
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.PostProcess),
) : ImageFrameTransformHook<ImageT> {
  final override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
}

object ComposeDataExtensionPipeline {
  @Composable
  fun Apply(
    extensions: List<PlannedDataExtension>,
    previewId: String?,
    renderMode: String?,
    sink: ExtensionCompositionSink,
    attributes: Map<String, Any?> = emptyMap(),
    content: @Composable () -> Unit,
  ) {
    Observe(
      extensions = extensions,
      previewId = previewId,
      renderMode = renderMode,
      sink = sink,
      attributes = attributes,
    )
    Extract(
      extensions = extensions,
      previewId = previewId,
      renderMode = renderMode,
      sink = sink,
      attributes = attributes,
    )
    Around(
      hooks = extensions.filterIsInstance<AroundComposableHook>(),
      index = 0,
      previewId = previewId,
      renderMode = renderMode,
      attributes = attributes,
      content = content,
    )
  }

  @Composable
  fun Extract(
    extensions: List<PlannedDataExtension>,
    previewId: String?,
    renderMode: String?,
    sink: ExtensionCompositionSink,
    attributes: Map<String, Any?> = emptyMap(),
  ) {
    for (hook in extensions.filterIsInstance<ComposableExtractorHook>()) {
      hook.Extract(
        context =
          ExtensionComposeContext(
            extensionId = hook.id,
            previewId = previewId,
            renderMode = renderMode,
            attributes = attributes,
          ),
        sink = sink,
      )
    }
  }

  @Composable
  fun Observe(
    extensions: List<PlannedDataExtension>,
    previewId: String?,
    renderMode: String?,
    sink: ExtensionCompositionSink,
    attributes: Map<String, Any?> = emptyMap(),
  ) {
    for (hook in extensions.filterIsInstance<CompositionObserverHook>()) {
      hook.Observe(
        context =
          ExtensionComposeContext(
            extensionId = hook.id,
            previewId = previewId,
            renderMode = renderMode,
            attributes = attributes,
          ),
        sink = sink,
      )
    }
  }

  @Composable
  private fun Around(
    hooks: List<AroundComposableHook>,
    index: Int,
    previewId: String?,
    renderMode: String?,
    attributes: Map<String, Any?>,
    content: @Composable () -> Unit,
  ) {
    val hook = hooks.getOrNull(index)
    if (hook == null) {
      content()
      return
    }

    hook.Around(
      context =
        ExtensionComposeContext(
          extensionId = hook.id,
          previewId = previewId,
          renderMode = renderMode,
          attributes = attributes,
        )
    ) {
      Around(
        hooks = hooks,
        index = index + 1,
        previewId = previewId,
        renderMode = renderMode,
        attributes = attributes,
        content = content,
      )
    }
  }
}

val PlannedDataExtension.hasAroundComposableHook: Boolean
  get() = DataExtensionHookKind.AroundComposable in hooks && this is AroundComposableHook

val PlannedDataExtension.hasComposableExtractorHook: Boolean
  get() = DataExtensionHookKind.ComposableExtractor in hooks && this is ComposableExtractorHook

val PlannedDataExtension.hasCompositionObserverHook: Boolean
  get() = DataExtensionHookKind.CompositionObserver in hooks && this is CompositionObserverHook

val PlannedDataExtension.hasFrameDriverHook: Boolean
  get() = DataExtensionHookKind.ScenarioDriver in hooks && this is FrameDriverHook

val PlannedDataExtension.hasImageFrameTransformHook: Boolean
  get() = DataExtensionHookKind.AfterCapture in hooks && this is ImageFrameTransformHook<*>
