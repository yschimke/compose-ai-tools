package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KmpAndroidSiblingTargetTest {
  @Test
  fun `androidx desktop sibling maps to android sibling`() {
    assertThat(
        AndroidPreviewSupport.kmpAndroidSiblingName(
          "androidx.lifecycle",
          "lifecycle-viewmodel-desktop",
        )
      )
      .isEqualTo("lifecycle-viewmodel-android")
  }

  @Test
  fun `androidx jvmstubs sibling maps to android sibling`() {
    assertThat(
        AndroidPreviewSupport.kmpAndroidSiblingName("androidx.savedstate", "savedstate-jvmstubs")
      )
      .isEqualTo("savedstate-android")
  }

  @Test
  fun `jetbrains compose desktop sibling maps to android sibling`() {
    assertThat(
        AndroidPreviewSupport.kmpAndroidSiblingName("org.jetbrains.compose.ui", "ui-desktop")
      )
      .isEqualTo("ui-android")
  }

  @Test
  fun `unscoped desktop artifact is not rewritten`() {
    assertThat(
        AndroidPreviewSupport.kmpAndroidSiblingName(
          "org.jetbrains.kotlinx",
          "kotlinx-coroutines-swing-desktop",
        )
      )
      .isNull()
  }

  @Test
  fun `non sibling artifact is not rewritten`() {
    assertThat(
        AndroidPreviewSupport.kmpAndroidSiblingName(
          "org.jetbrains.compose.ui",
          "ui-tooling-preview",
        )
      )
      .isNull()
  }
}
