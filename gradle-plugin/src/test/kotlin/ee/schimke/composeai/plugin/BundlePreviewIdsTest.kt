package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BundlePreviewIdsTest {

  @Test
  fun `parses a single id`() {
    assertThat(BundlePreviewIds.parse("com.example.Foo.Bar")).containsExactly("com.example.Foo.Bar")
  }

  @Test
  fun `splits unescaped commas and trims whitespace`() {
    assertThat(BundlePreviewIds.parse("id1, id2 ,id3"))
      .containsExactly("id1", "id2", "id3")
      .inOrder()
  }

  @Test
  fun `drops empty entries`() {
    assertThat(BundlePreviewIds.parse(",,id1,,")).containsExactly("id1")
  }

  @Test
  fun `escaped comma stays literal in the parsed id`() {
    // `@Preview(name = "Phone, dark")` → id `…_Phone, dark`. The wire form is `…_Phone\, dark`.
    assertThat(BundlePreviewIds.parse("""com.example.Foo.Bar_Phone\, dark"""))
      .containsExactly("com.example.Foo.Bar_Phone, dark")
  }

  @Test
  fun `escaped backslash stays literal in the parsed id`() {
    assertThat(BundlePreviewIds.parse("""a\\b""")).containsExactly("""a\b""")
  }

  @Test
  fun `mixes escaped and unescaped commas across multiple ids`() {
    assertThat(BundlePreviewIds.parse("""id_a\,b,id_c,id_d\,e"""))
      .containsExactly("id_a,b", "id_c", "id_d,e")
      .inOrder()
  }

  @Test
  fun `unknown escape sequence keeps both characters verbatim`() {
    // `\n` is not a recognised escape — the parser leaves the backslash in place rather than
    // swallowing it, so future escape additions stay backwards compatible.
    assertThat(BundlePreviewIds.parse("""a\nb""")).containsExactly("""a\nb""")
  }

  @Test
  fun `trailing backslash is preserved verbatim`() {
    assertThat(BundlePreviewIds.parse("""a\""")).containsExactly("""a\""")
  }

  @Test
  fun `encode escapes commas and backslashes`() {
    assertThat(BundlePreviewIds.encode("com.example.Foo.Bar_Phone, dark"))
      .isEqualTo("""com.example.Foo.Bar_Phone\, dark""")
    assertThat(BundlePreviewIds.encode("""a\b""")).isEqualTo("""a\\b""")
  }

  @Test
  fun `encode then parse round-trips ids with commas and backslashes`() {
    val ids = listOf("com.example.Foo.Bar_Phone, dark", """a\b""", "plain", "with, two, commas")
    val wire = ids.joinToString(",") { BundlePreviewIds.encode(it) }
    assertThat(BundlePreviewIds.parse(wire)).containsExactlyElementsIn(ids).inOrder()
  }
}
