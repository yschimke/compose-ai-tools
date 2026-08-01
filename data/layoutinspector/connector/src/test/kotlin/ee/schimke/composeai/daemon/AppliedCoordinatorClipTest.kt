package ee.schimke.composeai.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the runtime coordinator clip signal used for scroll viewports in figma-svg (#3056). */
class AppliedCoordinatorClipTest {

  private open class CoordinatorFields(@JvmField val isClipping: Boolean)

  /** The real field is declared on `NodeCoordinator`, above the concrete coordinator subclass. */
  private class ConcreteCoordinator(clipping: Boolean) : CoordinatorFields(clipping)

  @Test
  fun `reads an applied runtime clip from a coordinator superclass`() {
    assertTrue(ModifierTokenResolver.appliedClipsContent(ConcreteCoordinator(true)))
  }

  @Test
  fun `does not mark a coordinator that did not clip`() {
    assertFalse(ModifierTokenResolver.appliedClipsContent(ConcreteCoordinator(false)))
  }

  @Test
  fun `is safely false when an older coordinator has no clip field`() {
    assertFalse(ModifierTokenResolver.appliedClipsContent(Any()))
  }
}
