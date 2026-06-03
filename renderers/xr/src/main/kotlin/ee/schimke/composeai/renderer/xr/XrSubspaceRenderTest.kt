package ee.schimke.composeai.renderer.xr

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The render entry the plugin's `composePreviewRenderXr` `Test` task runs: one parameterised case
 * per `XR_SUBSPACE` preview in the manifest. Each composes the preview's `Subspace` under the fake
 * XR runtime (via [XrSubspaceRenderer]) and writes `scene.json` into a per-preview subdirectory of
 * the render output dir.
 *
 * Lives in `src/main` (shipped in the jar) like `:renderer-android`'s `RobolectricRenderTest`, so
 * the plugin can run it against the consumer's test classpath; it is NOT part of `:renderer-xr`'s
 * own unit-test run (those exercise the recorder/writer/reader directly). The Robolectric SDK comes
 * from the `robolectric.properties` the plugin generates; the manifest + output dir from the system
 * properties it sets (`composeai.render.manifest` / `composeai.render.outputDir`).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
public class XrSubspaceRenderTest(private val preview: XrManifestReader.XrPreview) {

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION")
  @get:Rule public val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  public fun renderScene() {
    // `Subspace` only takes its spatial path when the XR feature is present; the offline runtime
    // reports none, so shadow it on (mirrors the recorder tests).
    shadowOf(rule.activity.packageManager)
      .setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)

    val rendersDir =
      File(
        requireNotNull(System.getProperty(OUTPUT_DIR_PROP)) { "$OUTPUT_DIR_PROP system property not set" }
      )
    val outputDir = File(rendersDir, sanitize(preview.id)).apply { mkdirs() }
    XrSubspaceRenderer.render(
      rule = rule,
      className = preview.className,
      functionName = preview.functionName,
      previewId = preview.id,
      outputDir = outputDir,
    )
  }

  public companion object {
    private const val MANIFEST_PROP = "composeai.render.manifest"
    private const val OUTPUT_DIR_PROP = "composeai.render.outputDir"

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public fun previews(): List<XrManifestReader.XrPreview> {
      val manifest = System.getProperty(MANIFEST_PROP) ?: return emptyList()
      return XrManifestReader.xrPreviews(File(manifest))
    }

    /** Make a preview id safe to use as a directory name. */
    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
  }
}
