package ee.schimke.composeai.renderer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the `@PreviewParameter` stale fan-out cleanup in `DesktopRendererMain`, in particular the
 * sibling protection from issue #2193: `@Preview(name = "Dark")` on `Foo` produces a sibling
 * preview whose stem (`Foo_Dark`) is an underscore-extension of `Foo`'s, so `Foo`'s prefix-greedy
 * sweep matched — and deleted — the sibling's base render and fan-out. The plugin now passes the
 * sibling stems in and [deleteStaleFanoutFiles] must leave their files alone.
 */
class DeleteStaleFanoutFilesTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun touch(name: String): File = tmp.newFile(name)

  @Test
  fun `deletes stale fan-out and keeps expected fan-out`() {
    val template = File(tmp.root, "Foo.png")
    touch("Foo_Alice.png")
    touch("Foo_PARAM_0.png")
    touch("Foo_renamed label.png")

    deleteStaleFanoutFiles(template, expectedNames = setOf("Foo_Alice.png"))

    assertTrue(File(tmp.root, "Foo_Alice.png").exists())
    assertFalse(File(tmp.root, "Foo_PARAM_0.png").exists())
    assertFalse(File(tmp.root, "Foo_renamed label.png").exists())
  }

  @Test
  fun `keeps sibling preview base render and its fan-out (issue 2193)`() {
    val template = File(tmp.root, "Foo.png")
    // The `@Preview(name = "Dark")` sibling's outputs — underscore-extensions of `Foo`.
    touch("Foo_Dark.png")
    touch("Foo_Dark_Alice.png")
    // Genuinely stale fan-out of `Foo` itself.
    touch("Foo_stale.png")

    deleteStaleFanoutFiles(
      template,
      expectedNames = setOf("Foo_Alice.png"),
      protectedSiblingStems = listOf("Foo_Dark"),
    )

    assertTrue(File(tmp.root, "Foo_Dark.png").exists())
    assertTrue(File(tmp.root, "Foo_Dark_Alice.png").exists())
    assertFalse(File(tmp.root, "Foo_stale.png").exists())
  }

  @Test
  fun `sibling protection does not shield unrelated stems sharing a name prefix`() {
    val template = File(tmp.root, "Foo.png")
    // `Foo_Darkish` extends the string `Foo_Dark` but is NOT `Foo_Dark.<ext>` nor `Foo_Dark_*`,
    // so the sibling stem must not shield it.
    touch("Foo_Darkish.png")

    deleteStaleFanoutFiles(
      template,
      expectedNames = emptySet(),
      protectedSiblingStems = listOf("Foo_Dark"),
    )

    assertFalse(File(tmp.root, "Foo_Darkish.png").exists())
  }

  @Test
  fun `only files matching the template prefix and extension are candidates`() {
    val template = File(tmp.root, "Foo.png")
    touch("Foobar_x.png")
    touch("Foo_x.gif")
    touch("Bar_x.png")

    deleteStaleFanoutFiles(template, expectedNames = emptySet())

    assertTrue(File(tmp.root, "Foobar_x.png").exists())
    assertTrue(File(tmp.root, "Foo_x.gif").exists())
    assertTrue(File(tmp.root, "Bar_x.png").exists())
  }
}
