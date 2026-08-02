package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Test

class ServeDesignReferenceStoreTest {
  private val fileSystem = FakeFileSystem()
  private val root = "/bundle".toPath()
  private val json = Json { prettyPrint = true }

  @Test
  fun `loads an exact preview mapping and verifies its canonical raster`() {
    val raster = pngBytes("canonical-reference")
    val reference =
      DesignReference(
        id = "login-figma",
        previewId = "com.example.LoginPreview",
        label = "Figma login",
        raster =
          DesignReferenceRaster(
            path = "references/login-figma.png",
            width = 390,
            height = 844,
            sha256 = raster.toByteString().sha256().hex(),
          ),
        source =
          DesignReferenceSource(
            provider = "figma",
            uri = "https://www.figma.com/file/private",
            revision = "42",
            attributes = mapOf("nodeId" to "10:2"),
          ),
      )
    writeManifest(listOf(reference))
    fileSystem.write(root / "references/login-figma.png") { write(raster) }

    val store = ServeDesignReferenceStore.load(File("/bundle"), fileSystem)

    assertEquals(listOf(reference), store.forPreview("com.example.LoginPreview"))
    assertContentEquals(raster, store.raster("login-figma"))
    assertNull(store.raster("missing"))
  }

  @Test
  fun `fails soft for traversal duplicate and hash-mismatched records`() {
    val validBytes = pngBytes("valid")
    val references =
      listOf(
        DesignReference(
          id = "valid",
          previewId = "preview",
          raster = DesignReferenceRaster("references/valid.png"),
        ),
        DesignReference(
          id = "valid",
          previewId = "duplicate",
          raster = DesignReferenceRaster("references/duplicate.png"),
        ),
        DesignReference(
          id = "traversal",
          previewId = "preview",
          raster = DesignReferenceRaster("../secret.png"),
        ),
        DesignReference(
          id = "bad-hash",
          previewId = "preview",
          raster = DesignReferenceRaster("references/bad.png", sha256 = "0".repeat(64)),
        ),
        DesignReference(
          id = "not-png",
          previewId = "preview",
          raster = DesignReferenceRaster("references/not-png.png"),
        ),
      )
    writeManifest(references)
    fileSystem.write(root / "references/valid.png") { write(validBytes) }
    fileSystem.write(root / "references/duplicate.png") { write(pngBytes("duplicate")) }
    fileSystem.write(root / "references/bad.png") { write(pngBytes("not the declared hash")) }
    fileSystem.write(root / "references/not-png.png") { writeUtf8("<html>not a raster</html>") }

    val store = ServeDesignReferenceStore.load(File("/bundle"), fileSystem)

    assertEquals(listOf("valid"), store.all.map { it.id })
    assertContentEquals(validBytes, store.raster("valid"))
  }

  private fun writeManifest(references: List<DesignReference>) {
    fileSystem.createDirectories(root / "references")
    fileSystem.write(root / "references/index.json") {
      writeUtf8(json.encodeToString(DesignReferenceManifest(references = references)))
    }
  }

  private fun pngBytes(payload: String): ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) +
      payload.encodeToByteArray()
}
