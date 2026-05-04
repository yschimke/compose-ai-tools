package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataProductSink
import ee.schimke.composeai.data.render.extensions.DataProductStore
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore

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
  val extraction: ExtensionExtractionContext = ExtensionExtractionContext.Empty,
  val states: ExtensionStateRegistry = RecordingExtensionStateRegistry(),
  val products: DataProductStore = RecordingDataProductStore(),
) {
  val slotTables: ExtensionSlotTables
    get() = extraction.slotTables

  fun <T : Any> get(key: ExtensionContextKey<T>): T? = extraction.get(key)

  fun <T : Any> require(key: ExtensionContextKey<T>): T = extraction.require(key)

  fun <T : Any> exportState(key: ExtensionStateKey<T>, state: State<T>) {
    states.export(key, state)
  }

  fun <T : Any> state(key: ExtensionStateKey<T>): State<T>? = states.state(key)

  fun <T : Any> value(key: ExtensionStateKey<T>): T? = states.value(key)
}

data class ExtensionContextKey<T : Any>(val name: String, val type: Class<T>) {
  init {
    require(name.isNotBlank()) { "Extension context key name must not be blank." }
  }
}

data class ExtensionContextValue<T : Any>(val key: ExtensionContextKey<T>, val value: T)

infix fun <T : Any> ExtensionContextKey<T>.provides(value: T): ExtensionContextValue<T> =
  ExtensionContextValue(this, value)

class ExtensionContextData
private constructor(private val values: Map<ExtensionContextKey<*>, Any>) {
  fun <T : Any> get(key: ExtensionContextKey<T>): T? {
    val value = values[key] ?: return null
    return key.type.cast(value)
  }

  fun <T : Any> require(key: ExtensionContextKey<T>): T =
    get(key) ?: error("Extension context value '${key.name}' is not available.")

  fun contains(key: ExtensionContextKey<*>): Boolean = key in values

  companion object {
    val Empty: ExtensionContextData = ExtensionContextData(emptyMap())

    fun of(vararg values: ExtensionContextValue<*>): ExtensionContextData =
      ExtensionContextData(values.associate { it.key to it.value })
  }
}

fun interface ExtensionSlotTables {
  fun snapshot(): List<CompositionData>

  companion object {
    val Empty: ExtensionSlotTables = ExtensionSlotTables { emptyList() }

    fun of(tables: List<CompositionData>): ExtensionSlotTables = ExtensionSlotTables {
      tables.toList()
    }
  }
}

data class ExtensionExtractionContext(
  val slotTables: ExtensionSlotTables = ExtensionSlotTables.Empty,
  val data: ExtensionContextData = ExtensionContextData.Empty,
) {
  fun <T : Any> get(key: ExtensionContextKey<T>): T? = data.get(key)

  fun <T : Any> require(key: ExtensionContextKey<T>): T = data.require(key)

  companion object {
    val Empty: ExtensionExtractionContext = ExtensionExtractionContext()
  }
}

object CommonExtensionContextKeys {
  val RenderPreviewContext: ExtensionContextKey<PreviewContext> =
    ExtensionContextKey("preview-context", PreviewContext::class.java)
}

data class ExtensionStateKey<T : Any>(
  val owner: DataExtensionId,
  val name: String,
  val type: Class<T>,
) {
  init {
    require(name.isNotBlank()) { "Extension state key name must not be blank." }
  }
}

interface ExtensionStateRegistry {
  fun <T : Any> export(key: ExtensionStateKey<T>, state: State<T>)

  fun <T : Any> state(key: ExtensionStateKey<T>): State<T>?

  fun <T : Any> value(key: ExtensionStateKey<T>): T? = state(key)?.value

  fun <T : Any> requireState(key: ExtensionStateKey<T>): State<T> =
    state(key) ?: error("Extension state '${key.owner.value}/${key.name}' is not available.")

  fun <T : Any> requireValue(key: ExtensionStateKey<T>): T = requireState(key).value
}

class RecordingExtensionStateRegistry : ExtensionStateRegistry {
  private val values: MutableMap<ExtensionStateKey<*>, State<*>> = linkedMapOf()

  override fun <T : Any> export(key: ExtensionStateKey<T>, state: State<T>) {
    key.type.cast(state.value)
    values[key] = state
  }

