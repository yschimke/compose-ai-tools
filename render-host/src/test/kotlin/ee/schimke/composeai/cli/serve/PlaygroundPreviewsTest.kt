package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewManifest
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * The synthesized `previews.json` a bundle-less playground daemon renders against. What matters
 * here is coverage: the daemon can only stream a preview the manifest names, so a snippet that
 * compiled several must see all of them listed — otherwise the extras are compiled and then
 * unreachable, which is exactly the single-preview limit this manifest used to impose.
 */
class PlaygroundPreviewsTest {

  @Test
  fun `compiled preview dimensions survive manifest synthesis without executing the snippet`() {
    val directory = Files.createTempDirectory("playground-preview-dimensions").toFile()
    try {
      val annotation =
        directory.resolve("Preview.java").apply {
          writeText(
            """
            package androidx.compose.ui.tooling.preview;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface Preview { int widthDp() default -1; int heightDp() default -1; }
            """
              .trimIndent()
          )
        }
      val source =
        directory.resolve("Snippet.java").apply {
          writeText(
            """
            package sample;
            import androidx.compose.ui.tooling.preview.Preview;
            public class Snippet {
              static { if (true) throw new AssertionError("Do not execute preview code during discovery"); }
              @Preview(widthDp=360, heightDp=216) public static void Fixed() {}
              @Preview(widthDp=120) public static void WidthOnly() {}
              @Preview public static void Wrapped() {}
              @Preview(widthDp=800, heightDp=800) public static void NotSelected() {}
            }
            """
              .trimIndent()
          )
        }
      assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
          .run(
            null,
            null,
            null,
            "-d",
            directory.path,
            annotation.path,
            source.path,
          ),
      )
      val manifest =
        json.decodeFromString<PreviewManifest>(
          PlaygroundPreviews.previewManifestJson(
            snippet(
                listOf("sample.Snippet.Fixed", "sample.Snippet.WidthOnly", "sample.Snippet.Wrapped")
              )
              .copy(classesDir = directory.path.toPath())
          )
        )
      assertEquals(3, manifest.previews.size)
      assertEquals(360, manifest.previews[0].params.widthDp)
      assertEquals(216, manifest.previews[0].params.heightDp)
      assertEquals(120, manifest.previews[1].params.widthDp)
      assertNull(manifest.previews[1].params.heightDp)
      assertNull(manifest.previews[2].params.widthDp)
      assertNull(manifest.previews[2].params.heightDp)
    } finally {
      directory.deleteRecursively()
    }
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun snippet(previewIds: List<String>) =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.CMP,
      workDir = "/w/snippet".toPath(),
      classesDir = "/w/snippet/classes".toPath(),
      classpath = listOf("/w/snippet/classes".toPath()),
      moduleName = "playground-cmp",
      previewId = previewIds.first(),
      previewIds = previewIds,
    )

  private fun decode(previewIds: List<String>): PreviewManifest =
    json.decodeFromString(
      PreviewManifest.serializer(),
      PlaygroundPreviews.previewManifestJson(snippet(previewIds)),
    )

  @Test
  fun `every declared preview is listed, in declaration order`() {
    val manifest =
      decode(
        listOf(
          "com.example.SnippetKt.Alpha",
          "com.example.OtherKt.Beta",
          "com.example.OtherKt.Gamma",
        )
      )
    assertEquals(
      listOf(
        "com.example.SnippetKt.Alpha",
        "com.example.OtherKt.Beta",
        "com.example.OtherKt.Gamma",
      ),
      manifest.previews.map { it.id },
      "the live session can only navigate to previews the manifest names",
    )
    assertEquals("playground-cmp", manifest.module)
  }

  @Test
  fun `an id is split back into its class and function`() {
    val entry = decode(listOf("com.example.SnippetKt.Alpha")).previews.single()
    assertEquals("com.example.SnippetKt", entry.className)
    assertEquals("Alpha", entry.functionName)
  }

  @Test
  fun `a snippet that names only one preview still yields exactly one entry`() {
    // The default `previewIds = listOf(previewId)` keeps every pre-existing single-preview caller
    // producing the manifest it always did.
    val manifest =
      json.decodeFromString(
        PreviewManifest.serializer(),
        PlaygroundPreviews.previewManifestJson(
          PlaygroundTokenStore.PlaygroundSnippet(
            mode = PlaygroundMode.CMP,
            workDir = "/w/snippet".toPath(),
            classesDir = "/w/snippet/classes".toPath(),
            classpath = listOf("/w/snippet/classes".toPath()),
            moduleName = "playground-cmp",
            previewId = "com.example.SnippetKt.Only",
          )
        ),
      )
    assertEquals(listOf("com.example.SnippetKt.Only"), manifest.previews.map { it.id })
  }
}
