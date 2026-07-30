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
        )
        .asArguments()
        .toList()

    assertThat(args)
      .containsExactly(
        "-Dcomposeai.preview.filter=Foo,Bar",
        "-Dcomposeai.preview.idFilter=*_Light",
        "-Dcomposeai.preview.idExclude=*_Dark",
      )
  }

  @Test
  fun `empty filters emit no arguments`() {
    val args =
      AndroidPreviewSupport.PreviewFilterSystemPropsProvider(
          nameFilters = list(),
          idFilters = list(),
          idExcludes = list(),
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
        )
        .asArguments()
        .toList()

    assertThat(args).containsExactly("-Dcomposeai.preview.filter=Foo")
  }
}
