package ee.schimke.composeai.wear.preview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the player selection `CapturingWearWidgetPreview` reads out of
 * [WearWidgetPreviewPlayer.PROPERTY].
 *
 * The default is the load-bearing case: it is what issue #5259 changed, so a silent flip back to
 * the View-backed lane would restore an unlabelled `RemoteComposePlayer` on every widget preview.
 */
class WearWidgetPreviewPlayerTest {

  @Test
  fun `nothing selected draws with the CMP player`() {
    assertThat(WearWidgetPreviewPlayer.DEFAULT).isEqualTo(WearWidgetPreviewPlayer.CMP)
    assertThat(WearWidgetPreviewPlayer.resolve(null)).isEqualTo(WearWidgetPreviewPlayer.CMP)
    assertThat(WearWidgetPreviewPlayer.resolve("")).isEqualTo(WearWidgetPreviewPlayer.CMP)
    assertThat(WearWidgetPreviewPlayer.resolve("   ")).isEqualTo(WearWidgetPreviewPlayer.CMP)
  }

  @Test
  fun `each lane answers to its own wire id`() {
    assertThat(WearWidgetPreviewPlayer.fromWire("cmp")).isEqualTo(WearWidgetPreviewPlayer.CMP)
    assertThat(WearWidgetPreviewPlayer.fromWire("view")).isEqualTo(WearWidgetPreviewPlayer.VIEW)
    // Canonical implementation names, kept in lockstep with `RemoteComposePlayerSelection`.
    assertThat(WearWidgetPreviewPlayer.fromWire("androidx-embedded"))
      .isEqualTo(WearWidgetPreviewPlayer.CMP)
    assertThat(WearWidgetPreviewPlayer.fromWire("androidx-view"))
      .isEqualTo(WearWidgetPreviewPlayer.VIEW)
  }

  @Test
  fun `the pipeline's other spellings of the same two players are accepted`() {
    // `?rcPlayer=cmp-android` / `RemoteComposePlayerKind.EMBEDDED` and `?rcPlayer=java` /
    // `RemoteComposePlayerKind.VIEW` name these players elsewhere; a value copied from either
    // should select what it looks like it selects.
    for (cmp in
      listOf("androidx-embedded", "cmp-android", "embedded", "CMP-ANDROID", " Embedded ")) {
      assertThat(WearWidgetPreviewPlayer.fromWire(cmp)).isEqualTo(WearWidgetPreviewPlayer.CMP)
    }
    for (view in listOf("java", "JAVA", " view ")) {
      assertThat(WearWidgetPreviewPlayer.fromWire(view)).isEqualTo(WearWidgetPreviewPlayer.VIEW)
    }
  }

  @Test
  fun `an unrecognised value names no lane and falls back to the default`() {
    assertThat(WearWidgetPreviewPlayer.fromWire("js")).isNull()
    assertThat(WearWidgetPreviewPlayer.fromWire("cmp-jvm")).isNull()
    assertThat(WearWidgetPreviewPlayer.resolve("cmp-wasm"))
      .isEqualTo(WearWidgetPreviewPlayer.DEFAULT)
  }

  @Test
  fun `the property is the shared one the Gradle plugin forwards`() {
    // Deliberately the same literal `RemoteComposePlayerSelection.PROPERTY` carries in
    // `:data-remotecompose-connector`, which this module cannot depend on: one
    // `-PcomposePreview.rcPlayer=view` has to move widget previews and ordinary Remote Compose
    // previews together, so the two spellings are pinned on both sides rather than trusted to stay
    // in step.
    assertThat(WearWidgetPreviewPlayer.PROPERTY).isEqualTo("composeai.render.rcPlayer")
  }
}
