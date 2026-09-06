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
  fun `nameQualifies accepts a CamelCase qualifier after the candidate name`() {
    // Confetti's `SessionCardPopulatedPreview`, `SessionCardLoadingPreview` and
    // `SessionCardBookmarkedPreview` are all previews of `SessionCard`.
    assertThat(PreviewTargetInference.nameQualifies("SessionCardPopulatedPreview", "SessionCard"))
      .isTrue()
    assertThat(PreviewTargetInference.nameQualifies("FooBar2Preview", "FooBar")).isTrue()
    assertThat(PreviewTargetInference.nameQualifies("PreviewFooBar", "Foo")).isTrue()
  }

  @Test
  fun `nameQualifies rejects a name that merely shares a prefix`() {
    // `Foobar` is not `Foo` plus a qualifier — the continuation is lower-case.
    assertThat(PreviewTargetInference.nameQualifies("FoobarPreview", "Foo")).isFalse()
    // An exact match is `nameMatches`'s, and never both.
    assertThat(PreviewTargetInference.nameQualifies("FooPreview", "Foo")).isFalse()
    assertThat(PreviewTargetInference.nameQualifies("FooPreview", "FooBar")).isFalse()
    assertThat(PreviewTargetInference.nameQualifies("Preview", "Foo")).isFalse()
    assertThat(PreviewTargetInference.nameQualifies("FooBarPreview", "")).isFalse()
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
  fun `the source name from metadata is what the import rule judges`() {
    // `androidx.compose.material3.Text` compiles to `Text-Nvy7gAk` because its `fontSize`, `color`
    // and `overflow` parameters are value classes. Judged on the JVM name it is not a usable
    // import and was rejected, which dropped every Material 3 component mentioning `Color`, `Dp`
    // or `TextUnit`. `inferComponents` reads the source name from metadata and passes that here.
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.TextKt",
          "Text",
          returnsUnit = true,
        )
      )
      .isTrue()
  }

  @Test
  fun `a backtick-escaped name containing a hyphen is still not an import`() {
    // ``fun `filled-button`()`` is legal Kotlin, and its *source* name really does contain a
    // hyphen — so it is not a usable import and must stay rejected. This is why the source name
    // comes from metadata rather than from trimming the JVM name at the first `-`, which would
    // have turned this into `filled` and published a function that does not exist.
    assertThat(
        PreviewTargetInference.isComponentLibraryTarget(
          "androidx.compose.material3.ButtonKt",
          "filled-button",
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

  @Test
  fun `a mangled JVM name only matches its preview once scored on the source name`() {
    // `ScreenPreview` is the clearest naming convention a preview can follow, and it was the one
    // this signal never fired for: `Screen(padding: Dp)` compiles to `Screen-a1b2c3d`, and the
    // scorer compared against that. Scoring on the source name is what makes NAME_MATCH reachable
    // for a composable taking a value-class parameter.
    assertThat(PreviewTargetInference.nameMatches("ScreenPreview", "Screen-a1b2c3d")).isFalse()
    assertThat(PreviewTargetInference.nameMatches("ScreenPreview", "Screen")).isTrue()
  }
}
