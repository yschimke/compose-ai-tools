package ee.schimke.composeai.preview.svg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgAssetTest {
  @Test
  fun loadsAssetFromClasspath() {
    val bytes = loadSvgAsset("svg/test-badge.svg")
    assertTrue("expected SVG content", bytes.decodeToString().contains("<svg"))
  }

  @Test
  fun toleratesLeadingSlash() {
    assertArrayEquals(loadSvgAsset("svg/test-badge.svg"), loadSvgAsset("/svg/test-badge.svg"))
  }

  @Test
  fun missingAssetThrowsHelpfully() {
    val ex =
      assertThrows(IllegalArgumentException::class.java) { loadSvgAsset("svg/does-not-exist.svg") }
    assertTrue(
      "message should point at src/main/resources",
      ex.message!!.contains("src/main/resources"),
    )
  }
}
