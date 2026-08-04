package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogThemeCacheTest {
  @Test
  fun `render cache evicts least recently used entries at its byte cap`() {
    val cache = CatalogThemeCache(maxBytes = 6)
    cache.put("a", byteArrayOf(1, 1, 1))
    cache.put("b", byteArrayOf(2, 2, 2))
    assertContentEquals(byteArrayOf(1, 1, 1), cache.get("a")) // a is now newest

    cache.put("c", byteArrayOf(3, 3, 3))

    assertNull(cache.get("b"))
    assertContentEquals(byteArrayOf(1, 1, 1), cache.get("a"))
    assertContentEquals(byteArrayOf(3, 3, 3), cache.get("c"))
    assertEquals(
      CatalogRenderCacheSnapshot(entries = 2, bytes = 6, maxBytes = 6, evictions = 1),
      cache.renderCacheSnapshot(),
    )
  }

  @Test
  fun `a render larger than the byte cap is not retained`() {
    val cache = CatalogThemeCache(maxBytes = 2)
    cache.put("large", byteArrayOf(1, 2, 3))

    assertNull(cache.get("large"))
    assertEquals(0, cache.renderCacheSnapshot().entries)
    assertEquals(0, cache.renderCacheSnapshot().bytes)
  }
}
