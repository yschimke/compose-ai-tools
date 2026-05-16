package ee.schimke.composeai.viewer

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit coverage for [loadBundle] — exercises the bundle-on-disk happy path, the schema mirror's
 * `kind`-discriminated polymorphic deserialisation, and per-preview error reporting when a declared
 * preview class isn't in `classes/app.jar`. UI behaviour (`Window`, drop target, live composition)
 * is harder to cover without a display server and is exercised at the integration level via the
 * headless xvfb smoke shown in the PR description.
 */
class BundleLoaderTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  @Test
  fun `loadBundle parses a minimal png-zip polyglot bundle`() {
    val bundle = writeMinimalBundle(includeAppClass = false)

    val loaded = loadBundle(bundle)
    try {
      assertThat(loaded.bundleManifest.schemaVersion).isEqualTo(1)
      assertThat(loaded.bundleManifest.backend).isEqualTo("desktop")
      assertThat(loaded.bundleManifest.previewIds).containsExactly("test.PreviewsKt.MissingPreview")
      assertThat(loaded.bundleManifest.classpath).hasSize(2)
      assertThat(loaded.bundleManifest.classpath[0]).isInstanceOf(ClasspathEntry.Module::class.java)
      val maven = loaded.bundleManifest.classpath[1] as ClasspathEntry.Maven
      assertThat(maven.group).isEqualTo("androidx.compose.ui")
      assertThat(maven.type).isEqualTo("jar")

      assertThat(loaded.previewManifest.previews).hasSize(1)
      assertThat(loaded.coverPreview.info.id).isEqualTo("test.PreviewsKt.MissingPreview")
      // Class is absent in the bundle so resolution must surface an error rather than crash.
      assertThat(loaded.coverPreview.composableMethod).isNull()
      assertThat(loaded.coverPreview.errorMessage).contains("test.PreviewsKt")
    } finally {
      loaded.close()
    }
  }

  @Test
  fun `loadBundle rejects non-bundle files`() {
    val txt = tempDir.newFile("not-a-bundle.txt").apply { writeText("hello world") }

    val ex =
      runCatching { loadBundle(txt) }.exceptionOrNull()
        ?: error("expected loadBundle to throw on non-bundle input")
    assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Build a tiny but well-formed PNG+ZIP polyglot. The PNG is just a sanity 1×1 cover; the zip
   * carries the manifests + an empty `classes/app.jar` so resolution exercises the
   * "class-not-found" branch deterministically.
   */
  private fun writeMinimalBundle(includeAppClass: Boolean): File {
    val zipBytes = ByteArrayOutputStream()
    ZipOutputStream(zipBytes).use { zip ->
      val bundleJson =
        Json.encodeToString(
          BundleManifest.serializer(),
          BundleManifest(
            schemaVersion = 1,
            backend = "desktop",
            previewIds = listOf("test.PreviewsKt.MissingPreview"),
            coverPreviewId = "test.PreviewsKt.MissingPreview",
            classpath =
              listOf(
                ClasspathEntry.Module(path = "classes/app.jar"),
                ClasspathEntry.Maven(
                  group = "androidx.compose.ui",
                  artifact = "ui-desktop",
                  version = "1.10.3",
                  type = "jar",
                ),
              ),
            modulePath = ":test",
            producedBy = "test-suite",
          ),
        )
      zip.putNextEntry(ZipEntry("bundle.json"))
      zip.write(bundleJson.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      val previewsJson =
        Json.encodeToString(
          PreviewManifest.serializer(),
          PreviewManifest(
            module = "test",
            variant = "desktop",
            previews =
              listOf(
                PreviewInfo(
                  id = "test.PreviewsKt.MissingPreview",
                  functionName = "MissingPreview",
                  className = "test.PreviewsKt",
                )
              ),
          ),
        )
      zip.putNextEntry(ZipEntry("previews.json"))
      zip.write(previewsJson.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      // Empty jar — just enough to satisfy the "classes/app.jar must be present" precondition.
      val emptyJarBytes = ByteArrayOutputStream()
      ZipOutputStream(emptyJarBytes).use { /* no entries */ }
      zip.putNextEntry(ZipEntry("classes/app.jar"))
      zip.write(emptyJarBytes.toByteArray())
      zip.closeEntry()
    }

    // Synthesise a 1×1 PNG header so the polyglot is recognised by `extractZipBytes`. Same byte
    // sequence the plugin's stub gray cover would produce — see PreviewBundleFormat.kt.
    val pngHeader = stubPng1x1()
    val out = tempDir.newFile("bundle.png")
    out.outputStream().use { sink ->
      sink.write(pngHeader)
      sink.write(zipBytes.toByteArray())
    }
    return out
  }

  /**
   * Minimal valid 1×1 PNG. Hand-rolled (CRCs included) so the test doesn't need `BufferedImage` /
   * `ImageIO` plumbing — the file just needs the PNG signature + a valid IHDR chain so the polyglot
   * extractor can locate the IEND boundary.
   */
  private fun stubPng1x1(): ByteArray {
    // Pre-computed by running `BufferedImage(1,1).also { it.setRGB(0,0,0x808080) }` through
    // `ImageIO.write(_, "png", baos)` and copying the bytes. Stable across Java releases.
    return byteArrayOf(
      -119,
      80,
      78,
      71,
      13,
      10,
      26,
      10,
      0,
      0,
      0,
      13,
      73,
      72,
      68,
      82,
      0,
      0,
      0,
      1,
      0,
      0,
      0,
      1,
      8,
      2,
      0,
      0,
      0,
      -112,
      119,
      83,
      -34,
      0,
      0,
      0,
      12,
      73,
      68,
      65,
      84,
      8,
      -41,
      99,
      -8,
      -65,
      -65,
      63,
      0,
      5,
      -2,
      2,
      -2,
      -86,
      -54,
      -3,
      -103,
      0,
      0,
      0,
      0,
      73,
      69,
      78,
      68,
      -82,
      66,
      96,
      -126,
    )
  }
}
