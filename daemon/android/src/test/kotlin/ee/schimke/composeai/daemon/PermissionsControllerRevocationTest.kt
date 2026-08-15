package ee.schimke.composeai.daemon

import android.content.pm.PackageManager
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Revocation behaviour of [PermissionsController]'s Robolectric grant sync — the leg
 * [PermissionsControllerTest] explicitly leaves alone because it needs a real `ShadowApplication`.
 *
 * These assert against the **platform** read (`Context.checkSelfPermission`), not the controller's
 * own snapshot map, because the snapshot was never the thing that leaked: `grantsState` has always
 * been replaced wholesale, while the shadow only ever heard about the names passed to
 * `denyPermissions(vararg String)`. A grant applied by one capture therefore survived into the next
 * one inside the same sandbox, and a preview carrying no `@PermissionPreview` at all — whose
 * extension is never constructed, so nothing runs to clear anything — could render a granted branch
 * it never asked for. That is a capture showing a state it did not establish, the defect class
 * issue #3676 exists to remove, so it gets pinned at the platform boundary where a consumer would
 * actually observe it.
 */
// Pinned to SDK 35, matching `SandboxHoldingRunner` and the other in-sandbox tests in this module:
// SDK 36 refuses to start on the JDK 17 toolchain the build pins, and nothing here is SDK-specific.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionsControllerRevocationTest {

  private val camera = "android.permission.CAMERA"
  private val record = "android.permission.RECORD_AUDIO"

  @After fun tearDown() = PermissionsController.resetForNewSession()

  private fun platformCheck(permission: String): Int =
    RuntimeEnvironment.getApplication().checkSelfPermission(permission)

  private fun grant(vararg permissions: String) =
    PermissionsController.set(
      PermissionsOverride(
        grants = permissions.associateWith { PermissionGrantStateOverride.GRANTED }
      )
    )

  @Test
  fun `clearing the override revokes a previously granted permission`() {
    grant(camera)
    assertEquals(PackageManager.PERMISSION_GRANTED, platformCheck(camera))

    // What the next un-annotated capture in this sandbox effectively sees. Before the union-deny,
    // `set(null)` passed an empty array to `denyPermissions` and revoked nothing.
    PermissionsController.set(null)
    assertEquals(PackageManager.PERMISSION_DENIED, platformCheck(camera))
  }

  @Test
  fun `a permission absent from a later override is revoked`() {
    grant(camera)
    assertEquals(PackageManager.PERMISSION_GRANTED, platformCheck(camera))

    // The documented contract: the map is exhaustive, so dropping CAMERA revokes it rather than
    // merging with what came before.
    grant(record)
    assertEquals(PackageManager.PERMISSION_DENIED, platformCheck(camera))
    assertEquals(PackageManager.PERMISSION_GRANTED, platformCheck(record))
  }

  @Test
  fun `an explicit denial still revokes a previously granted permission`() {
    grant(camera)
    PermissionsController.set(
      PermissionsOverride(grants = mapOf(camera to PermissionGrantStateOverride.DENIED))
    )
    assertEquals(PackageManager.PERMISSION_DENIED, platformCheck(camera))
  }

  @Test
  fun `session reset revokes everything the session granted`() {
    grant(camera, record)
    PermissionsController.resetForNewSession()
    assertEquals(PackageManager.PERMISSION_DENIED, platformCheck(camera))
    assertEquals(PackageManager.PERMISSION_DENIED, platformCheck(record))
  }
}
