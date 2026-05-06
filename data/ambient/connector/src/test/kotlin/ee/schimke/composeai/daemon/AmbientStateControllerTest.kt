package ee.schimke.composeai.daemon

import androidx.wear.ambient.AmbientLifecycleObserver
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract of the process-static [AmbientStateController]. The shadow + the recording
 * dispatch observer both consult this object, so we exercise the full state-machine here without
 * needing a Robolectric sandbox.
 */
class AmbientStateControllerTest {

  @After fun tearDown() = AmbientStateController.resetForNewSession()

  @Test
  fun `initial state is inactive`() {
    AmbientStateController.resetForNewSession()
    assertEquals(AmbientStateOverride.INACTIVE, AmbientStateController.current())
  }

  @Test
  fun `set ambient drives onEnterAmbient on registered callbacks`() {
    val cb = RecordingCallback()
    AmbientStateController.registerCallback(cb)
    try {
      AmbientStateController.set(
        AmbientOverride(
          state = AmbientStateOverride.AMBIENT,
          burnInProtectionRequired = true,
          deviceHasLowBitAmbient = true,
        )
      )
      assertEquals(AmbientStateOverride.AMBIENT, AmbientStateController.current())
      assertEquals(1, cb.enterCount)
      assertEquals(true, cb.lastDetails?.burnInProtectionRequired)
      assertEquals(true, cb.lastDetails?.deviceHasLowBitAmbient)
      assertEquals(0, cb.exitCount)
    } finally {
      AmbientStateController.unregisterCallback(cb)
    }
  }

  @Test
  fun `set null clears state and fires onExitAmbient`() {
    val cb = RecordingCallback()
    AmbientStateController.registerCallback(cb)
    try {
      AmbientStateController.set(AmbientOverride(state = AmbientStateOverride.AMBIENT))
      AmbientStateController.set(null)
      assertEquals(AmbientStateOverride.INACTIVE, AmbientStateController.current())
      assertEquals(1, cb.enterCount)
      assertEquals(1, cb.exitCount)
    } finally {
      AmbientStateController.unregisterCallback(cb)
    }
  }

  @Test
  fun `late-registered callback is primed with the current ambient state`() {
    AmbientStateController.set(AmbientOverride(state = AmbientStateOverride.AMBIENT))
    val cb = RecordingCallback()
    AmbientStateController.registerCallback(cb)
    try {
      assertEquals("late joiner sees onEnterAmbient", 1, cb.enterCount)
    } finally {
      AmbientStateController.unregisterCallback(cb)
    }
  }

  @Test
  fun `notifyUserInput flips to interactive and idle timer restores the override`() {
    val cb = RecordingCallback()
    AmbientStateController.registerCallback(cb)
    try {
      AmbientStateController.set(
        AmbientOverride(state = AmbientStateOverride.AMBIENT, idleTimeoutMs = 25L)
      )
      assertEquals(1, cb.enterCount)
      AmbientStateController.notifyUserInput(tNanos = 0L)
      assertEquals(AmbientStateOverride.INTERACTIVE, AmbientStateController.current())
      assertEquals(1, cb.exitCount)
      // Wait for idle restoration.
      val deadline = System.nanoTime() + 1_000_000_000L
      while (
        AmbientStateController.current() != AmbientStateOverride.AMBIENT &&
          System.nanoTime() < deadline
      ) {
        Thread.sleep(5)
      }
      assertEquals(AmbientStateOverride.AMBIENT, AmbientStateController.current())
      assertEquals("idle restoration re-fires onEnterAmbient", 2, cb.enterCount)
    } finally {
      AmbientStateController.unregisterCallback(cb)
    }
  }

  @Test
  fun `notifyUserInput is a no-op when no override is active`() {
    val cb = RecordingCallback()
    AmbientStateController.registerCallback(cb)
    try {
      AmbientStateController.notifyUserInput(tNanos = 0L)
      assertEquals(AmbientStateOverride.INACTIVE, AmbientStateController.current())
      assertEquals(0, cb.enterCount)
      assertEquals(0, cb.exitCount)
    } finally {
      AmbientStateController.unregisterCallback(cb)
    }
  }

  @Test
  fun `set replaces the override and cancels any pending idle restoration`() {
    val cb = RecordingCallback()
    AmbientStateController.registerCallback(cb)
    try {
      AmbientStateController.set(
        AmbientOverride(state = AmbientStateOverride.AMBIENT, idleTimeoutMs = 50L)
      )
      AmbientStateController.notifyUserInput(tNanos = 0L)
      // Replace before the idle restoration would fire.
      AmbientStateController.set(AmbientOverride(state = AmbientStateOverride.INTERACTIVE))
      Thread.sleep(80)
      assertEquals(AmbientStateOverride.INTERACTIVE, AmbientStateController.current())
      assertEquals("set replaced the override; original idle restoration must not fire", 1, cb.enterCount)
    } finally {
      AmbientStateController.unregisterCallback(cb)
    }
  }

  @Test
  fun `currentOverride exposes the active override snapshot`() {
    val ov = AmbientOverride(state = AmbientStateOverride.AMBIENT, updateTimeMillis = 42L)
    AmbientStateController.set(ov)
    assertEquals(ov, AmbientStateController.currentOverride())
    AmbientStateController.set(null)
    assertEquals(null, AmbientStateController.currentOverride())
  }

  private class RecordingCallback : AmbientLifecycleObserver.AmbientLifecycleCallback {
    var enterCount: Int = 0
    var exitCount: Int = 0
    var updateCount: Int = 0
    var lastDetails: AmbientLifecycleObserver.AmbientDetails? = null

    override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
      enterCount++
      lastDetails = ambientDetails
    }

    override fun onUpdateAmbient() {
      updateCount++
    }

    override fun onExitAmbient() {
      exitCount++
    }
  }

  @Test
  fun `wake kinds list mirrors AOSP's activating gestures`() {
    // Sanity: dispatch observer wakes only on the gestures Wear OS itself wakes on.
    assertTrue("input.click is an activating gesture",
      "input.click" in AmbientInputDispatchObserver.WAKE_KINDS)
    assertTrue("input.pointerDown is an activating gesture",
      "input.pointerDown" in AmbientInputDispatchObserver.WAKE_KINDS)
    assertTrue("input.rotaryScroll is an activating gesture",
      "input.rotaryScroll" in AmbientInputDispatchObserver.WAKE_KINDS)
    assertFalse("input.pointerMove is not activating",
      "input.pointerMove" in AmbientInputDispatchObserver.WAKE_KINDS)
    assertFalse("input.pointerUp is not activating",
      "input.pointerUp" in AmbientInputDispatchObserver.WAKE_KINDS)
  }
}
