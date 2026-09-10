package ee.schimke.composeai.plugin

/**
 * The AGP-generated names the Robolectric lane reaches for, resolved per variant.
 *
 * Classic AGP (`com.android.application` / `com.android.library`) derives every one of them from
 * the variant name: variant `debug` gives `debugRuntimeClasspath`, `debugUnitTestRuntimeClasspath`,
 * `generateDebugUnitTestConfig`, and so on. [classic] is that convention written down.
 *
 * `com.android.kotlin.multiplatform.library` — AGP 9's replacement for nesting
 * `com.android.library` inside KMP — does not. Its single variant is called `androidMain`, but the
 * configurations and tasks around it are named after the KMP *target* (`android`) and the host-test
 * *compilation* (`androidHostTest`) instead, so four of the six names stop matching:
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
 * The implementation bucket is the one that agrees by coincidence — KMP names it after the source
 * set, which for this plugin IS the variant name. It is listed here anyway so the mapping is
 * readable as a whole rather than as a set of exceptions.
 *
 * Every name is derived, never guessed: [kmpAndroid] takes the host-test component's own name from
 * `variant.unitTest`, so a consumer who renames the compilation is followed rather than broken.
 */
internal data class AndroidVariantNaming(
  /** The AGP variant name — `debug`, `demoRelease`, `androidMain`. */
  val variantName: String,
  /** The module's own runtime classpath, the render graph's starting point. */
  val runtimeClasspath: String,
  /**
   * The unit-test runtime classpath. Robolectric, `android.jar` and the merged-resource APK reach
   * the renderer through it. Null when the module has no host-test component at all, which is the
   * KMP-Android default: `withHostTest { }` is opt-in.
   */
  val unitTestRuntimeClasspath: String?,
  /** The declarable bucket plugin-injected dependencies are added to. */
  val implementation: String,
  /**
   * The declarable bucket for host-test-only dependencies — `ui-test-manifest`, `ui-test-junit4`,
   * the renderer's own JUnit surface. Classic AGP calls it `testImplementation` whatever the
   * variant; KMP names it after the host-test compilation, and has none until `withHostTest { }`.
   */
  val testImplementation: String?,
  /**
   * AGP's generator for `com/android/tools/test_config.properties` — the file Robolectric reads to
   * find `apk-for-local-test.ap_`. Null for the same reason as [unitTestRuntimeClasspath].
   */
  val unitTestConfigTask: String?,
  /**
   * Where that generator writes, relative to the build directory. Null when there is no generator.
   * A bare path with no producer wired in, so consumers must `dependsOn` [unitTestConfigTask] — see
   * the call site in `AndroidPreviewSupport`.
   */
  val unitTestConfigDir: String?,
  /**
   * AGP's own `Test` task for the host-test component. The render classpath is appended with its
   * `classpath` and `testClassesDirs`, which is the only place the merged unit-test `R.jar` appears
   * — dependency R classes (`androidx.lifecycle.runtime.R`) are reachable through nothing else.
   * Null when there is no host-test component.
   */
  val unitTestTask: String?,
  /**
   * AGP's merged unit-test resource APK directory (`apk_for_local_test`), relative to the build
   * directory. `BundlePreviewTask` reads the real APK during its action, so this has to be declared
   * as an input or a resource-only change leaves a cache hit carrying stale resources.
   */
  val apkForLocalTest: String?,
  /**
   * Extra compiled-class directories beyond the classic AGP ones, relative to the build directory.
   * On KMP the target's own output lives under `classes/kotlin/<target>/…`, and the target is NOT
   * always `android` — a consumer who renames it gets `classes/kotlin/mobile/main`, which the
   * render classpath has to carry or `composePreviewRender` cannot load a class discovery already
   * found.
   */
  val extraClassDirs: List<String>,
) {
  val capVariant: String = variantName.replaceFirstChar { it.uppercase() }

  companion object {
    /**
     * The naming for [variantName] on [project], picking the KMP-Android mapping when that plugin
     * is applied. The host-test compilation is derived (`<target>HostTest`) and verified against
     * the configuration it names rather than assumed, because this entry point has no `Variant` to
     * read `unitTest` off — it exists for callers outside `onVariants`, the Tooling API model
     * builder above all, which resolves the same configurations for `compose-preview doctor`.
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
     * [targetName] is the KMP target the plugin creates — `android` unless the consumer renamed it.
     * [unitTestName] is `variant.unitTest?.name` (`androidHostTest` by default), null when the
     * consumer never called `withHostTest { }`.
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
            // `androidTarget()` + `com.android.library` (issue #1492): compilation named after
            // the variant.
            "classes/kotlin/$targetName/$variantName",
            // `com.android.kotlin.multiplatform.library` (issue #248): one compilation, `main`.
            "classes/kotlin/$targetName/main",
          ),
      )
    }
  }
}
