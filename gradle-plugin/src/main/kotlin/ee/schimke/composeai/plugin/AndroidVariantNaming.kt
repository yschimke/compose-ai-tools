package ee.schimke.composeai.plugin

/**
 * The AGP-generated names the Robolectric lane uses, per variant. Classic AGP derives them all from
 * the variant name ([classic]). KMP-Android names them after the KMP target and host-test
 * compilation instead:
 *
 * |                           |classic (`debug`)              |KMP-Android (`androidMain`)      |
 * |---------------------------|-------------------------------|---------------------------------|
 * |runtime classpath          |`debugRuntimeClasspath`        |`androidRuntimeClasspath`        |
 * |host-test bucket           |`testImplementation`           |`androidHostTestImplementation`  |
 * |unit-test runtime classpath|`debugUnitTestRuntimeClasspath`|`androidHostTestRuntimeClasspath`|
 * |unit-test config task      |`generateDebugUnitTestConfig`  |`generateAndroidHostTestConfig`  |
 * |main compile task          |`compileDebugKotlin`           |`compileAndroidMain`             |
 * |implementation bucket      |`debugImplementation`          |`androidMainImplementation`      |
 * |class output               |`tmp/kotlin-classes/debug`     |`classes/kotlin/android/main`    |
 *
 * Names are derived, not guessed: [kmpAndroid] takes the host-test component's name from
 * `variant.unitTest`, so renamed compilations work.
 */
internal data class AndroidVariantNaming(
  /** The AGP variant name — `debug`, `demoRelease`, `androidMain`. */
  val variantName: String,
  /** The module's own runtime classpath, the render graph's starting point. */
  val runtimeClasspath: String,
  /**
   * Unit-test runtime classpath (Robolectric, `android.jar`, merged-resource APK). Null without a
   * host-test component (KMP-Android's default).
   */
  val unitTestRuntimeClasspath: String?,
  /** The declarable bucket plugin-injected dependencies are added to. */
  val implementation: String,
  /** Declarable bucket for host-test-only dependencies; KMP has none until `withHostTest { }`. */
  val testImplementation: String?,
  /**
   * AGP's `test_config.properties` generator, which Robolectric uses to find
   * `apk-for-local-test.ap_`. Null like [unitTestRuntimeClasspath].
   */
  val unitTestConfigTask: String?,
  /**
   * That generator's output dir (relative to build dir). No producer is wired, so consumers must
   * depend on [unitTestConfigTask].
   */
  val unitTestConfigDir: String?,
  /**
   * AGP's host-test `Test` task; its classpath is the only source of the merged unit-test `R.jar`.
   * Null without a host-test component.
   */
  val unitTestTask: String?,
  /**
   * The `apk_for_local_test` dir, declared as a bundle input so resource-only changes invalidate
   * the cache.
   */
  val apkForLocalTest: String?,
  /**
   * Extra class dirs relative to the build dir; KMP's target output lives under
   * `classes/kotlin/<target>/…`, and the target may be renamed.
   */
  val extraClassDirs: List<String>,
) {
  val capVariant: String = variantName.replaceFirstChar { it.uppercase() }

  /**
   * Where an in-process (BTA) compile writes classes: the same dir as Gradle's Kotlin compile,
   * which the daemon's child classloader loads. AGP 9 built-in Kotlin uses
   * `intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`; the standalone
   * plugin uses `tmp/kotlin-classes/<variant>`. KMP is stage-2 ineligible.
   */
  fun btaOutputDir(kotlinAndroidPluginApplied: Boolean): String =
    if (kotlinAndroidPluginApplied) "tmp/kotlin-classes/$variantName"
    else "intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"

  companion object {
    /**
     * Naming for [variantName], using the KMP-Android mapping when that plugin is applied. Without
     * a `Variant` (e.g. the Tooling API model builder), the host-test compilation
     * `<target>HostTest` is derived and verified against its configuration.
     */
    fun forProject(project: org.gradle.api.Project, variantName: String): AndroidVariantNaming {
      if (!project.pluginManager.hasPlugin("com.android.kotlin.multiplatform.library")) {
        return classic(variantName)
      }
      val target = variantName.removeSuffix("Main").ifEmpty { "android" }
      val targetName =
        if (project.configurations.findByName("${target}RuntimeClasspath") != null) target
        else "android"
      val hostTest = "${targetName}HostTest"
      return kmpAndroid(
        variantName = variantName,
        targetName = targetName,
        unitTestName =
          if (project.configurations.findByName("${hostTest}RuntimeClasspath") != null) hostTest
          else null,
      )
    }

    fun classic(variantName: String): AndroidVariantNaming {
      val cap = variantName.replaceFirstChar { it.uppercase() }
      return AndroidVariantNaming(
        variantName = variantName,
        runtimeClasspath = "${variantName}RuntimeClasspath",
        unitTestRuntimeClasspath = "${variantName}UnitTestRuntimeClasspath",
        implementation = "${variantName}Implementation",
        testImplementation = "testImplementation",
        unitTestConfigTask = "generate${cap}UnitTestConfig",
        unitTestConfigDir =
          "intermediates/unit_test_config_directory/${variantName}UnitTest/generate${cap}UnitTestConfig/out",
        unitTestTask = "test${cap}UnitTest",
        apkForLocalTest = "intermediates/apk_for_local_test/${variantName}UnitTest",
        // Classic AGP's own class outputs are listed at the call site; KMP adds the target dir.
        extraClassDirs = emptyList(),
      )
    }

    /**
     * [targetName] is the KMP target (`android` unless renamed); [unitTestName] is
     * `variant.unitTest?.name`, null without `withHostTest { }`.
     */
    fun kmpAndroid(
      variantName: String,
      targetName: String,
      unitTestName: String?,
    ): AndroidVariantNaming {
      val cap = unitTestName?.replaceFirstChar { it.uppercase() }
      return AndroidVariantNaming(
        variantName = variantName,
        runtimeClasspath = "${targetName}RuntimeClasspath",
        unitTestRuntimeClasspath = unitTestName?.let { "${it}RuntimeClasspath" },
        implementation = "${variantName}Implementation",
        testImplementation = unitTestName?.let { "${it}Implementation" },
        unitTestConfigTask = cap?.let { "generate${it}Config" },
        unitTestConfigDir =
          if (unitTestName == null || cap == null) null
          else "intermediates/unit_test_config_directory/$unitTestName/generate${cap}Config/out",
        unitTestTask = cap?.let { "test$it" },
        apkForLocalTest = unitTestName?.let { "intermediates/apk_for_local_test/$it" },
        extraClassDirs =
          listOf(
            // `androidTarget()` + `com.android.library` (#1492): compilation named after the
            // variant.
            "classes/kotlin/$targetName/$variantName",
            // `com.android.kotlin.multiplatform.library` (issue #248): one compilation, `main`.
            "classes/kotlin/$targetName/main",
          ),
      )
    }
  }
}
