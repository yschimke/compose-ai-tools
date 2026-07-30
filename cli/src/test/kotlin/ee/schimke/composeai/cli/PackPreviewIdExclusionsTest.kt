package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class PackPreviewIdExclusionsTest {

  private val noEnv: (String) -> String? = { null }

  private fun envWith(value: String): (String) -> String? = { name ->
    if (name == PackPreviewIdExclusions.ENV_VAR) value else null
  }

  @Test
  fun `no flag and no env yields no patterns`() {
    assertEquals(
      emptyList(),
      PackPreviewIdExclusions.fromArgs(listOf("pack", "--module", "app"), noEnv),
    )
  }

  @Test
  fun `repeatable flag collects every value`() {
    val args = listOf("--exclude-preview-id", "Foo_Dark", "--exclude-preview-id", "Bar_Dark")
    assertEquals(listOf("Foo_Dark", "Bar_Dark"), PackPreviewIdExclusions.fromArgs(args, noEnv))
  }

  @Test
  fun `one value may be comma-separated`() {
    val args = listOf("--exclude-preview-id", "Foo_Dark, Bar_Dark ,")
    assertEquals(listOf("Foo_Dark", "Bar_Dark"), PackPreviewIdExclusions.fromArgs(args, noEnv))
  }

  @Test
  fun `env var is used when the flag is absent`() {
    assertEquals(
      listOf("Foo_Dark", "Bar_Dark"),
      PackPreviewIdExclusions.fromArgs(listOf("pack"), envWith("Foo_Dark,Bar_Dark")),
    )
  }

  @Test
  fun `an explicit flag wins over the env var rather than merging`() {
    assertEquals(
      listOf("Foo_Dark"),
      PackPreviewIdExclusions.fromArgs(
        listOf("--exclude-preview-id", "Foo_Dark"),
        envWith("Everything_Dark"),
      ),
    )
  }

  @Test
  fun `env var name is the Gradle property's ORG_GRADLE_PROJECT form`() {
    assertEquals("ORG_GRADLE_PROJECT_composePreview.idExclude", PackPreviewIdExclusions.ENV_VAR)
  }

  @Test
  fun `row labels come from their own flag and env var`() {
    val args =
      listOf("--exclude-preview-row", "Dark, ExtraDark", "--exclude-preview-id", "Foo_Dark")
    assertEquals(listOf("Dark", "ExtraDark"), PackPreviewIdExclusions.rowsFromArgs(args, noEnv))
    // The two axes are independent: an id pattern never lands in the row list, or a pack would skip
    // rows it was only asked to skip whole previews for.
    assertEquals(listOf("Foo_Dark"), PackPreviewIdExclusions.fromArgs(args, noEnv))
  }

  @Test
  fun `row labels fall back to the row env var only`() {
    val env = { name: String ->
      when (name) {
        PackPreviewIdExclusions.ROW_ENV_VAR -> "Dark"
        PackPreviewIdExclusions.ENV_VAR -> "Foo_Dark"
        else -> null
      }
    }
    assertEquals(listOf("Dark"), PackPreviewIdExclusions.rowsFromArgs(listOf("pack"), env))
    assertEquals(
      "ORG_GRADLE_PROJECT_composePreview.rowExclude",
      PackPreviewIdExclusions.ROW_ENV_VAR,
    )
  }

  private val ids = listOf("Foo_Light", "Foo_Dark", "FooLarge_Dark", "Bar_Light")

  @Test
  fun `no patterns retains every id`() {
    assertEquals(ids, PackPreviewIdExclusions.retain(ids, emptyList()))
    assertEquals(ids, PackPreviewIdExclusions.retain(ids, listOf(" ", "")))
  }

  @Test
  fun `an exact pattern drops that id`() {
    assertEquals(
      listOf("Foo_Light", "FooLarge_Dark", "Bar_Light"),
      PackPreviewIdExclusions.retain(ids, listOf("Foo_Dark")),
    )
  }

  @Test
  fun `a plain pattern also matches as a substring, like the plugin's matchesId`() {
    // The render-side filter (PreviewNameFilter.matchesId) substring-matches a glob-free pattern,
    // so
    // this must too — otherwise a plain pattern would thin the render but not the semantics pass.
    assertEquals(
      listOf("Foo_Light", "Bar_Light"),
      PackPreviewIdExclusions.retain(ids, listOf("_Dark")),
    )
  }

  @Test
  fun `a glob drops the whole family`() {
    assertEquals(
      listOf("Foo_Light", "Bar_Light"),
      PackPreviewIdExclusions.retain(ids, listOf("*_Dark")),
    )
  }

  @Test
  fun `matching is case-sensitive`() {
    assertEquals(ids, PackPreviewIdExclusions.retain(ids, listOf("foo_dark")))
  }

  @Test
  fun `a substring pattern is as wide as it reads`() {
    // `Foo` matches `Foo_Light`, `Foo_Dark` AND `FooLarge_Dark` — the same width the render-side
    // filter gives it. Reach for a glob (`Foo_*`) when that's not what you meant.
    assertEquals(listOf("Bar_Light"), PackPreviewIdExclusions.retain(ids, listOf("Foo")))
  }
}
