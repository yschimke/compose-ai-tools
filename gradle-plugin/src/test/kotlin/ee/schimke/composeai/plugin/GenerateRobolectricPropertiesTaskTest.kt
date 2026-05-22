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
 * The `sdk` resolution chain — `composePreview.sdkVersion` override > consumer `android.compileSdk`
 * > static default, with the auto-detect path clamped to Robolectric's max — is the load-bearing
 * > fix for issue #1248 (`PackageParser: Requires newer sdk version`), so each link gets a
 * > dedicated assertion here. The samples (`:samples:android`, `:samples:wear`) exercise the
 * > AGP-side `finalizeDsl` plumbing end-to-end via `:samples:android:composePreviewRenderAll`.
 */
class GenerateRobolectricPropertiesTaskTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `default emits sdk graphicsMode application shadows`() {
    val body = generate(useConsumerApplication = false, override = null, compileSdk = 36)
    assertThat(body).contains("sdk=36")
    assertThat(body).contains("graphicsMode=NATIVE")
    assertThat(body).contains("application=android.app.Application")
    assertThat(body).contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
  }

  @Test
  fun `useConsumerApplication drops application line but keeps sdk graphicsMode shadows`() {
    val body = generate(useConsumerApplication = true, override = null, compileSdk = 36)
    assertThat(body).contains("sdk=36")
    assertThat(body).contains("graphicsMode=NATIVE")
    assertThat(body).doesNotContain("application=")
    assertThat(body).contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
  }

  @Test
  fun `explicit composePreview sdkVersion override wins over consumer compileSdk`() {
    val body = generate(useConsumerApplication = false, override = 33, compileSdk = 36)
    assertThat(body).contains("sdk=33")
  }

  @Test
  fun `consumer compileSdk wins when override is unset`() {
    // Repro of the inverse of #1248 — confirm we haven't simply moved the hardcode from 35 to 36.
    val body = generate(useConsumerApplication = false, override = null, compileSdk = 35)
    assertThat(body).contains("sdk=35")
  }

  @Test
  fun `compileSdk above the Robolectric ceiling clamps to the ceiling`() {
    // Tiles consumers on compileSdk = 37 (transitive minCompileSdk from wear-tiles-renderer)
    // shouldn't see a hard build failure — clamp to MAX_SUPPORTED_SDK (36 for stable Robolectric
    // 4.16.1) and warn.
    val body = generate(useConsumerApplication = false, override = null, compileSdk = 37)
    assertThat(body).contains("sdk=${GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK}")
  }

  @Test
  fun `maxSupportedSdkOverride lifts the ceiling and skips the clamp`() {
    // Matrix snapshot probes pair a Robolectric snapshot with this override so an above-ceiling
    // compileSdk renders at its native level instead of clamping. Production consumers don't
    // touch this knob.
    val body =
      generate(
        useConsumerApplication = false,
        override = null,
        compileSdk = 37,
        maxSupportedSdkOverride = 37,
      )
    assertThat(body).contains("sdk=37")
  }

  @Test
  fun `explicit override above the ceiling fails strictly`() {
    // Auto-detect clamps; an explicit user pick is a configuration error worth surfacing.
    val exception =
      assertThrows(GradleException::class.java) {
        generate(useConsumerApplication = false, override = 99, compileSdk = 36)
      }
    assertThat(exception.message).contains("composePreview.sdkVersion = 99")
    assertThat(exception.message).contains("supported range")
  }

  @Test
  fun `explicit override below the floor fails strictly`() {
    val exception =
      assertThrows(GradleException::class.java) {
        generate(useConsumerApplication = false, override = 5, compileSdk = 36)
      }
    assertThat(exception.message).contains("composePreview.sdkVersion = 5")
  }

  @Test
  fun `compileSdk below the floor fails with a Gradle-friendly message`() {
    val exception =
      assertThrows(GradleException::class.java) {
        generate(useConsumerApplication = false, override = null, compileSdk = 15)
      }
    assertThat(exception.message).contains("compileSdk = 15")
    assertThat(exception.message).contains("composePreview.sdkVersion")
  }

  @Test
  fun `falls back to DEFAULT_SDK when neither override nor compileSdk is set`() {
    // AGP normally fails the build before `compileSdk` ends up unset, so this branch is mostly a
    // guard for unit-test setups that drive the task directly without an `android { … }` block.
    val body = generate(useConsumerApplication = false, override = null, compileSdk = null)
    assertThat(body).contains("sdk=${GenerateRobolectricPropertiesTask.DEFAULT_SDK}")
  }

  private fun generate(
    useConsumerApplication: Boolean,
    override: Int?,
    compileSdk: Int?,
    maxSupportedSdkOverride: Int? = null,
  ): String {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task =
      project.tasks
        .register(
          "composePreviewGenerateRobolectricProperties",
          GenerateRobolectricPropertiesTask::class.java,
        )
        .get()
    task.useConsumerApplication.set(useConsumerApplication)
    if (override != null) task.sdkOverride.set(override)
    if (compileSdk != null) task.consumerCompileSdk.set(compileSdk)
    if (maxSupportedSdkOverride != null) task.maxSupportedSdkOverride.set(maxSupportedSdkOverride)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    task.outputDir.set(
      tmp.newFolder("out-$override-$compileSdk-$useConsumerApplication-$maxSupportedSdkOverride")
    )
    task.generate()
    val file =
      task.outputDir.get().asFile.resolve("ee/schimke/composeai/renderer/robolectric.properties")
    return file.readText()
  }
}
