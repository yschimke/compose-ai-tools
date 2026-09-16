package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared-backplate lane of [ServeDesignPageStore] — the trust boundary for bytes a delivery
 * branch hands the server underneath its sanitized layer.
 *
 * A page is a stack now: plates, then the sanitized SVG, then the catalog's renders. The plates are
 * the one layer that is NOT markup this server walked, so everything about them is verified rather
 * than believed — and a plate that fails costs its own placements, never the page.
 */
class ServeDesignPageAssetsTest {

  private val root: File = createTempDirectory("design-page-assets").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private val plateId = "a".repeat(64)

  /** A minimal, real PNG header — signature plus enough bytes to satisfy the size checks. */
  private fun pngBytes(size: Int = 64): ByteArray =
    ByteArray(size).also {
      byteArrayOf(
          0x89.toByte(),
          'P'.code.toByte(),
          'N'.code.toByte(),
          'G'.code.toByte(),
          0x0D,
          0x0A,
          0x1A,
          0x0A,
        )
        .copyInto(it)
    }

  private fun write(relative: String, bytes: ByteArray) {
    val file = File(root, "pages/$relative")
    file.parentFile.mkdirs()
    file.writeBytes(bytes)
  }

  private fun manifest(
    assets: String = assetRecord(),
    background: String = placement(),
  ): String =
    """
    {
      "version": 2,
      "fileKey": "kitkey",
      "assets": [$assets],
      "pages": [
        {
          "id": "buttons",
          "name": "Buttons",
          "nodeId": "1:2",
          "frame": { "width": 2048.0, "height": 1024.0 },
          "image": { "uri": "buttons.svg", "format": "svg" },
          "designBlend": "screen",
          "background": [$background]
        }
      ]
    }
    """
      .trimIndent()

  private fun assetRecord(
    id: String = plateId,
    uri: String = "assets/$id.png",
    format: String = "png",
    bytes: Int = 64,
  ) = """{"id":"$id","uri":"$uri","format":"$format","width":8,"height":8,"bytes":$bytes}"""

  private fun placement(asset: String = plateId) =
    """{"asset":"$asset","x":0.0,"y":0.0,"width":2048.0,"height":1024.0}"""

  private fun store(
    manifestJson: String = manifest(),
    plate: ByteArray? = pngBytes(),
    plateAt: String = "assets/$plateId.png",
  ): ServeDesignPageStore {
    write("index.json", manifestJson.toByteArray())
    write(
      "buttons.svg",
      """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2048 1024"/>""".toByteArray(),
    )
    if (plate != null) write(plateAt, plate)
    return ServeDesignPageStore.load(root)
  }

  // --- the happy path -------------------------------------------------------

  @Test
  fun `a verified plate is served and placed`() {
    val store = store()
    val page = store.pages.single()
    assertEquals(plateId, store.asset(plateId)?.id)
    assertEquals(listOf(plateId), store.background(page).map { it.asset })
  }

  /** One stored plate, many placements — the property that keeps a heavy sheet under its cap. */
  @Test
  fun `one plate serves many placements`() {
    val five =
      (0 until 5).joinToString(",") {
        """{"asset":"$plateId","x":${it * 100}.0,"y":0.0,"width":100.0,"height":100.0}"""
      }
    val store = store(manifest(background = five))
    assertEquals(5, store.background(store.pages.single()).size)
    assertEquals(1, store.assets().size)
  }

  // --- verification ---------------------------------------------------------

  /** A record with no file is not a plate, and its placements must not draw a hole. */
  @Test
  fun `a missing file is refused`() {
    val store = store(plate = null)
    assertNull(store.asset(plateId))
    assertTrue(store.background(store.pages.single()).isEmpty())
  }

  /**
   * `format` is a CLAIM. A file that does not open as what it says it is gets refused on its first
   * bytes rather than handed to a decoder to cope with.
   */
  @Test
  fun `a mislabelled payload is refused on its signature`() {
    val store = store(plate = "GIF89a".toByteArray() + ByteArray(58))
    assertNull(store.asset(plateId))
  }

  /** An SVG smuggled in as a plate is markup nobody walked, underneath the sanitized layer. */
  @Test
  fun `an svg is refused whatever it is labelled`() {
    val store = store(plate = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())
    assertNull(store.asset(plateId))
  }

  /** The truncated download, caught before a decoder meets a malformed image. */
  @Test
  fun `a size that disagrees with the declaration is refused`() {
    val store = store(plate = pngBytes(size = 32))
    assertNull(store.asset(plateId))
  }

  /** Refused on the declaration, before any I/O — the decompression-bomb gate. */
  @Test
  fun `a declared bomb never reaches the filesystem`() {
    val bomb =
      """{"id":"$plateId","uri":"assets/$plateId.png","format":"png","width":40000,"height":40000,"bytes":64}"""
    assertNull(store(manifest(assets = bomb)).asset(plateId))
  }

  /** A traversing uri must not pull a file from outside the staged bundle. */
  @Test
  fun `a traversing path is refused`() {
    val escape = assetRecord(uri = "../../../etc/passwd")
    assertNull(store(manifest(assets = escape)).asset(plateId))
  }

  // --- fail-soft ------------------------------------------------------------

  /**
   * The whole posture in one case: a bad plate costs its own placements and nothing else. The page
   * still draws, its nodes still resolve, and the catalog still serves its grid.
   */
  @Test
  fun `a bad plate costs its placements but never the page`() {
    val store = store(plate = "not a png".toByteArray())
    val page = store.pages.single()
    assertEquals("buttons", page.id)
    assertTrue(store.svg(page.id)!!.contains("<svg"))
    assertTrue(store.background(page).isEmpty())
  }

  /** A placement naming a plate the bundle never carried resolves to nothing, not to a hole. */
  @Test
  fun `a dangling placement does not draw`() {
    val other = "b".repeat(64)
    val store = store(manifest(background = placement(asset = other)))
    assertTrue(store.background(store.pages.single()).isEmpty())
  }
}
