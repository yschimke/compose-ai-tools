package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.UiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeOverridesTest {

  private fun ok(
    params: Map<String, String>
  ): ee.schimke.composeai.daemon.protocol.PreviewOverrides {
    val parsed = ServeOverrides.parse(params)
    assertTrue(parsed is OverrideParse.Ok, "expected Ok, got $parsed")
    return parsed.overrides
  }

  @Test
  fun `empty params leave every field null`() {
    val o = ok(emptyMap())
    assertNull(o.uiMode)
    assertNull(o.device)
    assertNull(o.localeTag)
    assertNull(o.fontScale)
    assertNull(o.orientation)
    assertNull(o.widthPx)
    assertNull(o.heightPx)
    assertNull(o.density)
    assertNull(o.inspectionMode)
  }

  @Test
  fun `maps each field`() {
    val o =
      ok(
        mapOf(
          "uiMode" to "dark",
          "device" to "id:pixel_5",
          "localeTag" to "ja-JP",
          "fontScale" to "1.3",
          "density" to "2.0",
          "widthPx" to "400",
          "heightPx" to "800",
          "orientation" to "landscape",
          "inspectionMode" to "true",
        )
      )
    assertEquals(UiMode.DARK, o.uiMode)
    assertEquals("id:pixel_5", o.device)
    assertEquals("ja-JP", o.localeTag)
    assertEquals(1.3f, o.fontScale)
    assertEquals(2.0f, o.density)
    assertEquals(400, o.widthPx)
    assertEquals(800, o.heightPx)
    assertEquals(Orientation.LANDSCAPE, o.orientation)
    assertEquals(true, o.inspectionMode)
  }

  @Test
  fun `blank values are treated as absent`() {
    val o = ok(mapOf("uiMode" to "", "device" to "", "fontScale" to ""))
    assertNull(o.uiMode)
    assertNull(o.device)
    assertNull(o.fontScale)
  }

  @Test
  fun `invalid enums and numbers are rejected with a reason`() {
    for (bad in
      listOf(
        mapOf("uiMode" to "purple"),
        mapOf("orientation" to "sideways"),
        mapOf("fontScale" to "huge"),
        mapOf("fontScale" to "-1"),
        mapOf("density" to "0"),
        mapOf("widthPx" to "wide"),
        mapOf("widthPx" to "-5"),
        mapOf("inspectionMode" to "maybe"),
      )) {
      val parsed = ServeOverrides.parse(bad)
      assertTrue(parsed is OverrideParse.Invalid, "expected Invalid for $bad, got $parsed")
      assertTrue(parsed.message.isNotBlank())
    }
  }

  @Test
  fun `cache key is stable for equal overrides and order-independent`() {
    val a = ok(mapOf("uiMode" to "dark", "device" to "id:pixel_5", "fontScale" to "1.3"))
    val b = ok(mapOf("fontScale" to "1.3", "device" to "id:pixel_5", "uiMode" to "dark"))
    assertEquals(ServeOverrides.cacheKey("preview.A", a), ServeOverrides.cacheKey("preview.A", b))
  }

  @Test
  fun `cache key differs by preview id and by any override field`() {
    val base = ok(mapOf("uiMode" to "light"))
    val dark = ok(mapOf("uiMode" to "dark"))
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", base),
      ServeOverrides.cacheKey("preview.A", dark),
    )
    assertNotEquals(
      ServeOverrides.cacheKey("preview.A", base),
      ServeOverrides.cacheKey("preview.B", base),
    )
  }

  @Test
  fun `preview mode parses known wire values`() {
    assertEquals(PreviewMode.SNAPSHOT, PreviewMode.parse("snapshot"))
    assertEquals(PreviewMode.LIVE, PreviewMode.parse("live"))
    assertNull(PreviewMode.parse("bogus"))
    assertNull(PreviewMode.parse(null))
  }
}
