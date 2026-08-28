package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Coverage for the `serve` half of issue #3749: a screen whose states come from a
 * `@PreviewParameter` provider must list every state, not just value 0.
 *
 * Discovery can't enumerate a provider, so the row set is read back off the fan-out the render pass
 * wrote. These tests drive [ServeParameterRows] against a [FakeFileSystem] so the filename rules
 * are pinned without a render.
 */
class ServeParameterRowsTest {

  private val moduleDir = "/mod".toPath()
  private val rendersDir = "/mod/build/compose-previews/renders".toPath()

  private val fs = FakeFileSystem().also { it.createDirectories(rendersDir) }

  private fun touch(name: String) {
    fs.write(rendersDir / name) { writeUtf8("png") }
  }

  private fun preview(
    id: String,
    output: String = "renders/$id.png",
    parameterized: Boolean = true,
  ) =
    PreviewInfo(
      id = id,
      functionName = id.substringBefore('_'),
      className = "com.example.PreviewsKt",
      params =
        PreviewParams(
          previewParameterProviderClassName =
            if (parameterized) "com.example.SwatchProvider" else null
        ),
      captures = listOf(Capture(renderOutput = output)),
    )

  private fun rows(target: PreviewInfo, vararg all: PreviewInfo) =
    ServeParameterRows.rowsFor(
      preview = target,
      moduleDir = moduleDir,
      siblingOutputs = ServeParameterRows.claimedOutputs(all.toList().ifEmpty { listOf(target) }),
      fileSystem = fs,
    )

  /** The reporter's case: four provider values, four servable ids. */
  @Test
  fun `a labelled fan-out becomes one row per value`() {
    val swatch = preview("SwatchPreview")
    touch("SwatchPreview.png")
    listOf("Crimson", "Teal", "Amber", "Violet").forEach { touch("SwatchPreview_$it.png") }

    assertEquals(
      listOf(
        "SwatchPreview_Amber",
        "SwatchPreview_Crimson",
        "SwatchPreview_Teal",
        "SwatchPreview_Violet",
      ),
      rows(swatch).map { it.id },
    )
    assertEquals(listOf("Amber", "Crimson", "Teal", "Violet"), rows(swatch).map { it.label })
  }

  /** `PARAM_10` sorts after `PARAM_2`, not before it as lexicographic order would. */
  @Test
  fun `index rows order numerically and come before labelled ones`() {
    val p = preview("Foo")
    listOf("PARAM_0", "PARAM_2", "PARAM_10").forEach { touch("Foo_$it.png") }
    touch("Foo_Zed.png")

    assertEquals(
      listOf("Foo_PARAM_0", "Foo_PARAM_2", "Foo_PARAM_10", "Foo_Zed"),
      rows(p).map { it.id },
    )
  }

  /**
   * `Foo` and `Foo_Dark` are both real previews when a multi-preview annotation is in play, so
   * `renders/Foo_Dark.png` belongs to the latter and must not be read as a row of the former.
   */
  @Test
  fun `a sibling preview's own render is not a row`() {
    val foo = preview("Foo")
    val fooDark = preview("Foo_Dark", output = "renders/Foo_Dark.png")
    touch("Foo_Dark.png")
    touch("Foo_Crimson.png")

    assertEquals(listOf("Foo_Crimson"), rows(foo, foo, fooDark).map { it.id })
  }

  /**
   * Issue #3819. A *longer-stemmed* sibling owns its own fan-out: with `Foo` and `Foo_Dark` both
   * real previews, `Foo_Dark_Alice.png` is `Foo_Dark`'s row, not `Foo`'s row `Dark_Alice`. The
   * builder that feeds `show` / `list` / `render` always applied that rule
   * (`parameterFanoutOwnedBySibling`) and this glob didn't — the exact drift that makes two
   * derivations of one id dangerous: `serve` would list a row id that resolves to a different
   * preview's render everywhere else. Both now go through `PreviewParameterFanout`.
   */
  @Test
  fun `a longer-stemmed sibling's fan-out is not a row of the shorter one`() {
    val foo = preview("Foo")
    val fooDark = preview("Foo_Dark", output = "renders/Foo_Dark.png")
    touch("Foo_Dark.png")
    touch("Foo_Dark_Alice.png")
    touch("Foo_Crimson.png")

    assertEquals(listOf("Foo_Crimson"), rows(foo, foo, fooDark).map { it.id })
    assertEquals(listOf("Foo_Dark_Alice"), rows(fooDark, foo, fooDark).map { it.id })
  }

  /** A preview's own non-row captures (scroll variants) are claimed by it, so they're excluded. */
  @Test
  fun `the preview's own extra captures are not rows`() {
    val p =
      preview("Foo")
        .copy(
          captures =
            listOf(
              Capture(renderOutput = "renders/Foo.png"),
              Capture(renderOutput = "renders/Foo_SCROLL_end.png"),
            )
        )
    touch("Foo_SCROLL_end.png")
    touch("Foo_Crimson.png")

    assertEquals(listOf("Foo_Crimson"), rows(p).map { it.id })
  }

  /** No provider means no rows — an ordinary preview keeps exactly its old single entry. */
  @Test
  fun `a preview with no provider never expands`() {
    val plain = preview("Plain", parameterized = false)
    touch("Plain_Crimson.png")

    assertTrue(rows(plain).isEmpty())
  }

  /**
   * A `serve` run that didn't render (bundle-backed, or a render that failed) has no fan-out on
   * disk. Expanding to nothing keeps the base id listed rather than emptying the viewer.
   */
  @Test
  fun `a missing fan-out expands to nothing`() {
    val p = preview("Ghost")
    assertTrue(rows(p).isEmpty())

    val noDir = ServeParameterRows.rowsFor(p, "/nope".toPath(), emptySet(), fs)
    assertTrue(noDir.isEmpty())
  }

  /** Only files sharing the template's extension count — a GIF sibling isn't a PNG row. */
  @Test
  fun `only the template extension matches`() {
    val p = preview("Foo")
    touch("Foo_Crimson.png")
    touch("Foo_Animated.gif")

    assertEquals(listOf("Foo_Crimson"), rows(p).map { it.id })
  }
}
