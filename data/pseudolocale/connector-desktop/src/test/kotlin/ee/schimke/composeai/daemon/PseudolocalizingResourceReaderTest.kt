package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.ResourceReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the desktop connector pseudolocalises strings at the
 * `org.jetbrains.compose.resources.ResourceReader` byte level — parallels
 * `PseudolocaleResourcesSpanPreservationTest` on Android, which covers the same contract through
 * the `Resources.getText` interception path.
 *
 * The Compose Resources record format the test pokes at is `getStringItem` in
 * `StringResourcesUtils.kt`: UTF-8 bytes split on `|`, last segment is the data:
 * - `string` — single Base64 of the UTF-8 text.
 * - `string-array` — comma-separated Base64 chunks.
 * - `plurals` — comma-separated `<category>:<Base64>` pairs.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalEncodingApi::class)
class PseudolocalizingResourceReaderTest {

  @Test
  fun `string records get the accent and bracket wrap`() {
    val (path, offset, size, raw) = stringRecord("string", "Hello")
    val reader = PseudolocalizingResourceReader(stubReader(path, raw), Pseudolocale.ACCENT)

    val out = runBlocking { reader.readPart(path, offset, size) }
    val decoded = decodeStringRecord(out)

    assertTrue("accented output should start with '[': $decoded", decoded.startsWith("["))
    assertTrue("accented output should end with ']': $decoded", decoded.endsWith("]"))
    assertTrue(
      "accented output should contain the accent form of Hello: $decoded",
      decoded.contains("Ĥêļļö"),
    )
  }

  @Test
  fun `string records get the per-word RLO PDF wrap in bidi mode`() {
    val (path, offset, size, raw) = stringRecord("string", "Hello world")
    val reader = PseudolocalizingResourceReader(stubReader(path, raw), Pseudolocale.BIDI)

    val out = runBlocking { reader.readPart(path, offset, size) }
    val decoded = decodeStringRecord(out)

    // RLO `‮` then characters then PDF `‬` for each whitespace-separated word.
    val rlo = '‮'
    val pdf = '‬'
    assertTrue(
      "bidi output should wrap 'Hello' in RLO/PDF: $decoded",
      decoded.contains("${rlo}Hello${pdf}"),
    )
    assertTrue(
      "bidi output should wrap 'world' in RLO/PDF: $decoded",
      decoded.contains("${rlo}world${pdf}"),
    )
  }

  @Test
  fun `format placeholders survive the transform untouched`() {
    val (path, offset, size, raw) = stringRecord("string", "Hello %1\$s, you have %2\$d items")
    val reader = PseudolocalizingResourceReader(stubReader(path, raw), Pseudolocale.ACCENT)

    val out = runBlocking { reader.readPart(path, offset, size) }
    val decoded = decodeStringRecord(out)

    assertTrue("placeholder %1\$s should survive: $decoded", decoded.contains("%1\$s"))
    assertTrue("placeholder %2\$d should survive: $decoded", decoded.contains("%2\$d"))
  }

  @Test
  fun `string-array records pseudolocalise each entry`() {
    val items = listOf("Save", "Cancel", "Retry")
    val (path, offset, size, raw) = arrayRecord(items)
    val reader = PseudolocalizingResourceReader(stubReader(path, raw), Pseudolocale.ACCENT)

    val out = runBlocking { reader.readPart(path, offset, size) }
    val record = out.decodeToString()
    val parts = record.removePrefix("string-array|").split(",")

    assertEquals(items.size, parts.size)
    val decodedItems = parts.map { Base64.decode(it).decodeToString() }
    assertTrue("first entry accented: ${decodedItems[0]}", decodedItems[0].contains("Šàʌê"))
    assertTrue("second entry accented: ${decodedItems[1]}", decodedItems[1].contains("Çàñçêļ"))
    assertTrue("third entry accented: ${decodedItems[2]}", decodedItems[2].contains("Ŕêţŕý"))
  }

