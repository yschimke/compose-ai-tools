package ee.schimke.composeai.daemon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext

/**
 * Always-on data extension that records every Android resource (`getString`, `getDrawable`, …)
 * resolved during composition and writes the `resources/used` artefact after capture.
 *
 * Implements both [AroundComposableHook] (installs a recording [LocalContext] over the activity)
 * and [PostCaptureProcessor] (writes the JSON artefact once the bitmap is captured). The recorder
 * is constructed inside the extension; the render engine no longer touches
 * `ResourcesUsedDataProducer.recorder(...)` or `.writeArtifacts(...)` directly.
 */
class ResourcesRecorderExtension(private val baseContext: Context) :
  AroundComposableHook, PostCaptureProcessor {
  private val recorder: RecordingResources = ResourcesUsedDataProducer.recorder(baseContext)
  private val recordingContext: Context =
    ResourcesUsedDataProducer.context(baseContext, recorder)

  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> =
    setOf(DataExtensionHookKind.AroundComposable, DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Instrumentation)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  @Composable
  override fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContext provides recordingContext, content = content)
  }

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    ResourcesUsedDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      recorder = recorder,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(ResourcesUsedDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { context -> ResourcesRecorderExtension(context) }
  }
}
