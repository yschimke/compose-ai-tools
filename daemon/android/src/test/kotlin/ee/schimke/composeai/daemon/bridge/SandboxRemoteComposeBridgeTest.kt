package ee.schimke.composeai.daemon.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-preview scoping + ordering coverage for [SandboxRemoteComposeBridge], the Remote Compose
 * sibling of [SandboxPermissionsBridge].
 *
 * The bridge is a single JVM-wide singleton shared across every Robolectric sandbox (it lives in the
 * do-not-acquire `ee.schimke.composeai.daemon.bridge` package). Keying its map by previewId keeps two
 * concurrent previews — each in its own sandbox, each writing into the same singleton — from
 * cross-polluting each other's host-side declaration snapshot. Declarations cross as JSON strings, so
 * these tests use opaque strings as stand-ins for the serialised `RemoteComposeKnobDeclaration`.
 *
 * Plain JUnit (no `@RunWith(SandboxHoldingRunner::class)`) — the bridge is plain host-side state; its
 * cross-classloader visibility is exercised end-to-end by the Remote Compose Robolectric render path.
 */
class SandboxRemoteComposeBridgeTest {

  @After fun tearDown() = SandboxRemoteComposeBridge.resetAll()

  @Test
  fun `two concurrent previews see only their own declarations`() {
    SandboxRemoteComposeBridge.record("preview-a", "label", "{a-label}")
    SandboxRemoteComposeBridge.record("preview-b", "shaderColor", "{b-color}")
    SandboxRemoteComposeBridge.record("preview-a", "score", "{a-score}")

    assertEquals(
      "preview-a must see only its own declarations",
      listOf("{a-label}", "{a-score}"),
      SandboxRemoteComposeBridge.snapshot("preview-a").toList(),
    )
    assertEquals(
      "preview-b must see only its own declarations",
      listOf("{b-color}"),
      SandboxRemoteComposeBridge.snapshot("preview-b").toList(),
    )
  }

  @Test
  fun `snapshot preserves insertion order and replaces a re-declared name in place`() {
    SandboxRemoteComposeBridge.record("preview-a", "label", "{v1}")
    SandboxRemoteComposeBridge.record("preview-a", "score", "{score}")
    // A recomposition re-declares "label" with a fresh JSON — replaces in place, keeps position.
    SandboxRemoteComposeBridge.record("preview-a", "label", "{v2}")

    assertEquals(
      listOf("{v2}", "{score}"),
      SandboxRemoteComposeBridge.snapshot("preview-a").toList(),
    )
  }

  @Test
  fun `snapshot of an unseen preview is empty`() {
    SandboxRemoteComposeBridge.record("preview-a", "label", "{a}")

    assertEquals(emptyList<String>(), SandboxRemoteComposeBridge.snapshot("never-rendered").toList())
  }

  @Test
  fun `reset clears only the named preview, leaving concurrent previews intact`() {
    SandboxRemoteComposeBridge.record("preview-a", "label", "{a}")
    SandboxRemoteComposeBridge.record("preview-b", "color", "{b}")

    SandboxRemoteComposeBridge.reset("preview-a")

    assertEquals(
      "preview-a's scope is cleared",
      emptyList<String>(),
      SandboxRemoteComposeBridge.snapshot("preview-a").toList(),
    )
    assertEquals(
      "closing preview-a's session must not wipe a concurrently-held preview-b",
      listOf("{b}"),
      SandboxRemoteComposeBridge.snapshot("preview-b").toList(),
    )
  }

  @Test
  fun `resetAll clears every preview scope`() {
    SandboxRemoteComposeBridge.record("preview-a", "label", "{a}")
    SandboxRemoteComposeBridge.record("preview-b", "color", "{b}")

    SandboxRemoteComposeBridge.resetAll()

    assertEquals(emptyList<String>(), SandboxRemoteComposeBridge.snapshot("preview-a").toList())
    assertEquals(emptyList<String>(), SandboxRemoteComposeBridge.snapshot("preview-b").toList())
  }

  @Test
  fun `no-preview renders share the dedicated sentinel scope`() {
    SandboxRemoteComposeBridge.record(SandboxRemoteComposeBridge.NO_PREVIEW_SCOPE, "label", "{s}")

    assertEquals(
      listOf("{s}"),
      SandboxRemoteComposeBridge.snapshot(SandboxRemoteComposeBridge.NO_PREVIEW_SCOPE).toList(),
    )
    // A real previewId must not pick up the sentinel scope's declarations.
    assertEquals(emptyList<String>(), SandboxRemoteComposeBridge.snapshot("preview-a").toList())
  }
}