  @Test
  fun `plural records pseudolocalise each category`() {
    val plurals = mapOf("one" to "%1\$d item", "other" to "%1\$d items")
    val (path, offset, size, raw) = pluralRecord(plurals)
    val reader = PseudolocalizingResourceReader(stubReader(path, raw), Pseudolocale.ACCENT)

    val out = runBlocking { reader.readPart(path, offset, size) }
    val record = out.decodeToString()
    val data = record.removePrefix("plurals|")
    val decoded =
      data.split(",").associate { entry ->
        val cat = entry.substringBefore(':')
        val pseudo = Base64.decode(entry.substringAfter(':')).decodeToString()
        cat to pseudo
      }

    assertEquals(setOf("one", "other"), decoded.keys)
    assertTrue("one form pseudolocalised: ${decoded["one"]}", decoded["one"]!!.contains("îţêɱ"))
    assertTrue(
      "other form pseudolocalised: ${decoded["other"]}",
      decoded["other"]!!.contains("îţêɱš"),
    )
    assertTrue(
      "placeholder preserved in one: ${decoded["one"]}",
      decoded["one"]!!.contains("%1\$d"),
    )
  }

  @Test
  fun `non-string records pass through untouched`() {
    // A record with no `|` separator — emulates an image / raw-bytes resource. The reader must
    // return the bytes unchanged, byte-for-byte.
    val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val reader = PseudolocalizingResourceReader(stubReader("img.png", raw), Pseudolocale.ACCENT)

    val out = runBlocking { reader.readPart("img.png", 0, raw.size.toLong()) }

    assertArrayEqualsBytes(raw, out)
  }

  @Test
  fun `read and getUri delegate unchanged`() {
    val raw = "delegated-bytes".encodeToByteArray()
    val delegate = stubReader("any", raw, uri = "file:///fake/any")
    val reader = PseudolocalizingResourceReader(delegate, Pseudolocale.ACCENT)

    val read = runBlocking { reader.read("any") }
    assertArrayEqualsBytes(raw, read)
    assertSame("file:///fake/any", reader.getUri("any"))
  }

  // -- helpers --------------------------------------------------------------

  private data class Record(
    val path: String,
    val offset: Long,
    val size: Long,
    val bytes: ByteArray,
  )

  private fun stringRecord(type: String, text: String): Record {
    val bytes = "$type|${Base64.encode(text.encodeToByteArray())}".encodeToByteArray()
    return Record("strings.cvr", 0, bytes.size.toLong(), bytes)
  }

  private fun arrayRecord(items: List<String>): Record {
    val joined = items.joinToString(",") { Base64.encode(it.encodeToByteArray()) }
    val bytes = "string-array|$joined".encodeToByteArray()
    return Record("strings.cvr", 0, bytes.size.toLong(), bytes)
  }

  private fun pluralRecord(items: Map<String, String>): Record {
    val joined =
      items.entries.joinToString(",") { (k, v) -> "$k:${Base64.encode(v.encodeToByteArray())}" }
    val bytes = "plurals|$joined".encodeToByteArray()
    return Record("strings.cvr", 0, bytes.size.toLong(), bytes)
  }

  private fun decodeStringRecord(bytes: ByteArray): String {
    val record = bytes.decodeToString()
    val data = record.substringAfterLast('|')
    return Base64.decode(data).decodeToString()
  }

  private fun stubReader(
    path: String,
    bytes: ByteArray,
    uri: String = "file:///fake/$path",
  ): ResourceReader {
    val expectedPath = path
    val resolvedUri = uri
    return object : ResourceReader {
      override suspend fun read(path: String): ByteArray {
        check(path == expectedPath) { "unexpected path $path" }
        return bytes
      }

      override suspend fun readPart(path: String, offset: Long, size: Long): ByteArray {
        check(path == expectedPath) { "unexpected path $path" }
        return bytes.copyOfRange(offset.toInt(), (offset + size).toInt())
      }

      override fun getUri(path: String): String {
        check(path == expectedPath) { "unexpected path $path" }
        return resolvedUri
      }
    }
  }

  private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
    assertEquals("byte length", expected.size, actual.size)
    for (i in expected.indices) {
      assertEquals("byte at $i", expected[i], actual[i])
    }
  }
}
