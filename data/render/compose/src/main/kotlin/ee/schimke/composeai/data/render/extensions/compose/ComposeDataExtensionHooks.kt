package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composable
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
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
