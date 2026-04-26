package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android XML resource renderer. Reads the `resources.json` written by `discoverAndroidResources`
 * (path via `composeai.resources.manifest`), iterates every [RenderResourceCapture], and writes a
 * PNG to the directory pointed at by `composeai.resources.outputDir`.
 *
 * Initial scope (this iteration): `RenderResourceType.VECTOR` only. `ANIMATED_VECTOR` and
 * `ADAPTIVE_ICON` captures are skipped with a single lifecycle-log line so the missing files don't
 * surprise downstream consumers — they land in subsequent commits (adaptive shape masks +
 * animated-vector GIF encoding).
 *
 * Robolectric setup mirrors [RobolectricRenderTest]'s pin: SDK 35, NATIVE graphics, paused looper,
 * hardware pixel-copy. The Gradle task wiring (`renderAndroidResources`) sets the same system
 * properties on this Test task as on `renderPreviews`, so both paths share Robolectric's runtime
 * configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResourcePreviewRenderTest {

  @Test
  fun renderResources() {
    val manifestPath =
      System.getProperty("composeai.resources.manifest")
        ?: error("composeai.resources.manifest system property not set")
    val outputDirPath =
      System.getProperty("composeai.resources.outputDir")
        ?: error("composeai.resources.outputDir system property not set")

    val manifest = json.decodeFromString<RenderResourceManifest>(File(manifestPath).readText())
    val outputRoot = File(outputDirPath)
    outputRoot.mkdirs()

    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val packageName = context.packageName

    var rendered = 0
    var skipped = 0
    var missing = 0
    for (preview in manifest.resources) {
      if (preview.type != RenderResourceType.VECTOR) {
        // ADAPTIVE_ICON + ANIMATED_VECTOR land in follow-up commits.
        skipped += preview.captures.size
        continue
      }
      val (resType, resName) = preview.id.split('/', limit = 2).let { it[0] to it[1] }
      val resId = context.resources.getIdentifier(resName, resType, packageName)
      if (resId == 0) {
        System.err.println(
          "compose-preview: resource ${preview.id} not found on the consumer's R class " +
            "(package=$packageName); skipping ${preview.captures.size} capture(s)"
        )
        missing += preview.captures.size
        continue
      }

      for (capture in preview.captures) {
        val qualifiers = capture.variant?.qualifiers
        if (!qualifiers.isNullOrEmpty()) {
          RuntimeEnvironment.setQualifiers(qualifiers)
        }
        val drawable: Drawable? = ContextCompat.getDrawable(context, resId)
        if (drawable == null) {
          System.err.println(
            "compose-preview: ContextCompat.getDrawable returned null for ${preview.id} " +
              "at qualifiers=${qualifiers ?: "<default>"}; skipping capture"
          )
          missing++
          continue
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
          val canvas = Canvas(bitmap)
          drawable.setBounds(0, 0, width, height)
          drawable.draw(canvas)
          val outFile = resolveOutputPath(outputRoot, capture.renderOutput)
          outFile.parentFile?.mkdirs()
          outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
          bitmap.recycle()
        }
        rendered++
      }
    }
    println(
      "compose-preview resource render: $rendered PNG(s), $skipped capture(s) skipped " +
        "(unsupported type), $missing capture(s) missing"
    )
  }

  /**
   * The manifest's `renderOutput` is module-relative (e.g. `renders/resources/drawable/foo.png`).
   * The Gradle task hands us `composeai.resources.outputDir` already pointing at the `renders/`
   * directory, so we strip the leading `renders/` segment and resolve under it.
   */
  private fun resolveOutputPath(outputRoot: File, renderOutput: String): File =
    File(outputRoot, renderOutput.removePrefix("renders/"))

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
