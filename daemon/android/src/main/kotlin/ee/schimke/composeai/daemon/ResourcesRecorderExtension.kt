package ee.schimke.composeai.daemon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
 * Implements both [AroundComposableHook] (installs a recording [LocalContext] over whatever context
 * is in scope) and [PostCaptureProcessor] (writes the JSON artefact once the bitmap is captured).
 * The recorder is constructed inside the extension; the render engine no longer touches
 * `ResourcesUsedDataProducer.recorder(...)` or `.writeArtifacts(...)` directly.
 *
 * **The recorder re-bases onto the composition's `LocalContext`, and that is load-bearing.** This
 * extension runs in the [DataExtensionPhase.Instrumentation] phase, i.e. *inside* every
 * [DataExtensionPhase.OuterEnvironment] wrap — and `PseudolocaleOverrideExtension` is one of those,
 * providing a `LocalContext` whose `Resources` pseudolocalises `getText`. Recording from the
 * activity captured at build time and re-providing *that* as `LocalContext` would shadow the outer
 * wrap for all preview content beneath: on the daemon lane a `localeTag=en-XA` render came back
 * with every string un-pseudolocalised even once the planner ran, because the recorder handed the
 * preview the raw activity's `Resources` back (#4371). Delegating to the context in scope keeps the
 * stack composable — outer wrappers stay in force and their output is what gets recorded, which is
 * also the truthful answer for `resources/used`: it reports the strings the render actually drew.
 */
class ResourcesRecorderExtension(private val baseContext: Context) :
  AroundComposableHook, PostCaptureProcessor {
  /**
   * Re-created by [Around] over the composition's `LocalContext`; the build-time value covers a
   * render whose composition never ran (nothing recorded, empty artefact) and `@Volatile` because
   * [process] reads it from the capture thread.
   */
  @Volatile
  private var recorder: RecordingResources = ResourcesUsedDataProducer.recorder(baseContext)

  override val id: DataExtensionId = ID
  override val hooks: Set<DataExtensionHookKind> =
    setOf(DataExtensionHookKind.AroundComposable, DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Instrumentation)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  @Composable
  override fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit) {
    val outer = LocalContext.current
    val recordingContext =
      remember(outer) {
        ResourcesUsedDataProducer.recorder(outer).let {
          recorder = it
          ResourcesUsedDataProducer.context(outer, it)
        }
      }
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
      RenderDataArtifactExtensionFactory { context ->
        ResourcesRecorderExtension(context)
      }
  }
}
