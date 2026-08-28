package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WebEmbed
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeBundleTest {

  private val previews =
    listOf(
      ServePreview("com.example.A", "Alpha"),
      ServePreview("com.example.B", "Beta"),
      ServePreview("com.example.C", "Gamma"),
    )

  /** A render lambda that returns deterministic per-id bytes, or null for ids in [fail]. */
  private fun renderer(fail: Set<String> = emptySet()): (ServePreview) -> ByteArray? = { p ->
    if (p.id in fail) null else "png-${p.id}".toByteArray()
  }

  @Test
  fun `external bundle has gallery plus one png per rendered preview`() {
    val built = ServeBundle.build(previews, "Demo", ":demo", render = renderer())

    assertEquals(3, built.previewCount)
    assertEquals(3, built.renderedCount)
    assertTrue(built.failed.isEmpty())
    assertTrue(WebEmbed.INDEX_NAME in built.files)
    assertTrue(WebEmbed.SCRIPT_NAME in built.files)
    for (p in previews) {
      assertTrue("previews/${p.id}.png" in built.files, "missing png for ${p.id}")
    }
  }

  @Test
  fun `a failed render is recorded and its png omitted while others render`() {
    val built =
      ServeBundle.build(previews, "Demo", ":demo", render = renderer(fail = setOf("com.example.B")))

    assertEquals(2, built.renderedCount)
    assertEquals(listOf("com.example.B"), built.failed)
    assertFalse("previews/com.example.B.png" in built.files)
    assertTrue("previews/com.example.A.png" in built.files)
  }

  @Test
  fun `inline bundle bakes pngs into the script with no separate png files`() {
    val built = ServeBundle.build(previews, "Demo", ":demo", inline = true, render = renderer())

    assertTrue(WebEmbed.INDEX_NAME in built.files)
    assertTrue(
      built.files.keys.none { it.startsWith("previews/") },
      "inline must not emit png files",
    )
  }

  @Test
  fun `zip is reproducible and contains the gallery entries`() {
    val files = ServeBundle.build(previews, "Demo", ":demo", render = renderer()).files

    val zipA = ServeBundle.zip(files)
    val zipB = ServeBundle.zip(files)
    assertContentEquals(zipA, zipB, "same files must produce byte-identical zips")

    val names = mutableListOf<String>()
    ZipInputStream(ByteArrayInputStream(zipA)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        names += entry.name
        zin.closeEntry()
      }
    }
    assertTrue(WebEmbed.INDEX_NAME in names)
    assertTrue(names.any { it.startsWith("previews/") })
  }

  @Test
  fun `writeDir lays out the gallery and nested previews on disk`() {
    val files = ServeBundle.build(previews, "Demo", ":demo", render = renderer()).files
    val dir =
      java.nio.file.Files.createTempDirectory("serve-bundle").toFile().also { it.deleteOnExit() }

    ServeBundle.writeDir(files, dir)

    assertTrue(File(dir, WebEmbed.INDEX_NAME).isFile)
    assertTrue(File(dir, "previews/com.example.A.png").isFile)
  }
}
