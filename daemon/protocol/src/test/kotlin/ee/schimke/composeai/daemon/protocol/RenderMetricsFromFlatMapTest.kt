package ee.schimke.composeai.daemon.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RenderMetrics.fromFlatMap] — the flat `Map<String, Long>` → structured
 * [RenderMetrics] translator B2.3 lands.
 *
 * Three cases the JSON-RPC server depends on:
 * - Happy path: all four B2.3 keys present → struct populated 1:1 with the map values.
 * - Partial map: at least one key missing → result is [RenderMetrics.FromFlatMapResult.PartialMap]
 *   with the missing-keys list. The wire layer logs a warn-level notification on this and emits
 *   `metrics: null` (no half-populated objects).
 * - Extras: unknown keys (e.g. `tookMs`, future host-private counters) are ignored — the four known
 *   keys still translate cleanly.
 */
class RenderMetricsFromFlatMapTest {

  @Test
  fun happy_path_all_four_keys_present_populate_struct() {
    val map: Map<String, Long> =
      mapOf(
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 42L,
        RenderMetrics.KEY_NATIVE_HEAP_MB to 17L,
        RenderMetrics.KEY_SANDBOX_AGE_RENDERS to 3L,
        RenderMetrics.KEY_SANDBOX_AGE_MS to 1234L,
      )
    val result = RenderMetrics.fromFlatMap(map)
    assertTrue(
      "happy path should produce Populated, got $result",
      result is RenderMetrics.FromFlatMapResult.Populated,
    )
    val populated = (result as RenderMetrics.FromFlatMapResult.Populated).metrics
    assertEquals(42L, populated.heapAfterGcMb)
    assertEquals(17L, populated.nativeHeapMb)
    assertEquals(3L, populated.sandboxAgeRenders)
    assertEquals(1234L, populated.sandboxAgeMs)
  }

  @Test
  fun null_source_returns_AbsentSource() {
    val result = RenderMetrics.fromFlatMap(null)
    assertTrue(
      "null source should produce AbsentSource, got $result",
      result is RenderMetrics.FromFlatMapResult.AbsentSource,
    )
  }

  @Test
  fun missing_one_key_returns_PartialMap_with_missing_key_listed() {
    val map: Map<String, Long> =
      mapOf(
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 1L,
        RenderMetrics.KEY_NATIVE_HEAP_MB to 2L,
        // Missing sandboxAgeRenders.
        RenderMetrics.KEY_SANDBOX_AGE_MS to 4L,
      )
    val result = RenderMetrics.fromFlatMap(map)
    assertTrue(
      "missing key should produce PartialMap, got $result",
      result is RenderMetrics.FromFlatMapResult.PartialMap,
    )
    val partial = result as RenderMetrics.FromFlatMapResult.PartialMap
    assertEquals(listOf(RenderMetrics.KEY_SANDBOX_AGE_RENDERS), partial.missingKeys)
  }

  @Test
  fun missing_multiple_keys_returns_PartialMap_with_all_missing_keys_listed() {
    val map: Map<String, Long> =
      mapOf(
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 1L
        // Three keys missing.
      )
    val result = RenderMetrics.fromFlatMap(map)
    assertTrue(
      "missing keys should produce PartialMap, got $result",
      result is RenderMetrics.FromFlatMapResult.PartialMap,
    )
    val partial = result as RenderMetrics.FromFlatMapResult.PartialMap
    assertEquals(
      listOf(
        RenderMetrics.KEY_NATIVE_HEAP_MB,
        RenderMetrics.KEY_SANDBOX_AGE_RENDERS,
        RenderMetrics.KEY_SANDBOX_AGE_MS,
      ),
      partial.missingKeys,
    )
  }

  @Test
  fun extra_unknown_keys_ignored_when_all_four_present() {
    val map: Map<String, Long> =
      mapOf(
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 11L,
        RenderMetrics.KEY_NATIVE_HEAP_MB to 22L,
        RenderMetrics.KEY_SANDBOX_AGE_RENDERS to 33L,
        RenderMetrics.KEY_SANDBOX_AGE_MS to 44L,
        "tookMs" to 999L,
        "future-private-counter" to 12345L,
      )
    val result = RenderMetrics.fromFlatMap(map)
    assertTrue(
      "extras shouldn't trigger PartialMap, got $result",
      result is RenderMetrics.FromFlatMapResult.Populated,
    )
    val populated = (result as RenderMetrics.FromFlatMapResult.Populated).metrics
    assertEquals(11L, populated.heapAfterGcMb)
    assertEquals(22L, populated.nativeHeapMb)
    assertEquals(33L, populated.sandboxAgeRenders)
    assertEquals(44L, populated.sandboxAgeMs)
  }

  @Test
  fun empty_map_returns_PartialMap_with_all_four_listed() {
    val result = RenderMetrics.fromFlatMap(emptyMap())
    assertTrue(
      "empty map should produce PartialMap, got $result",
      result is RenderMetrics.FromFlatMapResult.PartialMap,
    )
    val partial = result as RenderMetrics.FromFlatMapResult.PartialMap
    assertEquals(4, partial.missingKeys.size)
    assertTrue(
      partial.missingKeys.containsAll(
        listOf(
          RenderMetrics.KEY_HEAP_AFTER_GC_MB,
          RenderMetrics.KEY_NATIVE_HEAP_MB,
          RenderMetrics.KEY_SANDBOX_AGE_RENDERS,
          RenderMetrics.KEY_SANDBOX_AGE_MS,
        )
      )
    )
  }
}
