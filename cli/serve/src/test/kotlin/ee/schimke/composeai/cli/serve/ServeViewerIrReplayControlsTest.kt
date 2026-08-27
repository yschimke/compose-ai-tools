package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `data-ir-replay` flag the viewer greys its inert controls from.
 *
 * A preview redrawn by replaying a captured Remote Compose document can't be recomposed, so the
 * server answers a `themeProvider` / `knob.` / `localeTag` override with a 409 rather than
 * unchanged pixels ([CatalogLiveRouting.irReplayDroppedOverrideNames]). Without this flag the
 * viewer would still offer those controls, and dragging one would only produce an error — so the
 * page carries the same fact the refusal is derived from, and `viewer.js` disables exactly that
 * set.
 *
 * The flag is deliberately **not** `data-has-rc-doc`, which the RC canvas lane already uses. The
 * two coincide on every host today but answer different questions ("there are `.rc` bytes for the
 * browser" vs "the daemon cannot recompose this"), and a host that ever serves a document for a
 * class-backed preview must not grey that preview's live controls.
 */
class ServeViewerIrReplayControlsTest {

  private val preview = ServePreview(id = "button-namedlabel__ideal__default__light", label = "Btn")

  private fun viewer(irReplay: Boolean) =
    ServeWeb.viewerPage(
      preview,
      token = "t",
      siblings = listOf(preview),
      canApplyOverrides = true,
      irReplay = irReplay,
    )

  @Test
  fun `an IR-replayed preview carries the flag`() {
    assertTrue(viewer(irReplay = true).contains("data-ir-replay=\"1\""))
  }

  @Test
  fun `an ordinary preview does not`() {
    assertFalse(viewer(irReplay = false).contains("data-ir-replay"))
  }

  /**
   * The flag has to be independent of the RC canvas lane's own gate, or the viewer would grey
   * controls for any preview that merely carries a document.
   */
  @Test
  fun `the flag is independent of the RC canvas lane's doc gate`() {
    val docOnly =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        siblings = listOf(preview),
        canApplyOverrides = true,
        hasRemoteComposeDoc = true,
        irReplay = false,
      )
    assertTrue(docOnly.contains("data-has-rc-doc=\"1\""))
    assertFalse(docOnly.contains("data-ir-replay"))
  }
}
