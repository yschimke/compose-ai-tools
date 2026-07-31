package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewThemeShadowingTest {

  private fun invocation(owner: String, method: String) =
    PreviewTargetInference.Invocation(owner, method, "(Landroidx/compose/runtime/Composer;I)V")

  @Test
  fun `installing MaterialTheme is a theme install`() {
    assertThat(
        PreviewThemeShadowing.isThemeInstall(
          invocation("androidx.compose.material3.MaterialThemeKt", "MaterialTheme")
        )
      )
      .isTrue()
    assertThat(
        PreviewThemeShadowing.isThemeInstall(
          invocation("androidx.wear.compose.material3.MaterialThemeKt", "MaterialTheme")
        )
      )
      .isTrue()
  }

  @Test
  fun `the defaulted-args bridge is a theme install`() {
    // `MaterialTheme { content }` leaves colorScheme/typography/shapes defaulted, so the compiler
    // emits the synthetic `MaterialTheme$default` bridge. This is the shape almost every real app
    // theme wrapper produces — missing it would make the check fire on nearly nothing.
    assertThat(
        PreviewThemeShadowing.isThemeInstall(
          invocation("androidx.compose.material3.MaterialThemeKt", "MaterialTheme\$default")
        )
      )
      .isTrue()
  }

  @Test
  fun `reading the ambient theme is not a theme install`() {
    // `MaterialTheme.colorScheme` — the object accessor, not the `…Kt` facade composable. A preview
    // body reading the theme it was handed is exactly the well-behaved case; flagging it would make
    // the check useless.
    assertThat(
        PreviewThemeShadowing.isThemeInstall(
          invocation("androidx.compose.material3.MaterialTheme", "getColorScheme")
        )
      )
      .isFalse()
    assertThat(
        PreviewThemeShadowing.isThemeInstall(
          invocation("androidx.compose.material3.MaterialTheme", "getTypography")
        )
      )
      .isFalse()
  }

  @Test
  fun `unrelated composables are not theme installs`() {
    assertThat(PreviewThemeShadowing.isThemeInstall(invocation("com.example.AppKt", "Surface")))
      .isFalse()
    assertThat(
        PreviewThemeShadowing.isThemeInstall(
          invocation("androidx.compose.foundation.layout.BoxKt", "Box")
        )
      )
      .isFalse()
  }

  @Test
  fun `no findings produces no warning`() {
    assertThat(PreviewThemeShadowing.warningOrNull(findings = emptyList(), themeCount = 3)).isNull()
  }

  @Test
  fun `findings without declared themes produce no warning`() {
    // A module with no theme providers has no theme axis to shadow — an app preview installing its
    // own theme there is just an app preview.
    val finding = PreviewThemeShadowing.Finding("com.example.FooKt", "BarPreview", listOf("Theme"))
    assertThat(PreviewThemeShadowing.warningOrNull(listOf(finding), themeCount = 0)).isNull()
  }

  @Test
  fun `warning names the previews and the call chain`() {
    val warning =
      PreviewThemeShadowing.warningOrNull(
        findings =
          listOf(
            PreviewThemeShadowing.Finding(
              "dev.example.ComponentCatalogKt",
              "CardPreview",
              listOf("AppThemeFixed", "MaterialTheme"),
            )
          ),
        themeCount = 5,
      )
    assertThat(warning).isNotNull()
    assertThat(warning)
      .contains("dev.example.ComponentCatalogKt.CardPreview (via AppThemeFixed → MaterialTheme)")
    assertThat(warning).contains("5 @ThemeCatalog/@WearThemeCatalog provider(s)")
    // Actionable, and explicit that a deliberately pinned preview is allowed to stay.
    assertThat(warning).contains("@PreviewWrapper")
    assertThat(warning).contains("warning, not an error")
  }

  @Test
  fun `warning truncates a long finding list`() {
    val findings =
      (1..14).map {
        PreviewThemeShadowing.Finding("com.example.FooKt", "Preview$it", listOf("MaterialTheme"))
      }
    val warning = PreviewThemeShadowing.warningOrNull(findings, themeCount = 2)
    assertThat(warning).contains("14 @Preview function(s)")
    assertThat(warning).contains("(+4 more)")
    assertThat(warning).doesNotContain("Preview11")
  }
}
