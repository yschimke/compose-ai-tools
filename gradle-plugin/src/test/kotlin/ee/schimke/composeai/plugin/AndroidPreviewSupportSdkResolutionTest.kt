package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the resolution chain used to fill `sdk=N` in the generated `robolectric.properties`:
 * extension override → consumer `android.compileSdk` → static fallback. The chain is the
 * load-bearing fix for issue #1248 (`PackageParser: Requires newer sdk version` when a `compileSdk
 * = 36` consumer hit our previous `sdk=35` hardcode), so each link gets a dedicated assertion here
 * — applying real AGP just to cover this would dominate `:gradle-plugin:test`'s wall-clock.
 *
 * The samples (`:samples:android`, `:samples:wear`) inherit `compileSdk = 36` from the project's
 * `composeai.android-conventions` plugin and exercise the consumer-compileSdk branch end-to-end via
 * `:samples:android:renderAllPreviews`; this test pins the unit-level behaviour so a regression in
 * the chain is caught before reaching that integration run.
 */
class AndroidPreviewSupportSdkResolutionTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `explicit composePreview sdkVersion override wins over consumer compileSdk`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extensionOverride = project.objects.property(Int::class.java).apply { set(33) }
    val consumerCompileSdk = project.objects.property(Int::class.java).apply { set(36) }

    val resolved =
      AndroidPreviewSupport.resolveRobolectricSdk(extensionOverride, consumerCompileSdk)

    assertThat(resolved.get()).isEqualTo(33)
  }

  @Test
  fun `consumer compileSdk wins when override is unset`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extensionOverride = project.objects.property(Int::class.java)
    val consumerCompileSdk = project.objects.property(Int::class.java).apply { set(36) }

    val resolved =
      AndroidPreviewSupport.resolveRobolectricSdk(extensionOverride, consumerCompileSdk)

    assertThat(resolved.get()).isEqualTo(36)
  }

  @Test
  fun `consumer on compileSdk 35 still resolves to 35, no hardcoded ceiling`() {
    // Repro of the inverse of #1248 — confirm we haven't simply moved the hardcode from 35 to 36.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extensionOverride = project.objects.property(Int::class.java)
    val consumerCompileSdk = project.objects.property(Int::class.java).apply { set(35) }

    val resolved =
      AndroidPreviewSupport.resolveRobolectricSdk(extensionOverride, consumerCompileSdk)

    assertThat(resolved.get()).isEqualTo(35)
  }

  @Test
  fun `falls back to DEFAULT_SDK when neither override nor consumer compileSdk is set`() {
    // AGP normally fails the build before `compileSdk` ends up unset, so this branch is mostly a
    // guard for unit-test setups that drive the task directly without an `android { … }` block.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extensionOverride = project.objects.property(Int::class.java)
    val consumerCompileSdk = project.objects.property(Int::class.java)

    val resolved =
      AndroidPreviewSupport.resolveRobolectricSdk(extensionOverride, consumerCompileSdk)

    assertThat(resolved.get()).isEqualTo(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
  }
}
