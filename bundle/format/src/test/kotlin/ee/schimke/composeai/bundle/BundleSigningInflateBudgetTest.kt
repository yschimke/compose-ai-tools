package ee.schimke.composeai.bundle

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [BundleSigning.canonicalDigest] and [BundleSigning.readSignatures] read client-controlled zips on
 * the server's upload path. Every member — including ones the server's extraction ignores — must be
 * charged to one inflation budget and streamed, never inflated whole into memory.
 */
class BundleSigningInflateBudgetTest {

  private fun zip(build: ZipOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { it.build() }
    return out.toByteArray()
  }

  private fun ZipOutputStream.entry(name: String, size: Long, byte: Int = 0) {
    putNextEntry(ZipEntry(name))
    val chunk = ByteArray(1 shl 20) { byte.toByte() }
    var remaining = size
    while (remaining > 0) {
      val n = minOf(remaining, chunk.size.toLong()).toInt()
      write(chunk, 0, n)
      remaining -= n
    }
    closeEntry()
  }

  @Test
  fun `streamed digest matches the whole-entry hash`() {
    val payload = ByteArray(3 * (1 shl 20) + 17) { (it % 251).toByte() }
    val bytes = zip {
      putNextEntry(ZipEntry("previews/a.png"))
      write(payload)
      closeEntry()
    }
    val line = "previews/a.png:${BundleSigning.hex(BundleSigning.sha256(payload))}"
    assertEquals(
      BundleSigning.hex(BundleSigning.sha256(line.toByteArray(Charsets.UTF_8))),
      BundleSigning.hex(BundleSigning.canonicalDigest(bytes)),
    )
  }

  @Test
  fun `an ignored member inflating past the budget is rejected`() {
    val bomb = zip {
      entry("previews/probe.png", 8)
      entry("ignored-padding.bin", BundleSigning.MAX_UNCOMPRESSED_BYTES + 1)
    }
    assertFailsWith<ZipException> { BundleSigning.canonicalDigest(bomb) }
    assertFailsWith<ZipException> { BundleSigning.readSignatures(bomb) }
  }

  @Test
  fun `too many entries are rejected`() {
    val many = zip { repeat(BundleSigning.MAX_ENTRIES + 1) { entry("e/$it", 0) } }
    assertFailsWith<ZipException> { BundleSigning.canonicalDigest(many) }
    assertFailsWith<ZipException> { BundleSigning.readSignatures(many) }
  }

  @Test
  fun `an oversized signatures json is rejected`() {
    val big = zip {
      entry(BundleSigning.SIGNATURES_PATH, BundleSigning.MAX_SIGNATURES_BYTES + 1L, ' '.code)
    }
    assertFailsWith<ZipException> { BundleSigning.readSignatures(big) }
  }

  @Test
  fun `an unsigned bundle still reads as unsigned`() {
    assertNull(BundleSigning.readSignatures(zip { entry("previews/a.png", 8) }))
  }
}
