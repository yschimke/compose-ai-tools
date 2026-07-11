package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.PreviewInfo
import org.gradle.api.GradleException
import org.junit.Test

class SelectNamedPreviewsTest {

  private fun preview(functionName: String, pkg: String = "com.example.preview") =
    PreviewInfo(
      id = "$pkg.$functionName",
      functionName = functionName,
      className = "$pkg.PreviewsKt",
    )

  private val all =
    listOf(
      preview("ExportHelpDialogPreview"),
      preview("HomeScreenPreview"),
      preview("BrokenPreview"),
    )

  @Test
  fun `empty filter returns every preview`() {
    assertThat(selectNamedPreviews(all, emptyList())).isEqualTo(all)
  }

  @Test
  fun `blank-only filter returns every preview`() {
    assertThat(selectNamedPreviews(all, listOf("  ", ""))).isEqualTo(all)
  }

  @Test
  fun `glob narrows to the single matching preview`() {
    val selected = selectNamedPreviews(all, listOf("*ExportHelpDialogPreview"))
    assertThat(selected.map { it.functionName }).containsExactly("ExportHelpDialogPreview")
  }

  @Test
  fun `an unrelated broken preview is not selected by a non-matching filter`() {
    val selected = selectNamedPreviews(all, listOf("HomeScreenPreview"))
    assertThat(selected.map { it.functionName }).containsExactly("HomeScreenPreview")
    assertThat(selected.map { it.functionName }).doesNotContain("BrokenPreview")
  }

  @Test
  fun `no match fails fast and lists available preview names`() {
    val thrown =
      try {
        selectNamedPreviews(all, listOf("DoesNotExist"))
        null
      } catch (e: GradleException) {
        e
      }
    assertThat(thrown).isNotNull()
    val message = thrown!!.message!!
    assertThat(message).contains("matched no previews")
    assertThat(message).contains("'DoesNotExist'")
    // Names are surfaced as the readable package-qualified form, sorted.
    assertThat(message).contains("com.example.preview.ExportHelpDialogPreview")
    assertThat(message).contains("com.example.preview.HomeScreenPreview")
  }

  @Test
  fun `no match with no discovered previews explains the empty module`() {
    val thrown =
      try {
        selectNamedPreviews(emptyList(), listOf("Anything"))
        null
      } catch (e: GradleException) {
        e
      }
    assertThat(thrown!!.message).contains("no discovered previews")
  }
}
