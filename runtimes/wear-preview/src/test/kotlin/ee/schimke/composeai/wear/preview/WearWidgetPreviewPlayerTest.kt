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
  fun `nothing selected draws with the AndroidX embedded player`() {
    val embedded = WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED
    assertThat(WearWidgetPreviewPlayer.DEFAULT).isEqualTo(embedded)
    assertThat(WearWidgetPreviewPlayer.resolve(null)).isEqualTo(embedded)
    assertThat(WearWidgetPreviewPlayer.resolve("")).isEqualTo(embedded)
    assertThat(WearWidgetPreviewPlayer.resolve("   ")).isEqualTo(embedded)
  }

  @Test
  fun `each lane answers to its canonical implementation name`() {
    assertThat(WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED.wire).isEqualTo("androidx-embedded")
    assertThat(WearWidgetPreviewPlayer.ANDROIDX_VIEW.wire).isEqualTo("androidx-view")
    for (lane in WearWidgetPreviewPlayer.entries) {
      assertThat(WearWidgetPreviewPlayer.fromWire(lane.wire)).isEqualTo(lane)
    }
  }

  @Test
  fun `the legacy spellings the daemon still accepts select the same players`() {
    // Kept in lockstep with `RemoteComposePlayerSelection.fromWire`.
    for (raw in listOf("embedded", "EMBEDDED", " Androidx-Embedded ")) {
      assertThat(WearWidgetPreviewPlayer.fromWire(raw))
        .isEqualTo(WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED)
    }
    for (raw in listOf("java", "JAVA", " view ", "ANDROIDX-VIEW")) {
      assertThat(WearWidgetPreviewPlayer.fromWire(raw))
        .isEqualTo(WearWidgetPreviewPlayer.ANDROIDX_VIEW)
    }
  }

  @Test
  fun `cmp-android is the CMP player and selects no capture lane`() {
    // `cmp-android` names rc-player-compose on Android, a replay-only daemon backend; it used to
    // name the embedded player, and must not silently keep doing so here. The bare `cmp` is
    // retired.
    assertThat(WearWidgetPreviewPlayer.fromWire("cmp-android")).isNull()
    assertThat(WearWidgetPreviewPlayer.fromWire("cmp")).isNull()
  }

  @Test
  fun `an unrecognised value names no lane and falls back to the default`() {
    assertThat(WearWidgetPreviewPlayer.fromWire("camaelon-js")).isNull()
    assertThat(WearWidgetPreviewPlayer.fromWire("js")).isNull()
    assertThat(WearWidgetPreviewPlayer.fromWire("cmp-jvm")).isNull()
    assertThat(WearWidgetPreviewPlayer.resolve("cmp-wasm"))
      .isEqualTo(WearWidgetPreviewPlayer.DEFAULT)
  }

  @Test
  fun `the property is the shared one the Gradle plugin forwards`() {
    // Deliberately the same literal `RemoteComposePlayerSelection.PROPERTY` carries in
    // `:data-remotecompose-connector`, which this module cannot depend on: one
    // `-PcomposePreview.rcPlayer=androidx-view` has to move widget previews and ordinary Remote
    // Compose previews together, so the two spellings are pinned on both sides rather than trusted
    // to stay in step.
    assertThat(WearWidgetPreviewPlayer.PROPERTY).isEqualTo("composeai.render.rcPlayer")
  }
}
