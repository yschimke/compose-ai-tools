package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit coverage for the process-static [GestureStateController] — register / invoke / snapshot /
 * override-application semantics that back the `compose/gestures` data product. Resets around each
 * test since the controller is a singleton.
 */
class GestureStateControllerTest {

  @Before fun reset() = GestureStateController.resetForNewSession()

  @After fun tearDown() = GestureStateController.resetForNewSession()

  @Test
  fun `register surfaces handlers in snapshot`() {
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = true) {}
    GestureStateController.register(GestureKindOverride.DISMISS, "Back", hintAvailable = false) {}

    val snap = GestureStateController.snapshot()
    assertEquals(2, snap.registered.size)
    assertEquals("primary", snap.registered[0].type)
    assertEquals("Play", snap.registered[0].label)
    assertTrue(snap.registered[0].hintAvailable)
    assertEquals("dismiss", snap.registered[1].type)
    assertFalse(snap.registered[1].hintAvailable)
  }

  @Test
  fun `duplicate registration replaces prior entry`() {
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = false) {}
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = true) {}
    val snap = GestureStateController.snapshot()
    assertEquals(1, snap.registered.size)
    assertTrue(snap.registered.single().hintAvailable)
  }

  @Test
  fun `unregister drops the handler`() {
    GestureStateController.register(GestureKindOverride.SCROLL, "Scroll", hintAvailable = true) {}
    GestureStateController.unregister(GestureKindOverride.SCROLL, "Scroll")
    assertTrue(GestureStateController.snapshot().registered.isEmpty())
  }

  @Test
  fun `invoke runs matching handler and records lastInvoked`() {
    var primaryFired = 0
    var dismissFired = 0
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = true) {
      primaryFired++
    }
    GestureStateController.register(GestureKindOverride.DISMISS, "Back", hintAvailable = true) {
      dismissFired++
    }

    val fired = GestureStateController.invoke(GestureKindOverride.PRIMARY)
    assertEquals(1, fired)
    assertEquals(1, primaryFired)
    assertEquals(0, dismissFired)
    assertEquals("Play", GestureStateController.snapshot().lastInvoked)
  }

  @Test
  fun `invoke with label scopes to a single handler`() {
    var aFired = 0
    var bFired = 0
    GestureStateController.register(GestureKindOverride.PRIMARY, "A", hintAvailable = true) { aFired++ }
    GestureStateController.register(GestureKindOverride.PRIMARY, "B", hintAvailable = true) { bFired++ }

    GestureStateController.invoke(GestureKindOverride.PRIMARY, label = "B")
    assertEquals(0, aFired)
    assertEquals(1, bFired)
    assertEquals("B", GestureStateController.snapshot().lastInvoked)
  }

  @Test
  fun `invoke with no matching handler fires nothing`() {
    val fired = GestureStateController.invoke(GestureKindOverride.PAGE)
    assertEquals(0, fired)
    assertNull(GestureStateController.snapshot().lastInvoked)
  }

  @Test
  fun `set applies enabled and showHints, null restores defaults`() {
    GestureStateController.set(GestureOverride(enabled = false, showHints = true))
    assertFalse(GestureStateController.enabled())
    assertTrue(GestureStateController.hintsShownState.value)
    assertTrue(GestureStateController.snapshot().hintsShown)

    GestureStateController.set(null)
    assertTrue(GestureStateController.enabled())
    assertFalse(GestureStateController.hintsShownState.value)
    assertFalse(GestureStateController.snapshot().hintsShown)
  }

  @Test
  fun `resetForNewSession clears everything`() {
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = true) {}
    GestureStateController.invoke(GestureKindOverride.PRIMARY)
    GestureStateController.set(GestureOverride(showHints = true))

    GestureStateController.resetForNewSession()

    val snap = GestureStateController.snapshot()
    assertTrue(snap.registered.isEmpty())
    assertNull(snap.lastInvoked)
    assertFalse(snap.hintsShown)
    assertTrue(snap.enabled)
  }
}
