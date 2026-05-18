package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of the generated `robolectric.properties`: `sdk`, `graphicsMode`, `shadows`,
 * and the `application` toggle driven by `composePreview.useConsumerApplication`. The `sdk` +
 * `graphicsMode` keys live here rather than on `@Config` / `@GraphicsMode` to avoid JUnit's
 * `AnnotationParser` resolving `android.app.Application` during test-class discovery — see
 * issue #142 and `GenerateRobolectricPropertiesTask` KDoc.
 *
 * The `sdk` value tracks the consumer's `android.compileSdk` (or a `composePreview.sdkVersion`
 * override) — see issue #1248 and the resolver in `AndroidPreviewSupport`. These tests drive the
 * task directly so they cover the property surface; the AGP-side auto-detection is exercised by the
 * samples' end-to-end render runs.
 */
class GenerateRobolectricPropertiesTaskTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `default emits sdk graphicsMode application shadows`() {
    val body = generate(useConsumerApplication = false, sdk = 36)
    assertThat(body).contains("sdk=36")
    assertThat(body).contains("graphicsMode=NATIVE")
    assertThat(body).contains("application=android.app.Application")
    assertThat(body).contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
  }

  @Test
  fun `useConsumerApplication drops application line but keeps sdk graphicsMode shadows`() {
    val body = generate(useConsumerApplication = true, sdk = 36)
    assertThat(body).contains("sdk=36")
    assertThat(body).contains("graphicsMode=NATIVE")
    assertThat(body).doesNotContain("application=")
    assertThat(body).contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
  }

  @Test
  fun `sdk property is propagated verbatim into the generated file`() {
    // Consumer on the previous compileSdk line — proves the file isn't hard-pinned to 36.
    val body = generate(useConsumerApplication = false, sdk = 35)
    assertThat(body).contains("sdk=35")
  }

  @Test
  fun `sdk above the supported ceiling fails with a Gradle-friendly message`() {
    val exception =
      assertThrows(GradleException::class.java) {
        generate(useConsumerApplication = false, sdk = 99)
      }
    assertThat(exception.message).contains("99")
    assertThat(exception.message).contains("composePreview.sdkVersion")
  }

  @Test
  fun `sdk below the supported floor fails with a Gradle-friendly message`() {
    val exception =
      assertThrows(GradleException::class.java) {
        generate(useConsumerApplication = false, sdk = 5)
      }
    assertThat(exception.message).contains("5")
    assertThat(exception.message).contains("composePreview.sdkVersion")
  }

  private fun generate(useConsumerApplication: Boolean, sdk: Int): String {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task =
      project.tasks
        .register("generateRobolectricProperties", GenerateRobolectricPropertiesTask::class.java)
        .get()
    task.useConsumerApplication.set(useConsumerApplication)
    task.sdk.set(sdk)
    task.outputDir.set(tmp.newFolder("out-$sdk-$useConsumerApplication"))
    task.generate()
    val file =
      task.outputDir.get().asFile.resolve("ee/schimke/composeai/renderer/robolectric.properties")
    return file.readText()
  }
}
