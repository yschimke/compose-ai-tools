package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewNameFilterTest {

  private val cls = "com.example.preview.ExportHelpDialogKt"
  private val fn = "ExportHelpDialogPreview"

  @Test
  fun `empty filter matches everything`() {
    assertThat(PreviewNameFilter.matches(emptyList(), fn, cls)).isTrue()
  }

  @Test
  fun `blank-only filter matches everything`() {
    assertThat(PreviewNameFilter.matches(listOf("  ", ""), fn, cls)).isTrue()
  }

  @Test
  fun `exact simple name matches`() {
    assertThat(PreviewNameFilter.matches(listOf("ExportHelpDialogPreview"), fn, cls)).isTrue()
  }

  @Test
  fun `substring of simple name matches`() {
    assertThat(PreviewNameFilter.matches(listOf("ExportHelpDialog"), fn, cls)).isTrue()
  }

  @Test
  fun `exact package-qualified name matches`() {
    assertThat(
        PreviewNameFilter.matches(listOf("com.example.preview.ExportHelpDialogPreview"), fn, cls)
      )
      .isTrue()
  }

  @Test
  fun `leading-star glob matches simple name`() {
    assertThat(PreviewNameFilter.matches(listOf("*ExportHelpDialogPreview"), fn, cls)).isTrue()
  }

  @Test
  fun `star glob matches package-qualified name`() {
    assertThat(PreviewNameFilter.matches(listOf("com.example.*.ExportHelpDialogPreview"), fn, cls))
      .isTrue()
  }

  @Test
  fun `interior star glob matches simple name`() {
    assertThat(PreviewNameFilter.matches(listOf("Export*Preview"), fn, cls)).isTrue()
  }

  @Test
  fun `question-mark glob matches a single char`() {
    assertThat(PreviewNameFilter.matches(listOf("ExportHelpDialogPrevie?"), fn, cls)).isTrue()
  }

  @Test
  fun `glob is anchored - partial glob does not match`() {
    // A glob is full-matched, so a bare stem without a trailing wildcard must not match a longer
    // name (unlike the plain-substring path).
    assertThat(PreviewNameFilter.matches(listOf("Export*Dialog"), fn, cls)).isFalse()
  }

  @Test
  fun `dot in glob is a literal, not any-char`() {
    // `com.example` must not match `comXexample` — the escaped dot proves the glob isn't leaking
    // regex metacharacters.
    assertThat(
        PreviewNameFilter.matches(listOf("com.example.preview.*"), fn, "comXexampleXpreviewXKt")
      )
      .isFalse()
  }

  @Test
  fun `non-matching name is rejected`() {
    assertThat(PreviewNameFilter.matches(listOf("SomethingElse"), fn, cls)).isFalse()
  }

  @Test
  fun `matching is case-sensitive`() {
    assertThat(PreviewNameFilter.matches(listOf("exporthelpdialogpreview"), fn, cls)).isFalse()
  }

  @Test
  fun `any pattern in the list matching keeps the preview`() {
    assertThat(PreviewNameFilter.matches(listOf("Nope", "*DialogPreview"), fn, cls)).isTrue()
  }

  @Test
  fun `fqName uses the class package, not the synthetic Kt holder`() {
    assertThat(PreviewNameFilter.fqName(cls, fn))
      .isEqualTo("com.example.preview.ExportHelpDialogPreview")
  }

  @Test
  fun `fqName falls back to the bare function name in the default package`() {
    assertThat(PreviewNameFilter.fqName("FooKt", "BarPreview")).isEqualTo("BarPreview")
  }
}
