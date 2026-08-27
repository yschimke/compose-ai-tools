package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Content-sniffing + summarising of the known document formats the serve host ingests.
 *
 * The sniff is the actual security boundary of the document lane: an upload that isn't a known
 * document must be refused rather than parked on the host's origin, so the "rejects" cases below
 * matter as much as the happy paths.
 */
class ServeDocFormatsTest {

  @Test
  fun `remote compose document is detected and summarised from its header`() {
    val doc =
      ServeDocFixtures.remoteComposeDoc(major = 1, minor = 2, patch = 3, width = 480, height = 240)

    assertEquals(ServeDocFormats.REMOTE_COMPOSE, ServeDocFormats.detect(doc))
    assertEquals(ServeDocSize(480, 240), ServeDocFormats.REMOTE_COMPOSE.size(doc))
    val facts = ServeDocFormats.REMOTE_COMPOSE.describe(doc)
    assertEquals("1.2.3", facts.first { it.key == "Format version" }.value)
    assertEquals("480 × 240", facts.first { it.key == "Document size" }.value)
  }

  @Test
  fun `a truncated remote compose header still yields what it could read`() {
    val full = ServeDocFixtures.remoteComposeDoc(width = 100, height = 100)
    val truncated = full.copyOf(16)

    // Still recognisably an RC document (the magic is intact) — the walk just stops early, so the
    // page shows the version and no size rather than failing the whole upload.
    assertEquals(ServeDocFormats.REMOTE_COMPOSE, ServeDocFormats.detect(truncated))
    assertNull(ServeDocFormats.REMOTE_COMPOSE.size(truncated))
    assertTrue(
      ServeDocFormats.REMOTE_COMPOSE.describe(truncated).any { it.key == "Format version" }
    )
  }

  @Test
  fun `lottie animation is detected and summarised`() {
    val doc = ServeDocFixtures.lottieDoc()

    assertEquals(ServeDocFormats.LOTTIE, ServeDocFormats.detect(doc))
    assertEquals(ServeDocSize(200, 100), ServeDocFormats.LOTTIE.size(doc))
    val facts = ServeDocFormats.LOTTIE.describe(doc).associate { it.key to it.value }
    assertEquals("Spinner", facts["Name"])
    assertEquals("5.7.4", facts["Bodymovin version"])
    assertEquals("200 × 100", facts["Size"])
    assertEquals("60 @ 30 fps", facts["Frames"])
    assertEquals("2s", facts["Duration"])
    assertEquals("2", facts["Layers"])
  }

  @Test
  fun `json that is not an animation is not a document`() {
    // A `layers`-carrying object without the frame-rate / in-out trio isn't a playable animation.
    val notAnimation = """{"layers":[],"hello":"world"}""".toByteArray()
    assertNull(ServeDocFormats.detect(notAnimation))
    assertNull(ServeDocFormats.detect("""{"v":"5.7.4"}""".toByteArray()))
    assertNull(ServeDocFormats.detect("not json at all".toByteArray()))
  }

  @Test
  fun `binary uploads that are not documents are refused`() {
    val zip =
      ByteArrayOutputStream()
        .also { out ->
          ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("previews/a.png"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
          }
        }
        .toByteArray()
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    val html = "<html><script>alert(1)</script></html>".toByteArray()

    assertNull(ServeDocFormats.detect(zip))
    assertNull(ServeDocFormats.detect(png))
    assertNull(ServeDocFormats.detect(html))
    assertNull(ServeDocFormats.detect(ByteArray(0)))
    // A zero-opcode file whose next bytes aren't the magic must not pass as Remote Compose.
    assertNull(ServeDocFormats.detect(ByteArray(64)))
  }

  @Test
  fun `every format resolves by id and mounts its player under a distinct path`() {
    for (format in ServeDocFormats.ALL) {
      assertEquals(format, ServeDocFormats.byId(format.id))
      assertEquals("/doc-player/${format.id}/bundle.js", format.playerPath)
    }
    assertNull(ServeDocFormats.byId("../../etc/passwd"))
    assertEquals(
      ServeDocFormats.ALL.size,
      ServeDocFormats.ALL.map { it.playerPath }.distinct().size,
      "each format serves its own player path",
    )
  }
}

/** Shared document fixtures for the store / routing tests. */
object ServeDocFixtures {

  /**
   * A minimal Remote Compose document: the `Header` operation (opcode 0, `magic|major`, minor,
   * patch) followed by a two-entry property table carrying `DOC_WIDTH` / `DOC_HEIGHT`.
   */
  fun remoteComposeDoc(
    major: Int = 1,
    minor: Int = 0,
    patch: Int = 0,
    width: Int = 256,
    height: Int = 256,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    fun int(value: Int) {
      out.write((value ushr 24) and 0xFF)
      out.write((value ushr 16) and 0xFF)
      out.write((value ushr 8) and 0xFF)
      out.write(value and 0xFF)
    }
    fun short(value: Int) {
      out.write((value ushr 8) and 0xFF)
      out.write(value and 0xFF)
    }
    out.write(0) // Header OP_CODE
    int((0x048C shl 16) or major)
    int(minor)
    int(patch)
    int(2) // property count
    short(5) // DOC_WIDTH, DATA_TYPE_INT
    short(4)
    int(width)
    short(6) // DOC_HEIGHT, DATA_TYPE_INT
    short(4)
    int(height)
    return out.toByteArray()
  }

  /** A minimal but shape-complete Lottie (Bodymovin) animation. */
  fun lottieDoc(name: String = "Spinner"): ByteArray =
    """
    {"v":"5.7.4","nm":"$name","fr":30,"ip":0,"op":60,"w":200,"h":100,
     "layers":[{"ind":1,"ty":4,"nm":"a"},{"ind":2,"ty":4,"nm":"b"}]}
    """
      .trimIndent()
      .toByteArray()
}
