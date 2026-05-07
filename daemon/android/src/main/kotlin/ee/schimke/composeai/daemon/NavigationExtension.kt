package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Always-on post-capture extension that walks the held [`androidx.activity.ComponentActivity`]
 * and writes the `data/navigation` artefact for the rendered preview — see
 * [NavigationDataProducer] for the payload shape and Robolectric caveats.
 *
 * Pure post-capture: no Compose-side hook is needed because the activity reference is threaded
 * through [RenderDataArtifactContextKeys.HeldActivity]. Skips silently when the key isn't
 * populated (host with no `ComposeTestRule.activity` available — defensive, the production path
 * always populates it).
 *
 * Mirrors the [I18nTranslationsExtension] / [ComposeSemanticsExtension] shape.
 */
class NavigationExtension : PostCaptureProcessor {
  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val rootDir = context.require(RenderDataArtifactContextKeys.RootDir)
    val outputBaseName = context.require(RenderDataArtifactContextKeys.OutputBaseName)
    val activity = context.get(RenderDataArtifactContextKeys.HeldActivity) ?: return
    NavigationDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = outputBaseName,
      activity = activity,
    )
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(NavigationDataProducer.KIND)

    val factory: RenderDataArtifactExtensionFactory =
      RenderDataArtifactExtensionFactory { _ -> NavigationExtension() }
  }
}
