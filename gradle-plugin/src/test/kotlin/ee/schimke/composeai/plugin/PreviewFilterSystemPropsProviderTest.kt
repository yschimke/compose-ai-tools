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

  @Test
  fun `emits one comma-joined -D per non-empty filter`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list("Foo", "Bar"),
          idFilters = list("*_Light"),
          idExcludes = list("*_Dark"),
          rowExcludes = list("Dark", "ExtraDark"),
        )
        .asArguments()
        .toList()

    assertThat(args)
      .containsExactly(
        "-Dcomposeai.preview.filter=Foo,Bar",
        "-Dcomposeai.preview.idFilter=*_Light",
        "-Dcomposeai.preview.idExclude=*_Dark",
        "-Dcomposeai.preview.rowExclude=Dark,ExtraDark",
      )
  }

  @Test
  fun `empty filters emit no arguments`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list(),
          rowExcludes = list(),
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
          rowExcludes = list(),
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
          rowExcludes = list("Dark", " ExtraDark "),
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.rowExclude=Dark,ExtraDark")
  }
}
