package ee.schimke.composeai.renderer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveDataDir] is shared by the display-filter and device-frame producer call sites so both
 * write under the previews-root `data/` regardless of where the capture landed. The regression to
 * guard against (#2192): a LONG/GIF scroll product already lives under `data/<kind>/<id>.png`, and
 * a naive `grandparent/data` resolution nests a bogus `data/data/`.
 */
class ResolveDataDirTest {

  @Test
  fun `normal render resolves sibling data dir`() {
    val target = File("/out/module/renders/MyPreview.png")
    assertEquals(File("/out/module/data"), resolveDataDir(target))
  }

  @Test
  fun `scroll data product does not nest a second data dir`() {
    val target = File("/out/module/data/render-scroll-long/MyPreview.png")
    assertEquals(File("/out/module/data"), resolveDataDir(target))
  }

  @Test
  fun `gif data product does not nest a second data dir`() {
    val target = File("/out/module/data/render-scroll-gif/MyPreview_Gif.gif")
    assertEquals(File("/out/module/data"), resolveDataDir(target))
  }
}
