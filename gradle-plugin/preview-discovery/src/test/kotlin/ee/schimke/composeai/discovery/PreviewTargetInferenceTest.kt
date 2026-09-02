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

  // --- component-library targets (PreviewInfo.componentTargets) -------------------------------

  @Test
  fun `a component library composable returning Unit is a component target`() {
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.CardKt",
          "Card",
          returnsUnit = true,
        )
      )
      .isTrue()
  }

  @Test
  fun `a composable property getter is not a component target`() {
    // `MaterialTheme.colorScheme` / `.typography` are @Composable getters on the theme object.
    // They pass every other test — real @Composable, in material3 — and reporting one would
    // describe a sticker's theme lookup as the component it demonstrates. Nine of :samples:cmp's
    // twelve component-bearing previews reported these before the Unit rule existed.
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.MaterialTheme",
          "getColorScheme",
          returnsUnit = false,
        )
      )
      .isFalse()
  }

  @Test
  fun `a theme entry point is the frame, not the subject`() {
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.MaterialThemeKt",
          "MaterialTheme",
          returnsUnit = true,
        )
      )
      .isFalse()
    // …on every component library the catalogs use, not just the Android one.
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.wear.compose.material3.MaterialThemeKt",
          "MaterialTheme",
          returnsUnit = true,
        )
      )
      .isFalse()
  }

  @Test
  fun `an inline-class-mangled JVM name demangles to the source name`() {
    // `androidx.compose.material3.Text` compiles to `Text-Nvy7gAk` because its `fontSize`, `color`
    // and `overflow` parameters are value classes. Taken verbatim the name is not a usable import,
    // so it was rejected — which silently dropped every Material 3 component whose signature
    // mentions `Color`, `Dp` or `TextUnit`, `Text` included.
    assertThat(PreviewTargetInference.sourceFunctionName("Text-Nvy7gAk")).isEqualTo("Text")
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.TextKt",
          PreviewTargetInference.sourceFunctionName("Text-Nvy7gAk"),
          returnsUnit = true,
        )
      )
      .isTrue()
  }

  @Test
  fun `an unmangled name is returned unchanged`() {
    assertThat(PreviewTargetInference.sourceFunctionName("Card")).isEqualTo("Card")
  }

  @Test
  fun `demangling does not rescue a synthetic lambda name`() {
    // Demangling only strips the value-class hash; a synthetic member is still not an import, and
    // the `$` rule that rejects it must keep applying after the strip.
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.CardKt",
          PreviewTargetInference.sourceFunctionName("Card\u0024lambda\u00240"),
          returnsUnit = true,
        )
      )
      .isFalse()
  }

  @Test
  fun `a name that is not a usable Kotlin import is rejected`() {
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.CardKt",
          "Card\u0024lambda\u00240",
          returnsUnit = true,
        )
      )
      .isFalse()
  }
}
