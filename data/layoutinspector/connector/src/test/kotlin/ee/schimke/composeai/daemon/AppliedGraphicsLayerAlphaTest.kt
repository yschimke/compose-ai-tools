package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the per-coordinator graphics-layer alpha path used by the figma-svg exporter (#2615). */
class AppliedGraphicsLayerAlphaTest {

  private open class CoordinatorFields(
    @JvmField val layer: Any?,
    @JvmField val wasLayerBlockInvoked: Boolean,
    @JvmField val lastLayerAlpha: Float,
  )

  /** The real field is declared on `NodeCoordinator`, above the concrete coordinator subclass. */
  private class ConcreteCoordinator(layer: Any?, invoked: Boolean, alpha: Float) :
    CoordinatorFields(layer, invoked, alpha)

  @Test
  fun `reads the alpha applied by this coordinator's real layer block`() {
    val coordinates = ConcreteCoordinator(layer = Any(), invoked = true, alpha = 1f)

    assertEquals(1f, ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates)!!, 0.001f)
  }

  @Test
  fun `preserves an intentional transparent applied layer`() {
    val coordinates = ConcreteCoordinator(layer = Any(), invoked = true, alpha = 0f)

    assertEquals(0f, ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates)!!, 0.001f)
  }

  @Test
  fun `does not expose the coordinator sentinel before a layer exists`() {
    val coordinates = ConcreteCoordinator(layer = null, invoked = false, alpha = 0.8f)

    assertNull(ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates))
  }

  @Test
  fun `does not expose the coordinator sentinel before the block runs`() {
    val coordinates = ConcreteCoordinator(layer = Any(), invoked = false, alpha = 0.8f)

    assertNull(ModifierTokenResolver.appliedGraphicsLayerAlpha(coordinates))
  }
}
