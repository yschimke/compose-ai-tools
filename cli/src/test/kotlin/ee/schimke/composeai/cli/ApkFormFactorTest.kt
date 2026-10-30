package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ApkFormFactorTest {

  /** One element of a manifest: its name and its `android:` attributes. */
  private data class Element(val name: String, val attributes: Map<String, Any>)

  @Test
  fun `a required watch feature makes a wear app`() {
    val signals =
      ManifestSignals.read(
        manifest(
          Element("manifest", emptyMap()),
          Element("uses-feature", mapOf("name" to "android.hardware.type.watch")),
        )
      )
    assertEquals(setOf("android.hardware.type.watch"), signals.requiredFeatures)
    assertEquals(ApkFormFactor.WEAR, ApkFormFactor.classify(signals, emptySequence()))
  }

  @Test
  fun `an optional watch feature does not`() {
    val signals =
      ManifestSignals.read(
        manifest(
          Element(
            "uses-feature",
            mapOf("name" to "android.hardware.type.watch", "required" to false),
          )
        )
      )
    assertEquals(setOf("android.hardware.type.watch"), signals.features)
    assertEquals(emptySet(), signals.requiredFeatures)
    assertEquals(ApkFormFactor.PHONE, ApkFormFactor.classify(signals, emptySequence()))
  }

  @Test
  fun `a projected activity makes a glimmer app, and an xr feature an xr app`() {
    val projected =
      ManifestSignals.read(
        manifest(
          Element(
            "activity",
            mapOf("name" to ".Glasses", "requiredDisplayCategory" to "xr_projected"),
          )
        )
      )
    assertEquals(ApkFormFactor.GLIMMER, ApkFormFactor.classify(projected, emptySequence()))

    val spatial =
      ManifestSignals.read(
        manifest(
          Element(
            "uses-feature",
            mapOf("name" to "android.software.xr.api.spatial", "required" to false),
          )
        )
      )
    assertEquals(ApkFormFactor.XR, ApkFormFactor.classify(spatial, emptySequence()))
  }

  @Test
  fun `code that uses glimmer or xr compose decides when the manifest does not`() {
    fun dex(type: String) = sequenceOf("dex\n035\u0000...$type...".toByteArray())
    val none = ManifestSignals()
    assertEquals(
      ApkFormFactor.GLIMMER,
      ApkFormFactor.classify(none, dex("Landroidx/xr/glimmer/Card;")),
    )
    assertEquals(
      ApkFormFactor.XR,
      ApkFormFactor.classify(none, dex("Landroidx/xr/compose/spatial/SubspaceKt;")),
    )
    assertEquals(
      ApkFormFactor.PHONE,
      ApkFormFactor.classify(none, dex("Landroidx/compose/ui/Modifier;")),
    )
  }

  @Test
  fun `detect reads the apk, and anything unreadable is a phone`() {
    val root = Files.createTempDirectory("form-factor").toFile()
    try {
      val apk = File(root, "wear.apk")
      ZipOutputStream(apk.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
        zip.write(manifest(Element("uses-feature", mapOf("name" to "android.hardware.type.watch"))))
        zip.closeEntry()
      }
      assertEquals(ApkFormFactor.WEAR, ApkFormFactor.detect(apk))
      assertEquals(ApkFormFactor.PHONE, ApkFormFactor.detect(File(root, "missing.apk")))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `form factors are named for the flag`() {
    assertEquals(ApkFormFactor.WEAR, ApkFormFactor.byId("Wear"))
    assertEquals(null, ApkFormFactor.byId("tv"))
    assertEquals("w227dp-h227dp-round-watch-xhdpi", ApkFormFactor.WEAR.qualifiers)
  }

  // -----------------------------------------------------------------------------------------------
  // A minimal binary XML writer: a UTF-16 string pool and one start element per [Element], the
  // shape aapt2 writes. Attribute names are matched by name, so no resource map is needed.

  private fun manifest(vararg elements: Element): ByteArray {
    val strings = mutableListOf<String>()
    fun index(value: String): Int =
      strings.indexOf(value).takeIf { it >= 0 }
        ?: strings.run {
          add(value)
          size - 1
        }
    val chunks = ByteArrayOutputStream()
    for (element in elements) {
      val attributes = element.attributes.entries.toList()
      val size = 16 + 20 + attributes.size * 20
      val chunk = buffer(size)
      chunk.putShort(0x0102.toShort()).putShort(16.toShort()).putInt(size).putInt(1).putInt(-1)
      chunk.putInt(-1).putInt(index(element.name))
      chunk
        .putShort(20.toShort())
        .putShort(20.toShort())
        .putShort(attributes.size.toShort())
        .putShort(0.toShort())
      chunk.putShort(0.toShort()).putShort(0.toShort())
      for ((name, value) in attributes) {
        chunk.putInt(-1).putInt(index(name))
        when (value) {
          is Boolean ->
            chunk
              .putInt(-1)
              .putShort(8.toShort())
              .put(0.toByte())
              .put(0x12.toByte())
              .putInt(if (value) -1 else 0)
          else -> {
            val text = index(value.toString())
            chunk.putInt(text).putShort(8.toShort()).put(0.toByte()).put(0x03.toByte()).putInt(text)
          }
        }
      }
      chunks.write(chunk.array())
    }

    val data = ByteArrayOutputStream()
    val offsets = IntArray(strings.size)
    for ((i, value) in strings.withIndex()) {
      offsets[i] = data.size()
      val bytes = buffer(2 + value.length * 2 + 2)
      bytes.putShort(value.length.toShort())
      value.forEach { bytes.putChar(it) }
      bytes.putShort(0.toShort())
      data.write(bytes.array())
    }
    while (data.size() % 4 != 0) data.write(0)
    val poolHeader = 28
    val poolSize = poolHeader + strings.size * 4 + data.size()
    val pool = buffer(poolSize)
    pool.putShort(0x0001.toShort()).putShort(poolHeader.toShort()).putInt(poolSize)
    pool.putInt(strings.size).putInt(0).putInt(0).putInt(poolHeader + strings.size * 4).putInt(0)
    offsets.forEach { pool.putInt(it) }
    pool.put(data.toByteArray())

    val total = 8 + poolSize + chunks.size()
    val file = buffer(total)
    file.putShort(0x0003.toShort()).putShort(8.toShort()).putInt(total)
    file.put(pool.array()).put(chunks.toByteArray())
    return file.array()
  }

  private fun buffer(size: Int): ByteBuffer =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
