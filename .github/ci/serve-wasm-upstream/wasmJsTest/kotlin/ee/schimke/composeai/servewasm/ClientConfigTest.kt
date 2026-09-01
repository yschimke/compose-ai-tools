package ee.schimke.composeai.servewasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientConfigTest {
  @Test
  fun `catalog path selects canonical server routes`() {
    val config =
      ClientConfig.fromLocation(
        search = "?token=secret&session=wrong&preview=button",
        pathname = "/wasm/compose-m3/",
      )

    assertEquals("compose-m3", config.session)
    assertTrue(config.sessionInPath)
    assertEquals("/compose-m3/api/previews", config.serverPath("/api/previews"))
    assertEquals("?token=secret", config.suffix())
    assertEquals("button", config.initialPreview)
    assertEquals(
      "token=secret&preview=button&live=1",
      config.permalinkQuery(AppLocation(previewId = "button", live = true)),
    )
    assertEquals(
      "token=secret&preview=button&uiMode=dark&background=off&fontScale=1.3&localeTag=ar",
      config.permalinkQuery(
        AppLocation(
          previewId = "button",
          uiMode = "dark",
          transparent = true,
          fontScale = 1.3f,
          localeTag = "ar",
        )
      ),
    )
  }

  @Test
  fun `legacy query selection remains usable outside a catalog path`() {
    val config = ClientConfig.fromLocation("?session=wear-m3&token=secret", "/wasm/preview-ui/")

    assertEquals("wear-m3", config.session)
    assertFalse(config.sessionInPath)
    assertEquals("/api/previews", config.serverPath("/api/previews"))
    assertEquals("?session=wear-m3&token=secret", config.suffix())
  }

  @Test
  fun `only wasm catalog paths supply a session`() {
    assertEquals("wear m3", catalogFromWasmPath("/wasm/wear%20m3/"))
    assertNull(catalogFromWasmPath("/wear-m3/"))
    assertNull(catalogFromWasmPath("/wasm/preview-ui/"))
  }

  @Test
  fun `app locations restore catalog preview and composer screens`() {
    assertEquals(AppLocation(), AppLocation.fromSearch(""))
    assertEquals(
      AppLocation(previewId = "button/fill", live = true),
      AppLocation.fromSearch("?preview=button%2Ffill&live=1"),
    )
    assertEquals(
      AppLocation(previewId = "button", live = false),
      AppLocation.fromSearch("?preview=button&live=0"),
    )
    assertEquals(
      AppLocation(composing = true),
      AppLocation.fromSearch("?preview=ignored&compose=1"),
    )
    assertEquals(AppLocation(filter = "filled button"), AppLocation.fromSearch("?q=filled+button"))
    assertEquals(
      AppLocation(
        previewId = "button",
        uiMode = "dark",
        transparent = true,
        fontScale = 2f,
        localeTag = "ar",
      ),
      AppLocation.fromSearch(
        "?preview=button&uiMode=dark&background=off&fontScale=99&localeTag=ar"
      ),
    )
    assertEquals(
      AppLocation(previewId = "button"),
      AppLocation.fromSearch("?preview=button&uiMode=sepia&fontScale=nope"),
    )
  }
}
