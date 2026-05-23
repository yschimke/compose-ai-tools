package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for compose-ai-tools#1360 finding #3: verify the reflective machinery in
 * [PseudolocaleResourceCache] actually finds and clears the Compose Multiplatform Resources
 * process-wide string-item cache, instead of silently no-opping if the field shape drifts.
 *
 * This test pins:
 * 1. The class + field names we reflect against still exist on the CMP version this project
 *    compiles against. If a future CMP bump moves them, this test fails fast and we update the
 *    field names in [PseudolocaleResourceCache] alongside the dependency bump.
 * 2. The `cache` field inside `AsyncCache` is a [MutableMap], so `Map.clear()` is valid.
 * 3. The clear actually removes entries we plant — the cache is observably empty after the call.
 */
class PseudolocaleResourceCacheTest {

  @Test
  fun `cache class and field are reachable via reflection on the CMP version we compile against`() {
    val utilsClass = Class.forName("org.jetbrains.compose.resources.StringResourcesUtilsKt")
    val cacheField = utilsClass.getDeclaredField("stringItemsCache").apply { isAccessible = true }
    val asyncCache = cacheField.get(null)
    assertNotNull(
      "StringResourcesUtilsKt.stringItemsCache must not be null on a fresh JVM — if this fails, " +
        "the CMP resources module has moved the cache and PseudolocaleResourceCache needs an update",
      asyncCache,
    )

    val mapField = asyncCache!!.javaClass.getDeclaredField("cache").apply { isAccessible = true }
    val map = mapField.get(asyncCache)
    assertTrue(
      "AsyncCache.cache must be a MutableMap so the reflective clear can call Map.clear() on " +
        "it — got ${map?.javaClass?.name}",
      map is MutableMap<*, *>,
    )
  }

  @Test
  fun `clearStringResourcesCacheBestEffort empties the underlying map and reports success`() {
    val utilsClass = Class.forName("org.jetbrains.compose.resources.StringResourcesUtilsKt")
    val cacheField = utilsClass.getDeclaredField("stringItemsCache").apply { isAccessible = true }
    val asyncCache = cacheField.get(null)!!
    val mapField = asyncCache.javaClass.getDeclaredField("cache").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") val map = mapField.get(asyncCache) as MutableMap<Any, Any>

    // Plant a sentinel so we can prove the clear actually emptied the map. We use a fake key
    // (clearly not one CMP would ever request) and a stub value — the value type only needs to be
    // `Any?` because the test plants and inspects through erased generics.
    map["__test-sentinel-for-#1360__"] = SENTINEL_VALUE
    assertTrue(
      "sentinel must be in the cache before the clear runs — if this fails, the planting " +
        "approach is broken and the test is meaningless",
      map.containsKey("__test-sentinel-for-#1360__"),
    )

    val cleared = PseudolocaleResourceCache.clearStringResourcesCacheBestEffort()

    assertTrue(
      "clearStringResourcesCacheBestEffort must report success against the bundled CMP version",
      cleared,
    )
    assertEquals("cache must be empty immediately after a successful clear", 0, map.size)
  }

  private companion object {
    // Any non-null value works — `AsyncCache.cache` is typed as `Map<K, Deferred<V>>` but
    // Java/Kotlin erasure lets us put anything into the raw map for the test. CMP doesn't read the
    // sentinel; we just need an entry to prove the clear removed it.
    private val SENTINEL_VALUE: Any = Any()
  }
}
