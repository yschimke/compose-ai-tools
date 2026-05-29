package ee.schimke.composeai.daemon.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-preview scoping coverage for [SandboxPermissionsBridge] (issue #1593).
 *
 * The bridge is a single JVM-wide singleton shared across every Robolectric sandbox (it lives in
 * the do-not-acquire `ee.schimke.composeai.daemon.bridge` package). Before the fix it keyed its
 * query map by permission name alone, so two concurrent previews — each in its own sandbox, each
 * writing into the same singleton — cross-polluted: the host's `compose/permissions` readback for
 * preview A could surface a permission preview B queried. These tests pin that each previewId owns
 * an isolated query set, mirroring `SandboxRecompositionBridge`'s `(previewId)` keying.
 *
 * Plain JUnit (no `@RunWith(SandboxHoldingRunner::class)`) — the bridge is plain host-side state;
 * its cross-classloader visibility is exercised end-to-end by `PermissionsDataFetchE2ETest`.
 */
class SandboxPermissionsBridgeTest {

  @After fun tearDown() = SandboxPermissionsBridge.resetAll()

  @Test
  fun `two concurrent previews see only their own queries`() {
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.CAMERA")
    SandboxPermissionsBridge.recordQuery("preview-b", "android.permission.ACCESS_FINE_LOCATION")
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.RECORD_AUDIO")

    assertEquals(
      "preview-a must see only its own queries",
      listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
      SandboxPermissionsBridge.snapshot("preview-a").toList(),
    )
    assertEquals(
      "preview-b must see only its own queries",
      listOf("android.permission.ACCESS_FINE_LOCATION"),
      SandboxPermissionsBridge.snapshot("preview-b").toList(),
    )
  }

  @Test
  fun `snapshot preserves insertion order and de-duplicates within a preview`() {
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.CAMERA")
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.RECORD_AUDIO")
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.CAMERA") // duplicate

    assertEquals(
      listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
      SandboxPermissionsBridge.snapshot("preview-a").toList(),
    )
  }

  @Test
  fun `snapshot of an unseen preview is empty`() {
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.CAMERA")

    assertEquals(emptyList<String>(), SandboxPermissionsBridge.snapshot("never-rendered").toList())
  }

  @Test
  fun `reset clears only the named preview, leaving concurrent previews intact`() {
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.CAMERA")
    SandboxPermissionsBridge.recordQuery("preview-b", "android.permission.ACCESS_FINE_LOCATION")

    SandboxPermissionsBridge.reset("preview-a")

    assertEquals(
      "preview-a's scope is cleared",
      emptyList<String>(),
      SandboxPermissionsBridge.snapshot("preview-a").toList(),
    )
    assertEquals(
      "closing preview-a's session must not wipe a concurrently-held preview-b",
      listOf("android.permission.ACCESS_FINE_LOCATION"),
      SandboxPermissionsBridge.snapshot("preview-b").toList(),
    )
  }

  @Test
  fun `resetAll clears every preview scope`() {
    SandboxPermissionsBridge.recordQuery("preview-a", "android.permission.CAMERA")
    SandboxPermissionsBridge.recordQuery("preview-b", "android.permission.ACCESS_FINE_LOCATION")

    SandboxPermissionsBridge.resetAll()

    assertEquals(emptyList<String>(), SandboxPermissionsBridge.snapshot("preview-a").toList())
    assertEquals(emptyList<String>(), SandboxPermissionsBridge.snapshot("preview-b").toList())
  }

  @Test
  fun `no-preview renders share the dedicated sentinel scope`() {
    SandboxPermissionsBridge.recordQuery(
      SandboxPermissionsBridge.NO_PREVIEW_SCOPE,
      "android.permission.CAMERA",
    )

    assertEquals(
      listOf("android.permission.CAMERA"),
      SandboxPermissionsBridge.snapshot(SandboxPermissionsBridge.NO_PREVIEW_SCOPE).toList(),
    )
    // A real previewId must not pick up the sentinel scope's queries.
    assertEquals(emptyList<String>(), SandboxPermissionsBridge.snapshot("preview-a").toList())
  }
}
