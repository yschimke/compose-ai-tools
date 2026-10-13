package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleReproducibilityTest {
  private fun jar(file: File, entries: List<Pair<String, String>>, timestamp: Long): File =
    file.apply {
      ZipOutputStream(outputStream()).use { zip ->
        entries.forEach { (name, value) ->
          zip.putNextEntry(ZipEntry(name).apply { time = timestamp })
          zip.write(value.toByteArray(Charsets.UTF_8))
          zip.closeEntry()
        }
      }
    }

  @Test
  fun `bundles and baked packaging are byte identical across roots timezone locale and entry order`() {
    val root = Files.createTempDirectory("bundle-reproducibility").toFile()
    val locale = Locale.getDefault()
    val zone = TimeZone.getDefault()
    try {
      val a = root.resolve("checkout-a").apply { mkdirs() }
      val b = root.resolve("checkout-b").apply { mkdirs() }
      val entries = listOf("App.class" to "compiled", "résumé.txt" to "资源")
      val appA = jar(a.resolve("app.jar"), entries, 0)
      val appB = jar(b.resolve("app.jar"), entries.reversed(), 1700000000000)
      val runtimeA = jar(a.resolve("local.jar"), listOf("message.txt" to "runtime"), 0)
      val runtimeB = b.resolve("local.jar").apply { writeBytes(runtimeA.readBytes()) }
      val preview =
        PreviewInfo(
          "example.App.Greeting",
          "Greeting",
          "example.App",
          sourceFile = "src/App.kt",
          captures = listOf(Capture(renderOutput = "greeting.png")),
        )
      val manifest = PreviewManifest("//:app", "desktop", listOf(preview))
      val first = a.resolve("bundle.png")
      val second = b.resolve("bundle.png")
      Locale.setDefault(Locale.US)
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
      writeBazelBundle(
        "//:app",
        listOf(appA),
        listOf(appA, runtimeA),
        manifest,
        null,
        setOf(preview.id),
        emptyList(),
        first,
      )
      Locale.setDefault(Locale.FRANCE)
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
      writeBazelBundle(
        "//:app",
        listOf(appB),
        listOf(appB, runtimeB),
        manifest,
        null,
        setOf(preview.id),
        emptyList(),
        second,
      )
      assertEquals(bazelBundleSha(first), bazelBundleSha(second))
      val png =
        java.util.Base64.getDecoder()
          .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
          )
      val rendersA = a.resolve("renders").apply { mkdirs() }
      val rendersB = b.resolve("renders").apply { mkdirs() }
      rendersA.resolve(previewPngName(preview.id)).writeBytes(png)
      rendersB.resolve(previewPngName(preview.id)).writeBytes(png)
      val bakedA = a.resolve("baked.png")
      val bakedB = b.resolve("baked.png")
      bakePreparedBundle(first, rendersA, bakedA)
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      bakePreparedBundle(second, rendersB, bakedB)
      assertTrue(bakedA.readBytes().contentEquals(bakedB.readBytes()))
    } finally {
      Locale.setDefault(locale)
      TimeZone.setDefault(zone)
      root.deleteRecursively()
    }
  }
}
