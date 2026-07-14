package ee.schimke.composeai.daemon

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
 * Verifies the desktop connector turns `stringResource(...)` lookups into editable override knobs at
 * the `org.jetbrains.compose.resources.ResourceReader` byte level — the auto-resource counterpart to
 * `PseudolocalizingResourceReaderTest`. Uses a fake [ResourceOverrideSink] to observe the (key,
 * default) the reader records and to inject a seeded replacement, without standing up the controller.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalEncodingApi::class)
class RecordingResourceReaderTest {

  @Test
  fun `string record records a res-keyed knob and re-encodes the effective value`() {
    val (path, offset, size, raw) = stringRecord("Hello")
    val seen = mutableListOf<Pair<String, String>>()
    val reader =
      RecordingResourceReader(stubReader(path, raw)) { key, default ->
        seen += key to default
        default // no seed → original text
      }

    val out = runBlocking { reader.readPart(path, offset, size) }

    assertEquals(listOf("res:$path#$offset" to "Hello"), seen)
    assertEquals("Hello", decodeStringRecord(out))
  }

  @Test
  fun `a seeded replacement is written back into the record`() {
    val (path, offset, size, raw) = stringRecord("Hello")
    val reader =
      RecordingResourceReader(stubReader(path, raw)) { _, _ -> "Bonjour" }

    val out = runBlocking { reader.readPart(path, offset, size) }

    assertEquals("Bonjour", decodeStringRecord(out))
  }

  @Test
  fun `string-array records key and substitute each entry independently`() {
    val items = listOf("Save", "Cancel", "Retry")
    val (path, offset, size, raw) = arrayRecord(items)
    val seen = mutableListOf<Pair<String, String>>()
    val reader =
      RecordingResourceReader(stubReader(path, raw)) { key, default ->
        seen += key to default
        if (default == "Cancel") "Dismiss" else default
      }

    val out = runBlocking { reader.readPart(path, offset, size) }
    val parts = out.decodeToString().removePrefix("string-array|").split(",")
    val decoded = parts.map { Base64.decode(it).decodeToString() }

    assertEquals(
      listOf("res:$path#$offset[0]" to "Save", "res:$path#$offset[1]" to "Cancel", "res:$path#$offset[2]" to "Retry"),
      seen,
    )
    assertEquals(listOf("Save", "Dismiss", "Retry"), decoded)
  }

  @Test
  fun `plural records key by category and substitute`() {
    val plurals = mapOf("one" to "%1\$d item", "other" to "%1\$d items")
    val (path, offset, size, raw) = pluralRecord(plurals)
    val seen = mutableListOf<String>()
    val reader =
      RecordingResourceReader(stubReader(path, raw)) { key, default ->
        seen += key
        if (key.endsWith(":other")) "%1\$d things" else default
      }

    val out = runBlocking { reader.readPart(path, offset, size) }
    val data = out.decodeToString().removePrefix("plurals|")
    val decoded =
      data.split(",").associate { entry ->
        entry.substringBefore(':') to Base64.decode(entry.substringAfter(':')).decodeToString()
      }

    assertTrue(seen.contains("res:$path#$offset:one"))
    assertTrue(seen.contains("res:$path#$offset:other"))
    assertEquals("%1\$d item", decoded["one"])
    assertEquals("%1\$d things", decoded["other"])
  }

  @Test
  fun `non-string records pass through untouched and never hit the sink`() {
    val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    var called = false
    val reader =
      RecordingResourceReader(stubReader("img.png", raw)) { _, _ ->
        called = true
        ""
      }

    val out = runBlocking { reader.readPart("img.png", 0, raw.size.toLong()) }

    assertArrayEqualsBytes(raw, out)
    assertTrue("sink must not be consulted for non-string records", !called)
  }

  @Test
  fun `read and getUri delegate unchanged`() {
    val raw = "delegated-bytes".encodeToByteArray()
    val delegate = stubReader("any", raw, uri = "file:///fake/any")
    val reader = RecordingResourceReader(delegate) { _, d -> d }

    val read = runBlocking { reader.read("any") }
    assertArrayEqualsBytes(raw, read)
    assertSame("file:///fake/any", reader.getUri("any"))
  }

  // -- helpers --------------------------------------------------------------

  private data class Record(val path: String, val offset: Long, val size: Long, val bytes: ByteArray)

  private fun stringRecord(text: String): Record {
    val bytes = "string|${Base64.encode(text.encodeToByteArray())}".encodeToByteArray()
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
    return Base64.decode(record.substringAfterLast('|')).decodeToString()
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
