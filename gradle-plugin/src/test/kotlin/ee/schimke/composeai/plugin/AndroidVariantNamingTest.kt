package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The AGP name mapping the Robolectric lane runs on.
 *
 * Every value here was read off a real `com.android.kotlin.multiplatform.library` module rather
 * than inferred: `:samples:cmp-android-robolectric` is the fixture, and its configurations are
 * `androidRuntimeClasspath` / `androidHostTestRuntimeClasspath`, its host-test config task is
 * `generateAndroidHostTestConfig`, and its AGP test task is `testAndroidHostTest`. Getting any one
 * of them wrong does not fail loudly — `findByName` returns null and the render silently loses the
 * merged R classes or the resource APK — so they are pinned.
 */
class AndroidVariantNamingTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `classic AGP derives every name from the variant`() {
    val naming = AndroidVariantNaming.classic("debug")

    assertThat(naming.variantName).isEqualTo("debug")
    assertThat(naming.capVariant).isEqualTo("Debug")
    assertThat(naming.runtimeClasspath).isEqualTo("debugRuntimeClasspath")
    assertThat(naming.unitTestRuntimeClasspath).isEqualTo("debugUnitTestRuntimeClasspath")
    assertThat(naming.implementation).isEqualTo("debugImplementation")
    assertThat(naming.testImplementation).isEqualTo("testImplementation")
    assertThat(naming.unitTestConfigTask).isEqualTo("generateDebugUnitTestConfig")
    assertThat(naming.unitTestTask).isEqualTo("testDebugUnitTest")
    assertThat(naming.unitTestConfigDir)
      .isEqualTo(
        "intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out"
      )
  }

  @Test
  fun `a flavoured variant keeps the same derivation`() {
    val naming = AndroidVariantNaming.classic("demoRelease")

    assertThat(naming.runtimeClasspath).isEqualTo("demoReleaseRuntimeClasspath")
    assertThat(naming.unitTestTask).isEqualTo("testDemoReleaseUnitTest")
  }

  @Test
  fun `KMP-Android names the target and the host-test compilation, not the variant`() {
    val naming =
      AndroidVariantNaming.kmpAndroid(
        variantName = "androidMain",
        targetName = "android",
        unitTestName = "androidHostTest",
      )

    // The variant is `androidMain`, but almost nothing is named after it.
    assertThat(naming.variantName).isEqualTo("androidMain")
    assertThat(naming.runtimeClasspath).isEqualTo("androidRuntimeClasspath")
    assertThat(naming.unitTestRuntimeClasspath).isEqualTo("androidHostTestRuntimeClasspath")
    assertThat(naming.testImplementation).isEqualTo("androidHostTestImplementation")
    assertThat(naming.unitTestConfigTask).isEqualTo("generateAndroidHostTestConfig")
    assertThat(naming.unitTestTask).isEqualTo("testAndroidHostTest")
    assertThat(naming.unitTestConfigDir)
      .isEqualTo(
        "intermediates/unit_test_config_directory/androidHostTest/generateAndroidHostTestConfig/out"
      )
    // The one that agrees with the classic derivation, because KMP names the declarable bucket
    // after the source set and the source set IS the variant here.
    assertThat(naming.implementation).isEqualTo("androidMainImplementation")
  }

  @Test
  fun `no host test leaves every unit-test name unset rather than guessed`() {
    // `withHostTest { }` is opt-in on KMP-Android. Naming a configuration that does not exist
    // would resolve to null at every call site anyway; saying so here is what lets
    // `kmpAndroidWantsRobolectric` refuse the lane instead of rendering without a classpath.
    val naming =
      AndroidVariantNaming.kmpAndroid(
        variantName = "androidMain",
        targetName = "android",
        unitTestName = null,
      )

    assertThat(naming.runtimeClasspath).isEqualTo("androidRuntimeClasspath")
    assertThat(naming.unitTestRuntimeClasspath).isNull()
    assertThat(naming.testImplementation).isNull()
    assertThat(naming.unitTestConfigTask).isNull()
    assertThat(naming.unitTestConfigDir).isNull()
    assertThat(naming.unitTestTask).isNull()
  }

  @Test
  fun `a renamed KMP target is followed rather than assumed`() {
    val naming =
      AndroidVariantNaming.kmpAndroid(
        variantName = "mobileMain",
        targetName = "mobile",
        unitTestName = "mobileHostTest",
      )

    assertThat(naming.runtimeClasspath).isEqualTo("mobileRuntimeClasspath")
    assertThat(naming.unitTestTask).isEqualTo("testMobileHostTest")
  }

  @Test
  fun `the target is read back off the variant name and verified against the configuration`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.configurations.create("androidRuntimeClasspath")

    assertThat(AndroidPreviewSupport.kmpAndroidTargetName(project, "androidMain"))
      .isEqualTo("android")
  }

  @Test
  fun `an unrecognisable variant name falls back to the default target`() {
    // `<target>Main` is the KMP source-set convention and holds for every shape seen so far. If a
    // future AGP breaks it, addressing `androidRuntimeClasspath` — the name the plugin has always
    // published — beats addressing a configuration derived from a name that no longer means what
    // it used to.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.configurations.create("androidRuntimeClasspath")

    assertThat(AndroidPreviewSupport.kmpAndroidTargetName(project, "someOtherVariant"))
      .isEqualTo("android")
  }

  @Test
  fun `a KMP-Android module that did not opt in keeps the Desktop lane`() {
    // The guarantee every existing `com.android.kotlin.multiplatform.library` consumer relies on:
    // adding a host test, or upgrading the plugin, must not silently move them onto Robolectric.
    var warned = false
    assertThat(
        AndroidPreviewSupport.kmpAndroidLaneDecision(optedIn = false, hasHostTest = true) {
          warned = true
        }
      )
      .isFalse()
    // Not a mistake, so not a warning — this is the default path, not a misconfiguration.
    assertThat(warned).isFalse()
  }

  @Test
  fun `opting in with a host test takes the Robolectric lane`() {
    assertThat(AndroidPreviewSupport.kmpAndroidLaneDecision(optedIn = true, hasHostTest = true))
      .isTrue()
  }

  @Test
  fun `opting in without a host test warns and falls back rather than failing`() {
    // `withHostTest { }` is the consumer's to write — the plugin cannot add it (AGP rejects a
    // second call). Asking for a lane that cannot be built is a build-script mistake, but failing
    // the build over it would take away the previews Desktop can still draw.
    var warning: String? = null
    val robolectric =
      AndroidPreviewSupport.kmpAndroidLaneDecision(optedIn = true, hasHostTest = false) {
        warning = AndroidPreviewSupport.kmpAndroidMissingHostTestMessage(":shared")
      }

    assertThat(robolectric).isFalse()
    assertThat(warning).contains("withHostTest")
    assertThat(warning).contains("isIncludeAndroidResources")
    assertThat(warning).contains(":shared")
  }

  @Test
  fun `the opt-in is off by default`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    assertThat(extension.kmpAndroidRobolectric.get()).isFalse()
  }

  @Test
  fun `KMP class dirs and the resource APK follow the target, not the literal android`() {
    // A renamed target compiles into `classes/kotlin/<target>/…` and packages into
    // `apk_for_local_test/<hostTest>`. Hardcoding `android` / `${variant}UnitTest` leaves the
    // render classpath unable to load a class discovery already found, and leaves
    // `BundlePreviewTask` reading an APK it never declared as an input — a cache hit then keeps
    // stale resources.
    val naming =
      AndroidVariantNaming.kmpAndroid(
        variantName = "mobileMain",
        targetName = "mobile",
        unitTestName = "mobileHostTest",
      )

    assertThat(naming.extraClassDirs)
      .containsExactly("classes/kotlin/mobile/mobileMain", "classes/kotlin/mobile/main")
    assertThat(naming.apkForLocalTest).isEqualTo("intermediates/apk_for_local_test/mobileHostTest")
  }

  @Test
  fun `classic AGP contributes no KMP class dirs and keeps its own APK path`() {
    val naming = AndroidVariantNaming.classic("debug")

    assertThat(naming.extraClassDirs).isEmpty()
    assertThat(naming.apkForLocalTest).isEqualTo("intermediates/apk_for_local_test/debugUnitTest")
  }

  @Test
  fun `forProject falls back to classic naming off a KMP-Android module`() {
    // The Tooling API model builder has no `Variant` to read `unitTest` off, so it resolves the
    // mapping from the project. On anything that is not KMP-Android that must be the classic
    // derivation, unchanged.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    val naming = AndroidVariantNaming.forProject(project, "debug")

    assertThat(naming.runtimeClasspath).isEqualTo("debugRuntimeClasspath")
    assertThat(naming.unitTestRuntimeClasspath).isEqualTo("debugUnitTestRuntimeClasspath")
  }
}
