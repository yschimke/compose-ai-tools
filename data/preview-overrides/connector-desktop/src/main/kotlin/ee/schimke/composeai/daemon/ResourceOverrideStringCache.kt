package ee.schimke.composeai.daemon

/**
 * Reflective shim that clears Compose Multiplatform Resources' process-wide string-item cache so the
 * wrapped [org.jetbrains.compose.resources.LocalResourceReader] is actually re-invoked for a
 * `stringResource(...)` lookup, rather than served the value a prior render already cached.
 *
 * **Why a second copy.** The pseudolocale desktop connector has the same need and its own
 * `PseudolocaleResourceCache`; that one is `internal` to `:data-pseudolocale-connector-desktop`, and
 * the resource-override feature is intentionally decoupled from pseudolocale, so this connector
 * carries its own equivalent rather than depending on that module. Both reach the same CMP internal
 * (`StringResourcesUtilsKt.stringItemsCache`, an `AsyncCache<String, StringItem>` keyed by
 * `path/offset-size`) via reflection; if a future CMP version reshapes it, both degrade the same way
 * (the reader stops being re-invoked, and an already-cached string is returned unmodified).
 *
 * Clearing on every render is required because [DesktopHost] reuses the JVM / classloader across
 * requests: without it, the first render of a given string in the JVM warms the cache and every
 * later render — including one carrying a fresh `namedOverrides` seed — reads the cached original,
 * so the substitution silently no-ops.
 */
internal object ResourceOverrideStringCache {

  private const val UTILS_CLASS = "org.jetbrains.compose.resources.StringResourcesUtilsKt"
  private const val CACHE_FIELD = "stringItemsCache"
  private const val ASYNC_CACHE_MAP_FIELD = "cache"

  /**
   * Clear the cache. Returns `true` on success, `false` if reflection couldn't find it (CMP version
   * drift). Never throws; safe to call from any thread (the underlying `Map.clear()` is a single
   * instruction and the renderer issues renders sequentially).
   */
  fun clearBestEffort(): Boolean {
    return try {
      val utilsClass = Class.forName(UTILS_CLASS)
      val cacheField = utilsClass.getDeclaredField(CACHE_FIELD).apply { isAccessible = true }
      val asyncCache = cacheField.get(null) ?: return false
      val mapField =
        asyncCache.javaClass.getDeclaredField(ASYNC_CACHE_MAP_FIELD).apply { isAccessible = true }
      val map = mapField.get(asyncCache) as? MutableMap<*, *> ?: return false
      map.clear()
      true
    } catch (_: ReflectiveOperationException) {
      false
    } catch (_: ClassCastException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }
}
