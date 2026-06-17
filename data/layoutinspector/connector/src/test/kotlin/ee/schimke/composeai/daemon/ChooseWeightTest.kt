package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the CSS / Compose font-weight matching rule used to pick the resolved face for a
 * `FontListFontFamily` (issue #1934) — so the typography projection reports the face Compose
 * actually draws, not the first one declared.
 */
class ChooseWeightTest {

  @Test
  fun exact_weight_wins() {
    assertEquals(700, chooseWeight(listOf(300, 700), 700))
    assertEquals(400, chooseWeight(listOf(400, 700), 400))
  }

  @Test
  fun above_500_prefers_nearest_heavier_then_lighter() {
    // The regression Codex flagged: weights 300/700, requesting SemiBold (600) must resolve to 700
    // (the nearest heavier face Compose picks), not 300 (declaration order).
    assertEquals(700, chooseWeight(listOf(300, 700), 600))
    // No heavier face available → fall back to the nearest lighter one.
    assertEquals(300, chooseWeight(listOf(100, 300), 600))
  }

  @Test
  fun below_400_prefers_nearest_lighter_then_heavier() {
    assertEquals(300, chooseWeight(listOf(300, 700), 350))
    assertEquals(700, chooseWeight(listOf(700, 900), 350))
  }

  @Test
  fun in_400_to_500_prefers_range_then_lighter_then_heavier() {
    // [target, 500] ascending first.
    assertEquals(500, chooseWeight(listOf(300, 500, 700), 450))
    // Nothing in [target, 500] → nearest lighter.
    assertEquals(300, chooseWeight(listOf(300, 700), 450))
    // Nothing in [target, 500] and nothing lighter → nearest heavier (> 500).
    assertEquals(700, chooseWeight(listOf(700, 900), 450))
  }

  @Test
  fun empty_list_resolves_to_null() {
    assertNull(chooseWeight(emptyList(), 400))
  }
}
