package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-only checks for [PermissionsController]. The Robolectric grant-sync path is exercised by
 * `:daemon:android`'s in-sandbox tests where `ShadowApplication` actually exists; here we cover the
 * snapshot-state behaviour the around-composable + data-product registry rely on.
 */
class PermissionsControllerTest {

  @After fun tearDown() = PermissionsController.resetForNewSession()

  @Test
  fun `controller starts with empty grant map and queried list`() {
    PermissionsController.resetForNewSession()
    assertTrue(PermissionsController.grants.value.isEmpty())
    assertTrue(PermissionsController.queried.value.isEmpty())
  }

  @Test
  fun `set replaces the grant map`() {
    PermissionsController.set(
      PermissionsOverride(
        grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
      )
    )
    assertEquals(
      PermissionGrantStateOverride.GRANTED,
      PermissionsController.grantFor("android.permission.CAMERA"),
    )

    // A subsequent override that drops CAMERA revokes it — no merge with previous state.
    PermissionsController.set(
      PermissionsOverride(
        grants = mapOf("android.permission.RECORD_AUDIO" to PermissionGrantStateOverride.DENIED)
      )
    )
    assertNull(PermissionsController.grantFor("android.permission.CAMERA"))
    assertEquals(
      PermissionGrantStateOverride.DENIED,
      PermissionsController.grantFor("android.permission.RECORD_AUDIO"),
    )
  }

  @Test
  fun `set with null clears the override`() {
    PermissionsController.set(
      PermissionsOverride(
        grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
      )
    )
    PermissionsController.set(null)
    assertTrue(PermissionsController.grants.value.isEmpty())
  }

  @Test
  fun `recordQuery preserves insertion order and de-duplicates`() {
    PermissionsController.recordQuery("android.permission.CAMERA")
    PermissionsController.recordQuery("android.permission.RECORD_AUDIO")
    PermissionsController.recordQuery("android.permission.CAMERA") // duplicate

    assertEquals(
      listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
      PermissionsController.queried.value,
    )
  }

  @Test
  fun `addChangeListener fires on every set transition`() {
    var fires = 0
    val unregister = PermissionsController.addChangeListener { fires += 1 }
    try {
      PermissionsController.set(
        PermissionsOverride(
          grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
        )
      )
      PermissionsController.set(null)
      assertEquals(2, fires)
    } finally {
      unregister()
    }
  }

  @Test
  fun `resetForNewSession drops grants and queried`() {
    PermissionsController.set(
      PermissionsOverride(
        grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
      )
    )
    PermissionsController.recordQuery("android.permission.CAMERA")
    PermissionsController.resetForNewSession()
    assertTrue(PermissionsController.grants.value.isEmpty())
    assertTrue(PermissionsController.queried.value.isEmpty())
  }
}