  override fun <T : Any> state(key: ExtensionStateKey<T>): State<T>? {
    val state = values[key] ?: return null
    key.type.cast(state.value)
    @Suppress("UNCHECKED_CAST")
    return state as State<T>
  }
}

class StaticExtensionState<T : Any>(override val value: T) : State<T>

fun <T : Any> staticExtensionState(value: T): State<T> = StaticExtensionState(value)

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
    Extract(sink, context.products)
  }

  @Composable abstract fun Extract(sink: ExtensionCompositionSink, products: DataProductSink)
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
    Observe(sink, context.products)
  }

  @Composable abstract fun Observe(sink: ExtensionCompositionSink, products: DataProductSink)
}

data class ExtensionFrameContext(
  val extensionId: DataExtensionId,
  val previewId: String?,
  val renderMode: String?,
  val attributes: Map<String, Any?> = emptyMap(),
  val extraction: ExtensionExtractionContext = ExtensionExtractionContext.Empty,
  val states: ExtensionStateRegistry = RecordingExtensionStateRegistry(),
  val products: DataProductStore = RecordingDataProductStore(),
) {
  fun <T : Any> exportState(key: ExtensionStateKey<T>, state: State<T>) {
    states.export(key, state)
  }

  fun <T : Any> state(key: ExtensionStateKey<T>): State<T>? = states.state(key)

  fun <T : Any> value(key: ExtensionStateKey<T>): T? = states.value(key)
}

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
    extraction: ExtensionExtractionContext = ExtensionExtractionContext.Empty,
    states: ExtensionStateRegistry? = null,
    products: DataProductStore? = null,
    content: @Composable () -> Unit,
  ) {
    val extensionStates = states ?: remember { RecordingExtensionStateRegistry() }
    val extensionProducts = products ?: remember { RecordingDataProductStore() }
    Observe(
      extensions = extensions,
      previewId = previewId,
      renderMode = renderMode,
      sink = sink,
      attributes = attributes,
      extraction = extraction,
      states = extensionStates,
      products = extensionProducts,
    )
    Extract(
      extensions = extensions,
      previewId = previewId,
      renderMode = renderMode,
      sink = sink,
      attributes = attributes,
      extraction = extraction,
      states = extensionStates,
      products = extensionProducts,
    )
    Around(
      hooks = extensions.filterIsInstance<AroundComposableHook>(),
      index = 0,
      previewId = previewId,
      renderMode = renderMode,
      attributes = attributes,
      extraction = extraction,
      states = extensionStates,
      products = extensionProducts,
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
    extraction: ExtensionExtractionContext = ExtensionExtractionContext.Empty,
    states: ExtensionStateRegistry? = null,
    products: DataProductStore? = null,
  ) {
    val extensionStates = states ?: remember { RecordingExtensionStateRegistry() }
    val extensionProducts = products ?: remember { RecordingDataProductStore() }
    for (hook in extensions.filterIsInstance<ComposableExtractorHook>()) {
      hook.Extract(
        context =
          ExtensionComposeContext(
            extensionId = hook.id,
            previewId = previewId,
            renderMode = renderMode,
            attributes = attributes,
            extraction = extraction,
            states = extensionStates,
            products = extensionProducts.scopedFor(hook),
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
    extraction: ExtensionExtractionContext = ExtensionExtractionContext.Empty,
    states: ExtensionStateRegistry? = null,
    products: DataProductStore? = null,
  ) {
    val extensionStates = states ?: remember { RecordingExtensionStateRegistry() }
    val extensionProducts = products ?: remember { RecordingDataProductStore() }
    for (hook in extensions.filterIsInstance<CompositionObserverHook>()) {
      hook.Observe(
        context =
          ExtensionComposeContext(
            extensionId = hook.id,
            previewId = previewId,
            renderMode = renderMode,
            attributes = attributes,
            extraction = extraction,
            states = extensionStates,
            products = extensionProducts.scopedFor(hook),
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
    extraction: ExtensionExtractionContext,
    states: ExtensionStateRegistry,
    products: DataProductStore,
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
          extraction = extraction,
          states = states,
          products = products.scopedFor(hook),
        )
    ) {
      Around(
        hooks = hooks,
        index = index + 1,
        previewId = previewId,
        renderMode = renderMode,
        attributes = attributes,
        extraction = extraction,
        states = states,
        products = products,
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
