package ee.schimke.composeai.daemon

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedDeviceArtSourceTest {

  private val fs = FakeFileSystem()

  private fun seed(path: String, bytes: ByteArray) {
    fs.createDirectories(path.toPath().parent!!)
    fs.write(path.toPath()) { write(bytes) }
  }

  @Test
  fun returnsCachedLayerBytes() {
    val bytes = byteArrayOf(1, 2, 3, 4)
    seed("/cache/wear_round/port_back.png", bytes)
    val source = CachedDeviceArtSource("/cache", fs)
    assertArrayEquals(bytes, source.fetch("wear_round", "back"))
  }

  @Test
  fun missReturnsNull() {
    val source = CachedDeviceArtSource("/cache", fs)
    assertNull(source.fetch("wear_round", "back"))
  }

  @Test
  fun emptyCachedFileReturnsNull() {
    seed("/cache/pixel_5/port_back.png", ByteArray(0))
    val source = CachedDeviceArtSource("/cache", fs)
    assertNull(source.fetch("pixel_5", "back"))
  }
}
