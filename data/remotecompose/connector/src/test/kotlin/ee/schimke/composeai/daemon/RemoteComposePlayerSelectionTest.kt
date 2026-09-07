package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the build-wide player selection every Remote Compose preview falls back to.
 *
 * The default is the load-bearing case: it is what issue #5259 changed, so a silent flip back to
 * the View-backed player would restore an unlabelled `RemoteComposePlayer` on every Remote Compose
 * preview at once.
 */
class RemoteComposePlayerSelectionTest {

  @Test
  fun `nothing selected draws with the embedded player`() {
    assertEquals(RemoteComposePlayerKind.EMBEDDED, RemoteComposePlayerSelection.DEFAULT)
    assertEquals(RemoteComposePlayerKind.EMBEDDED, RemoteComposePlayerSelection.resolve(null))
    assertEquals(RemoteComposePlayerKind.EMBEDDED, RemoteComposePlayerSelection.resolve(""))
    assertEquals(RemoteComposePlayerKind.EMBEDDED, RemoteComposePlayerSelection.resolve("  "))
  }

  @Test
  fun `every spelling the pipeline uses for these two players is accepted`() {
    // `?rcPlayer=cmp-android` in the viewer, `embedded` as the daemon player kind, `cmp` as the
    // Gradle property's own short form — a value copied from any of them must select what it looks
    // like it selects, and the same for `java` / `view`.
    for (cmp in listOf("cmp", "cmp-android", "embedded", "CMP-Android", " Embedded ")) {
      assertEquals(
        "'$cmp' should select the embedded player",
        RemoteComposePlayerKind.EMBEDDED,
        RemoteComposePlayerSelection.fromWire(cmp),
      )
    }
    for (view in listOf("view", "java", "JAVA", " View ")) {
      assertEquals(
        "'$view' should select the view player",
        RemoteComposePlayerKind.VIEW,
        RemoteComposePlayerSelection.fromWire(view),
      )
    }
  }

  @Test
  fun `the viewer-only lanes are not render-time players`() {
    // `js` and `cmp-wasm` replay in the browser; `cmp-jvm` renders in its own subprocess. None of
    // them is something this property can hand a Robolectric render, so they name no player rather
    // than quietly resolving to one.
    for (lane in listOf("js", "cmp-wasm", "cmp-jvm")) {
      assertNull(lane, RemoteComposePlayerSelection.fromWire(lane))
      assertEquals(
        RemoteComposePlayerSelection.DEFAULT,
        RemoteComposePlayerSelection.resolve(lane),
      )
    }
    assertNull(RemoteComposePlayerSelection.fromWire("nonsense"))
  }

  @Test
  fun `the property is the shared one the Gradle plugin forwards`() {
    // `:wear-preview-runtime` cannot depend on this module and spells the same literal in
    // `WearWidgetPreviewPlayer.PROPERTY`, pinned by its own test: one
    // `-PcomposePreview.rcPlayer=view` has to move widget previews and ordinary Remote Compose
    // previews together.
    assertEquals("composeai.render.rcPlayer", RemoteComposePlayerSelection.PROPERTY)
  }
}
