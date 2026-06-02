package ee.schimke.composeai.io

import kotlin.test.AfterTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test

class SuspendIoTest {

  private val fs = SystemFileSystem
  private val scratch = TemporaryDirectory / "common-io-test-${System.nanoTime()}"

  @AfterTest
  fun cleanup() {
    if (fs.exists(scratch)) fs.deleteRecursively(scratch)
  }

  @Test
  fun roundTripsUtf8AndCreatesParents() = runTest {
    val path = scratch / "nested" / "hello.txt"
    fs.writeUtf8(path, "héllo")
    assertTrue(fs.exists(path))
    assertEquals("héllo", fs.readUtf8(path))
  }

  @Test
  fun roundTripsBytes() = runTest {
    val path = scratch / "bytes.bin"
    val bytes = "abc".encodeUtf8().toByteArray()
    fs.writeBytes(path, bytes)
    assertContentEquals(bytes, fs.readBytes(path))
  }

  @Test
  fun appendsText() = runTest {
    val path = scratch / "log.txt"
    fs.writeUtf8(path, "a")
    fs.appendUtf8(path, "b")
    assertEquals("ab", fs.readUtf8(path))
  }

  @Serializable private data class Config(val name: String, val count: Int)

  @Test
  fun roundTripsJson() = runTest {
    val path = scratch / "config.json"
    val json = Json { prettyPrint = true }
    val value = Config("preview", 3)
    fs.writeJson(path, json, serializer(), value)
    assertEquals(value, fs.readJson(path, json, serializer<Config>()))
  }

  @Test
  fun createsTempFileAndDirectory() = runTest {
    val file = fs.createTempFile(suffix = ".tmp")
    val dir = fs.createTempDirectory()
    try {
      assertTrue(fs.exists(file))
      assertTrue(fs.metadata(dir).isDirectory)
    } finally {
      fs.delete(file)
      fs.deleteRecursively(dir)
    }
  }
}
