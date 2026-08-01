package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the per-coordinator graphics-layer alpha path used by the figma-svg exporter (#2615). */
class AppliedGraphicsLayerAlphaTest {

  private open class CoordinatorFields(
    @JvmField val layer: Any?,
    @JvmField val lastLayerAlpha: Float,
  )

  /** Mirrors Compose 1.9.5, where the coordinator has no `wasLayerBlockInvoked` field. */
  private class ConcreteCoordinator(layer: Any?, alpha: Float) : CoordinatorFields(layer, alpha)

  @Test
  fun `reads applied alpha from a Compose 1_9 coordinator`() {
    val coordinates = ConcreteCoordinator(layer = Any(), alpha = 1f)

    assertEquals(1f, ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates)!!, 0.001f)
  }

  @Test
  fun `preserves an intentional transparent applied layer`() {
    val coordinates = ConcreteCoordinator(layer = Any(), alpha = 0f)

    assertEquals(0f, ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates)!!, 0.001f)
  }

  @Test
  fun `does not expose the coordinator sentinel before a layer exists`() {
    val coordinates = ConcreteCoordinator(layer = null, alpha = 0.8f)

    assertNull(ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates))
  }
}
