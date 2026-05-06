package ee.schimke.composeai.daemon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalFontFamilyResolver
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
import java.io.File

/**
 * Always-on data extension that records every font resolution made during composition and writes
 * the `fonts/used` artefact after capture.
 *
 * Implements both [AroundComposableHook] (to install the recording [LocalFontFamilyResolver]) and
 * [PostCaptureProcessor] (to write the JSON artefact once the bitmap is captured) so the recorder
 * lifecycle is owned end-to-end by this extension. The render engine no longer constructs
 * `FontResolverRecorder` directly or threads its payload through `FontsUsedDataProducer
 * .writeArtifacts`.
 *
 * Constructed per render via [factory] so the recorder can take the per-render Android [Context]
 * for resource-name resolution.
 */
class FontsRecorderExtension(context: Context? = null) :
  AroundComposableHook, PostCaptureProcessor {
  private val recorder = FontResolverRecorder(context)

  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> =
    setOf(DataExtensionHookKind.AroundComposable, DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Instrumentation)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  @Composable
  override fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit) {
    val baseFontResolver = LocalFontFamilyResolver.current
    CompositionLocalProvider(
      LocalFontFamilyResolver provides recordingFontFamilyResolver(baseFontResolver, recorder),
      content = content,
    )
  }

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val previewId = context.get(RenderDataArtifactContextKeys.PreviewId) ?: outputBaseName
    FontsUsedDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      payload = recorder.payload(),
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(FontsUsedDataProducer.KIND)

    /** Factory wired into the render engine's data-artifact extension list. */
    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { context: Context -> FontsRecorderExtension(context) }
  }
}
