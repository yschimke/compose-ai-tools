package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

  /**
   * A record whose optional `match` this reader cannot decode must cost only itself.
   *
   * The regression: `match` was decoded as part of the enclosing manifest, so `"match": {}` (a
   * half-written producer, `percent` missing) threw while parsing the envelope, the whole decode
   * landed in `load`'s `runCatching`, and the store came back EMPTY — one bad record and the
   * catalog's entire design-spec lane went dark on every page, silently. The per-record validation
   * that exists to drop exactly this never got to run.
   */
  @Test
  fun `a record with an undecodable match is dropped without taking the manifest with it`() {
    val goodRaster = pngBytes("good")
    val badRaster = pngBytes("bad")
    fileSystem.createDirectories(root / "references")
    fileSystem.write(root / "references/good.png") { write(goodRaster) }
    fileSystem.write(root / "references/bad.png") { write(badRaster) }
    // Hand-written rather than round-tripped through the serializer: the point is a document no
    // producer in this repo would emit, which is precisely the case a fail-soft reader must
    // survive.
    fileSystem.write(root / "references/index.json") {
      writeUtf8(
        """
        {
          "schema": "compose-preview-references/v1",
          "references": [
            {
              "id": "bad",
              "previewId": "com.example.BadPreview",
              "raster": { "path": "references/bad.png" },
              "match": {}
            },
            {
              "id": "good",
              "previewId": "com.example.GoodPreview",
              "raster": { "path": "references/good.png" },
              "match": { "percent": 98.5, "scoreVersion": ${ServeDesignReferenceStore.SCORE_VERSION} }
            }
          ]
        }
        """
          .trimIndent()
      )
    }

    val store = ServeDesignReferenceStore.load(File("/bundle"), fileSystem)

    assertEquals(emptyList(), store.forPreview("com.example.BadPreview"))
    assertEquals(1, store.all.size, "the readable record still serves")
    assertEquals(98.5, store.forPreview("com.example.GoodPreview").single().match?.percent)
  }

  @Test
  fun `a nonsense percentage is dropped without dropping its reference`() {
    // A verdict is printed on a chip. A percentage outside 0..100 is a producer bug, and the cost
    // of ignoring it is a chip with no number — where the cost of trusting it is a chip stating a
    // falsehood, and the cost of dropping the record is a page with no design spec at all.
    val raster = pngBytes("out-of-range")
    val reference =
      DesignReference(
        id = "wild",
        previewId = "com.example.WildPreview",
        raster = DesignReferenceRaster(path = "references/wild.png"),
        match =
          DesignReferenceMatch(
            percent = 4200.0,
            scoreVersion = ServeDesignReferenceStore.SCORE_VERSION,
          ),
      )
    writeManifest(listOf(reference))
    fileSystem.write(root / "references/wild.png") { write(raster) }

    val store = ServeDesignReferenceStore.load(File("/bundle"), fileSystem)

    val loaded = store.forPreview("com.example.WildPreview").single()
    assertEquals("wild", loaded.id, "the reference itself still serves")
    assertNull(loaded.match, "the nonsense verdict is not published")
  }

  @Test
  fun `a match minted by another kernel is dropped without dropping its reference`() {
    // The scorer's kernel moves deliberately, and every published number moves with it. A
    // delivery branch is regenerated on its own schedule, so a viewer WILL meet a catalog baked
    // before the change — and printing that chip would put an old-kernel number beside a readout
    // the lane computes with the new one. Two numbers for one comparison, disagreeing at a glance,
    // is the one failure a number whose job is to be trusted at a glance cannot survive.
    val raster = pngBytes("stale")
    val reference =
      DesignReference(
        id = "stale",
        previewId = "com.example.StalePreview",
        raster = DesignReferenceRaster(path = "references/stale.png"),
        match =
          DesignReferenceMatch(
            percent = 98.5,
            scoreVersion = ServeDesignReferenceStore.SCORE_VERSION - 1,
          ),
      )
    writeManifest(listOf(reference))
    fileSystem.write(root / "references/stale.png") { write(raster) }

    val store = ServeDesignReferenceStore.load(File("/bundle"), fileSystem)

    val loaded = store.forPreview("com.example.StalePreview").single()
    assertEquals("stale", loaded.id, "the reference itself still serves")
    // Not printed, and not an error: the lane scores live on entry, which is what a chip with no
    // verdict has always fallen back to.
    assertNull(loaded.match, "a number from another kernel is not published")
  }

  @Test
  fun `a match that names no kernel at all is treated the same way`() {
    // Every catalog published before the version existed is this case. Absence is not "current".
    val raster = pngBytes("unversioned")
    val reference =
      DesignReference(
        id = "unversioned",
        previewId = "com.example.UnversionedPreview",
        raster = DesignReferenceRaster(path = "references/unversioned.png"),
        match = DesignReferenceMatch(percent = 98.5),
      )
    writeManifest(listOf(reference))
    fileSystem.write(root / "references/unversioned.png") { write(raster) }

    val store = ServeDesignReferenceStore.load(File("/bundle"), fileSystem)

    assertNull(store.forPreview("com.example.UnversionedPreview").single().match)
  }

  @Test
  fun `the served kernel version agrees with the scorer that mints it`() {
    // Two copies of a constant are fine while something fails when they disagree, and this is the
    // pair that has to: the browser mints the number and the host decides whether to print it, so a
    // host reading the wrong version would discard every current match or trust every stale one.
    val source =
      File(repoRoot(), "cli/serve-web/src/scorer/tuning.ts").let {
        if (it.exists()) it else File("cli/serve-web/src/scorer/tuning.ts")
      }
    assertTrue(source.exists(), "the scorer's tuning moved: ${source.absolutePath}")
    val declared =
      Regex("export const SCORE_VERSION\\s*=\\s*(\\d+)")
        .find(source.readText())
        ?.groupValues
        ?.get(1)
    assertEquals(ServeDesignReferenceStore.SCORE_VERSION.toString(), declared)
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
