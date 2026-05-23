package ee.schimke.composeai.preview.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LauncherWidgetSizeTest {
  @Test
  fun `diagonal stops walk the example from the spec`() {
    // 1x1 → 4x2 under Diagonal must visit exactly 1x1, 2x1, 3x2, 4x2.
    val stops =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.Diagonal,
      )
    assertEquals(
      listOf(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(2, 1),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(4, 2),
      ),
      stops,
    )
  }

  @Test
  fun `width-first stops walk one axis to completion before the other`() {
    // 1x1 → 4x2 under WidthFirst — width drags first, then height.
    val stops =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.WidthFirst,
      )
    assertEquals(
      listOf(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(2, 1),
        LauncherWidgetSize(3, 1),
        LauncherWidgetSize(4, 1),
        LauncherWidgetSize(4, 2),
      ),
      stops,
    )
  }

  @Test
  fun `height-first stops walk height to completion before width`() {
    // 1x1 → 4x2 under HeightFirst — height drags first, then width.
    val stops =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.HeightFirst,
      )
    assertEquals(
      listOf(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(1, 2),
        LauncherWidgetSize(2, 2),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(4, 2),
      ),
      stops,
    )
  }

  @Test
  fun `default order is width-first`() {
    // No explicit order argument — should match WidthFirst.
    assertEquals(
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.WidthFirst,
      ),
      launcherWidgetStops(LauncherWidgetSize(1, 1), LauncherWidgetSize(4, 2)),
    )
  }

  @Test
  fun `zero-delta resize collapses to a single stop in every order`() {
    for (order in LauncherResizeOrder.entries) {
      assertEquals(
        listOf(LauncherWidgetSize(2, 3)),
        launcherWidgetStops(LauncherWidgetSize(2, 3), LauncherWidgetSize(2, 3), order),
      )
    }
  }

  @Test
  fun `pure-horizontal grow steps one cell at a time regardless of order`() {
    val expected =
      listOf(
        LauncherWidgetSize(1, 2),
        LauncherWidgetSize(2, 2),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(4, 2),
      )
    for (order in LauncherResizeOrder.entries) {
      assertEquals(
        expected,
        launcherWidgetStops(LauncherWidgetSize(1, 2), LauncherWidgetSize(4, 2), order),
      )
    }
  }

  @Test
  fun `pure-vertical grow steps one cell at a time regardless of order`() {
    val expected =
      listOf(
        LauncherWidgetSize(3, 1),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(3, 3),
        LauncherWidgetSize(3, 4),
      )
    for (order in LauncherResizeOrder.entries) {
      assertEquals(
        expected,
        launcherWidgetStops(LauncherWidgetSize(3, 1), LauncherWidgetSize(3, 4), order),
      )
    }
  }

  @Test
  fun `shrink walks the same path in reverse for every order`() {
    for (order in LauncherResizeOrder.entries) {
      val forward = launcherWidgetStops(LauncherWidgetSize(1, 1), LauncherWidgetSize(4, 2), order)
      val reverseOrder =
        when (order) {
          LauncherResizeOrder.Diagonal -> LauncherResizeOrder.Diagonal
          // The "first-axis" identity flips under reversal: WidthFirst going 1×1→4×2 ends at
          // height; reversing that yields a HeightFirst-shaped path back to the start.
          LauncherResizeOrder.WidthFirst -> LauncherResizeOrder.HeightFirst
          LauncherResizeOrder.HeightFirst -> LauncherResizeOrder.WidthFirst
        }
      val reverse =
        launcherWidgetStops(LauncherWidgetSize(4, 2), LauncherWidgetSize(1, 1), reverseOrder)
      assertEquals(forward.reversed(), reverse)
    }
  }

  @Test
  fun `coerceIn clamps each axis independently`() {
    val min = LauncherWidgetSize(1, 3)
    val max = LauncherWidgetSize(4, 5)
    assertEquals(LauncherWidgetSize(4, 3), LauncherWidgetSize(7, 1).coerceIn(min, max))
    assertEquals(LauncherWidgetSize(1, 5), LauncherWidgetSize(0, 9).coerceIn(min, max))
    assertEquals(LauncherWidgetSize(2, 4), LauncherWidgetSize(2, 4).coerceIn(min, max))
  }

  @Test
  fun `coerceIn rejects inverted bounds`() {
    val min = LauncherWidgetSize(4, 4)
    val max = LauncherWidgetSize(1, 1)
    assertThrows(IllegalArgumentException::class.java) {
      LauncherWidgetSize(2, 2).coerceIn(min, max)
    }
  }

  @Test
  fun `negative cell counts are rejected at construction`() {
    // Zero is permitted (it expresses "below min" and gets clamped); negative is not.
    assertThrows(IllegalArgumentException::class.java) { LauncherWidgetSize(-1, 1) }
    assertThrows(IllegalArgumentException::class.java) { LauncherWidgetSize(1, -2) }
  }
}
