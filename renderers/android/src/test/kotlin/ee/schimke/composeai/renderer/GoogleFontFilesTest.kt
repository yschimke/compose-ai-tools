package ee.schimke.composeai.renderer

import ee.schimke.composeai.fonts.google.GoogleFontKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [GoogleFontFiles] — the read-only view the `compose/figma-svg` export uses to find
 * the exact TTF the render drew with, without ever downloading (issue #2906). Pure JVM.
 */
class GoogleFontFilesTest {

  @get:Rule val tempDir = TemporaryFolder()

  private var savedCacheDir: String? = null

  @Before
  fun setUp() {
    savedCacheDir = System.getProperty("composeai.fonts.cacheDir")
  }

  @After
  fun tearDown() {
    if (savedCacheDir == null) System.clearProperty("composeai.fonts.cacheDir")
    else System.setProperty("composeai.fonts.cacheDir", savedCacheDir)
  }

  private fun warm(family: String, weight: Int, italic: Boolean) {
    val dir = System.getProperty("composeai.fonts.cacheDir")
    java.io.File(dir, GoogleFontKey(family, weight, italic).fileName()).writeBytes(byteArrayOf(1))
  }

  private fun useCacheDir() {
    System.setProperty("composeai.fonts.cacheDir", tempDir.root.absolutePath)
  }

  @Test
  fun `cachedNearest returns the exact weight when present`() {
    useCacheDir()
    warm("Lato", 400, false)
    warm("Lato", 700, false)
    assertEquals("lato-400.ttf", GoogleFontFiles.cachedNearest("Lato", 400, false)?.name)
    assertEquals("lato-700.ttf", GoogleFontFiles.cachedNearest("Lato", 700, false)?.name)
  }

  @Test
  fun `cachedNearest falls back to the closest cached weight`() {
    // The JetLagged shape: only the default face is cached, but the heading asks for 600. The
    // render drew the 400 face synthetically bolded, so the 400 file is the face to embed.
    useCacheDir()
    warm("Lato", 400, false)
    assertEquals("lato-400.ttf", GoogleFontFiles.cachedNearest("Lato", 600, false)?.name)
  }

  @Test
  fun `cachedNearest picks the numerically closest of several weights`() {
    useCacheDir()
    warm("Lato", 300, false)
    warm("Lato", 700, false)
    assertEquals("lato-700.ttf", GoogleFontFiles.cachedNearest("Lato", 600, false)?.name)
    assertEquals("lato-300.ttf", GoogleFontFiles.cachedNearest("Lato", 450, false)?.name)
  }

  @Test
  fun `cachedNearest keeps italic and upright separate`() {
    useCacheDir()
    warm("Lato", 400, false)
    warm("Lato", 700, true)
    // An upright request must not borrow the italic file, and vice versa.
    assertEquals("lato-400.ttf", GoogleFontFiles.cachedNearest("Lato", 600, false)?.name)
    assertEquals("lato-700-italic.ttf", GoogleFontFiles.cachedNearest("Lato", 400, true)?.name)
  }

  @Test
  fun `cachedNearest does not cross family boundaries`() {
    useCacheDir()
    warm("Roboto", 400, false)
    assertNull(GoogleFontFiles.cachedNearest("Lato", 400, false))
  }

  @Test
  fun `cachedNearest returns null with no cache dir configured`() {
    System.clearProperty("composeai.fonts.cacheDir")
    assertNull(GoogleFontFiles.cachedNearest("Lato", 400, false))
  }
}
