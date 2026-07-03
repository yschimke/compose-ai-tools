package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Pins [fanoutSiblingStems] — the plugin-side half of the issue #2193 fix. The desktop renderer's
 * `@PreviewParameter` stale fan-out cleanup is prefix-greedy inside its own subprocess, so the
 * plugin must tell it which same-directory manifest outputs extend the current stem (`Foo` vs the
 * `@Preview(name = "Dark")` sibling's `Foo_Dark…`) and are therefore never stale.
 */
class FanoutSiblingStemsTest {

  private val rendersDir = File("/previews/renders")

  private fun render(name: String) = File(rendersDir, name)

  @Test
  fun `returns same-directory stems extending the output stem`() {
    val outputs =
      listOf(
        render("Foo.png"),
        render("Foo_Dark.png"),
        render("Foo_Selected.gif"),
        render("Bar.png"),
      )
    assertThat(fanoutSiblingStems(outputs, render("Foo.png")))
      .containsExactly("Foo_Dark", "Foo_Selected")
      .inOrder()
  }

  @Test
  fun `ignores outputs in other directories and non-extending stems`() {
    val outputs =
      listOf(
        File("/previews/data/render-scroll-long", "Foo_Dark.png"),
        render("Foobar.png"),
        render("Foo.png"),
      )
    assertThat(fanoutSiblingStems(outputs, render("Foo.png"))).isEmpty()
  }

  @Test
  fun `reverse direction only protects deeper extensions`() {
    // Rendering the `Foo_Dark` variant: plain `Foo` doesn't match its prefix (nothing to
    // protect), but a hypothetical deeper `Foo_Dark_TIME_500ms` capture does.
    val outputs = listOf(render("Foo.png"), render("Foo_Dark_TIME_500ms.png"))
    assertThat(fanoutSiblingStems(outputs, render("Foo_Dark.png")))
      .containsExactly("Foo_Dark_TIME_500ms")
  }

  @Test
  fun `deduplicates stems shared by several captures`() {
    // The same sibling stem can appear once per capture (e.g. a PNG and a GIF product).
    val outputs = listOf(render("Foo_Dark.png"), render("Foo_Dark.gif"))
    assertThat(fanoutSiblingStems(outputs, render("Foo.png"))).containsExactly("Foo_Dark")
  }
}
