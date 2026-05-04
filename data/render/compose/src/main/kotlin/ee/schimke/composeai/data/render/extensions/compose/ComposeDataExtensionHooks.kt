package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composable
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension

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

interface CompositionObserverHook : PlannedDataExtension {
  @Composable fun Observe(context: ExtensionComposeContext, sink: ExtensionCompositionSink)
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

val PlannedDataExtension.hasCompositionObserverHook: Boolean
  get() = DataExtensionHookKind.CompositionObserver in hooks && this is CompositionObserverHook
