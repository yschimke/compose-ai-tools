package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAccessibilityDataProductRegistryTest {
  @Test
  fun `producer writes parseable empty desktop payloads and removes stale overlays`() {
    val root = Files.createTempDirectory("a11y").toFile()
    val previewDir = root.resolve("preview").apply { mkdirs() }
    val staleOverlay =
      previewDir.resolve(DesktopAccessibilityDataProducer.FILE_OVERLAY).apply {
        writeBytes(byteArrayOf(1, 2, 3))
      }

    DesktopAccessibilityDataProducer.writeArtifacts(root, "preview", emptyList(), pngFile = null)

    assertEquals(
      "{\"findings\":[]}",
      previewDir.resolve(DesktopAccessibilityDataProducer.FILE_ATF).readText(),
    )
    assertEquals(
      "{\"nodes\":[]}",
      previewDir.resolve(DesktopAccessibilityDataProducer.FILE_HIERARCHY).readText(),
    )
    assertFalse(staleOverlay.exists())
  }

  @Test
  fun `registry advertises only desktop a11y products with their required transports`() {
    val registry =
      DesktopAccessibilityDataProductRegistry(Files.createTempDirectory("a11y").toFile())

    assertEquals(
      listOf(
        DesktopAccessibilityDataProducer.KIND_ATF,
        DesktopAccessibilityDataProducer.KIND_HIERARCHY,
        DesktopAccessibilityDataProducer.KIND_OVERLAY,
      ),
      registry.capabilities.map { it.kind },
    )
    assertTrue(registry.capabilities.all { it.requiresRerender })
    assertEquals("a11y", registry.renderModeFor(DesktopAccessibilityDataProducer.KIND_ATF))
    assertNull(registry.renderModeFor("a11y/touchTargets"))
    assertTrue(
      registry.call<DataProductRegistry.Outcome>(
        "missingOutcome",
        "preview",
        DesktopAccessibilityDataProducer.KIND_ATF,
      ) is DataProductRegistry.Outcome.RequiresRerender
    )
  }

  @Test
  fun `files extras and inline policy preserve the overlay as a PNG path`() {
    val root = Files.createTempDirectory("a11y").toFile()
    val previewDir = root.resolve("preview").apply { mkdirs() }
    val overlay =
      previewDir.resolve(DesktopAccessibilityDataProducer.FILE_OVERLAY).apply {
        writeBytes(byteArrayOf(1, 2, 3))
      }
    val registry = DesktopAccessibilityDataProductRegistry(root)

    assertEquals(
      previewDir.resolve(DesktopAccessibilityDataProducer.FILE_HIERARCHY),
      registry.call("fileFor", "preview", DesktopAccessibilityDataProducer.KIND_HIERARCHY),
    )
    assertNull(registry.call<File?>("fileFor", "preview", "unknown"))
    assertFalse(registry.call("allowInlineUpgrade", DesktopAccessibilityDataProducer.KIND_OVERLAY))
    assertTrue(registry.call("allowInlineUpgrade", DesktopAccessibilityDataProducer.KIND_ATF))
    val extra =
      registry
        .call<List<ee.schimke.composeai.daemon.protocol.DataProductExtra>?>(
          "extras",
          "preview",
          DesktopAccessibilityDataProducer.KIND_ATF,
          null,
        )
        .orEmpty()
        .single()
    assertEquals(overlay.absolutePath, extra.path)
    assertEquals("image/png", extra.mediaType)
    assertEquals(3L, extra.sizeBytes)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.call(name: String, vararg args: Any?): T {
    val method =
      javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
    method.isAccessible = true
    return method.invoke(this, *args) as T
  }
}
