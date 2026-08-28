package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Pins the wire format of [AndroidPreviewSupport.PreviewFilterSystemPropsProvider] — the
 * `composeai.preview.*` system properties the Android Robolectric render reads (issue #2977). The
 * property names are a contract with the renderer-side `PreviewFilter`, so they're asserted
 * literally here.
 */
class PreviewFilterSystemPropsProviderTest {

  private val project = ProjectBuilder.builder().build()

  private fun list(vararg v: String) =
    project.objects.listProperty(String::class.java).apply { set(v.toList()) }

  /** No `--exclude-preview-id-file`: the comma-joined wire format stays in force. */
  private val noFile = project.objects.property(String::class.java)

  private fun fileWith(lines: List<String>): java.io.File =
    java.io.File.createTempFile("excludes-", ".txt").apply {
      deleteOnExit()
      writeText(lines.joinToString("\n"))
    }

  private fun path(f: java.io.File) =
    project.objects.property(String::class.java).apply { set(f.path) }

  @Test
  fun `emits one comma-joined -D per non-empty filter`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list("Foo", "Bar"),
          idFilters = list("*_Light"),
          idExcludes = list("*_Dark"),
          idExcludeFile = noFile,
          rowExcludes = list("Dark", "ExtraDark"),
          permutations = list("accessibility"),
        )
        .asArguments()
        .toList()

    assertThat(args)
      .containsExactly(
        "-Dcomposeai.preview.filter=Foo,Bar",
        "-Dcomposeai.preview.idFilter=*_Light",
        "-Dcomposeai.preview.idExclude=*_Dark",
        "-Dcomposeai.preview.rowExclude=Dark,ExtraDark",
        "-Dcomposeai.preview.permutations=accessibility",
      )
  }

  @Test
  fun `empty filters emit no arguments`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list(),
          idExcludeFile = noFile,
          rowExcludes = list(),
          permutations = list(),
        )
        .asArguments()
        .toList()

    assertThat(args).isEmpty()
  }

  @Test
  fun `blank segments are dropped before joining`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(" Foo ", "", "  "),
          idFilters = list(),
          idExcludes = list(),
          idExcludeFile = noFile,
          rowExcludes = list(),
          permutations = list(),
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.filter=Foo")
  }

  @Test
  fun `the row axis travels on its own property`() {
    // It must not be folded into the id patterns: the id filters run over discovered entries, where
    // a
    // parameterized preview has no per-row id, so only a label can name one of its rows (#2966).
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list(),
          idExcludeFile = noFile,
          rowExcludes = list("Dark", " ExtraDark "),
          permutations = list(),
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.rowExclude=Dark,ExtraDark")
  }

  @Test
  fun `permutations split comma-batched values`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list(),
          idExcludeFile = noFile,
          rowExcludes = list(),
          permutations = list(" accessibility,foo ", "accessibility"),
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.permutations=accessibility,foo")
  }

  /**
   * The reason the file form exists: a preview id may contain a comma, so the joined property
   * cannot carry it. When the file is the source of the patterns, the PATH travels instead —
   * absolute, because the render's Gradle build runs in the module directory, not the CLI's.
   */
  @Test
  fun `a file-sourced exclusion list travels as a path, not a joined string`() {
    val ids = listOf("Foo_width=227dp, dpi=320", "Bar_width=227dp, dpi=320")
    val f = fileWith(ids)
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list(*ids.toTypedArray()),
          idExcludeFile = path(f),
          rowExcludes = list(),
          permutations = list(),
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.idExcludeFile=${f.absolutePath}")
  }

  /**
   * `--exclude-preview-id` on the task overrides the property convention, so a stale
   * `-PcomposePreview.idExcludeFile` must not win. The resolved lists differing is what marks the
   * override.
   */
  @Test
  fun `an overridden exclusion list ignores the file and travels joined`() {
    val f = fileWith(listOf("FromFile"))
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list("TypedOnTheCommandLine"),
          idExcludeFile = path(f),
          rowExcludes = list(),
          permutations = list(),
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.idExclude=TypedOnTheCommandLine")
  }
}
