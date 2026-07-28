package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewTargetInferenceTest {

  @Test
  fun `nameMatches strips Preview suffix`() {
    assertThat(PreviewTargetInference.nameMatches("FooPreview", "Foo")).isTrue()
    assertThat(PreviewTargetInference.nameMatches("FooScreenPreview", "FooScreen")).isTrue()
  }

  @Test
  fun `nameMatches strips Preview prefix`() {
    assertThat(PreviewTargetInference.nameMatches("PreviewFoo", "Foo")).isTrue()
    assertThat(PreviewTargetInference.nameMatches("Preview_Foo", "Foo")).isTrue()
  }

  @Test
  fun `nameMatches matches leading segment when separators present`() {
    // `Foo_Light_Preview` → strip `_Preview` → `Foo_Light` → leading `Foo` matches.
    assertThat(PreviewTargetInference.nameMatches("Foo_Light_Preview", "Foo")).isTrue()
    assertThat(PreviewTargetInference.nameMatches("Foo_Dark_Preview", "Foo")).isTrue()
  }

  @Test
  fun `nameMatches strips internal-fun JVM mangle`() {
    // `internal fun InternalFooPreview` compiles to `InternalFooPreview$<module>`.
    assertThat(PreviewTargetInference.nameMatches("InternalFooPreview\$test_module", "InternalFoo"))
      .isTrue()
  }

  @Test
  fun `nameMatches rejects unrelated names`() {
    assertThat(PreviewTargetInference.nameMatches("FooPreview", "Bar")).isFalse()
    assertThat(PreviewTargetInference.nameMatches("FooPreview", "FooBar")).isFalse()
  }

  @Test
  fun `nameMatches rejects bare Preview when nothing left after strip`() {
    assertThat(PreviewTargetInference.nameMatches("Preview", "Anything")).isFalse()
  }

  @Test
  fun `invalid JVM names are rejected as Kotlin import targets`() {
    assertThat(PreviewTargetInference.isValidKotlinImportIdentifier("Screen")).isTrue()
    assertThat(PreviewTargetInference.isValidKotlinImportIdentifier("_Screen2")).isTrue()
    assertThat(PreviewTargetInference.isValidKotlinImportIdentifier("Screen-G2aJUZY")).isFalse()
    assertThat(PreviewTargetInference.isValidKotlinImportIdentifier("Screen\$module")).isFalse()
  }

  @Test
  fun `preview wrapper names and debug catalog sources are rejected`() {
    assertThat(PreviewTargetInference.isPreviewOnlyWrapper("Wrap", null)).isTrue()
    assertThat(PreviewTargetInference.isPreviewOnlyWrapper("JetnewsTheme", null)).isTrue()
    assertThat(PreviewTargetInference.isPreviewOnlyWrapper("JetsnackPreviewWrapper", null)).isTrue()
    assertThat(
        PreviewTargetInference.isPreviewOnlyWrapper(
          "Component",
          "src/debug/kotlin/com/example/catalog/Components.kt",
        )
      )
      .isTrue()
    assertThat(
        PreviewTargetInference.isPreviewOnlyWrapper(
          "Component",
          "src/main/kotlin/com/example/components/Components.kt",
        )
      )
      .isFalse()
  }
}
