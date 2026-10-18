package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the generated `robolectric.properties`: `sdk`, `graphicsMode`, `shadows`, and the
 * `application` toggle. `sdk` / `graphicsMode` live here rather than in annotations so JUnit's
 * `AnnotationParser` doesn't resolve `android.app.Application` during discovery (#142).
 *
 * Each link of the `sdk` chain (override > `android.compileSdk` clamped to Robolectric's max >
 * default) gets an assertion, since it fixes #1248. The samples cover AGP's `finalizeDsl` plumbing
 * end-to-end.
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
  fun `the coil preview shadow is registered with its package instrumented`() {
    // Both lines are needed: the shadow only replaces `AsyncImagePainter.setPreview$…` if
    // `coil.compose` is instrumented (#2952).
    listOf(false, true).forEach { useConsumerApplication ->
      val body = generate(useConsumerApplication, override = null, compileSdk = 36)
      assertThat(body).contains("ee.schimke.composeai.renderer.ShadowAsyncImagePainter")
      assertThat(body).contains("instrumentedPackages=coil.compose")
    }
  }

  @Test
  fun `the paused-clock hwui shadow is registered for the render lane`() {
    // Without it Robolectric 4.17-beta-3+ paces hwui render-thread animations by JVM uptime rather
    // than the render clock, so animation captures weren't reproducible (#4578). The daemon
    // registers the same shadow in `SandboxHoldingRunner.getExtraShadows`; keep them together.
    listOf(false, true).forEach { useConsumerApplication ->
      val body = generate(useConsumerApplication, override = null, compileSdk = 36)
      assertThat(body).contains("ee.schimke.composeai.renderer.ShadowPausedClockHardwareRenderer")
    }
  }

  @Test
  fun `the wear clock shadow is registered with its target class instrumented`() {
    // Both lines needed, like coil: Wear's `ResourcesKt` (where `TimeText` reads the clock) isn't
    // instrumented by default (#3239). `WearTimeTextClockTest` uses the same entries.
    listOf(false, true).forEach { useConsumerApplication ->
      val body = generate(useConsumerApplication, override = null, compileSdk = 36)
      assertThat(body).contains("ee.schimke.composeai.renderer.ShadowWearTimeSource")
      assertThat(body).contains("androidx.wear.compose.materialcore.ResourcesKt")
    }
  }

  @Test
  fun `the Wear gesture input shadow is registered with its target class instrumented`() {
    listOf(false, true).forEach { useConsumerApplication ->
      val body = generate(useConsumerApplication, override = null, compileSdk = 36)
      assertThat(body).contains("ee.schimke.composeai.daemon.ShadowSdkGestureInputManager")
      assertThat(body)
        .contains("androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl")
    }
  }

  @Test
  fun `the permission-query tracker shadow is deliberately absent from the static lane`() {
    // #3698: `ShadowContextWrapperPermissionTracker` is daemon-only by decision.
    // `@PermissionPreview` still works here via `ShadowApplication`; the tracker only records
    // queried permissions for a payload served over the daemon's `data/fetch`, which this lane
    // can't answer. See `docs/DATA_PRODUCTS.md` before changing this.
    listOf(false, true).forEach { useConsumerApplication ->
      val body = generate(useConsumerApplication, override = null, compileSdk = 36)
      assertThat(body).doesNotContain("ShadowContextWrapperPermissionTracker")
    }
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
    // A compileSdk past the bundled Robolectric clamps to MAX_SUPPORTED_SDK with a warning, not a
    // failure.
    val body = generate(useConsumerApplication = false, override = null, compileSdk = 38)
    assertThat(body).contains("sdk=${GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK}")
  }

  @Test
  fun `maxSupportedSdkOverride lifts the ceiling and skips the clamp`() {
    // For SDK matrix snapshot probes rendering above the ceiling; not a production knob.
    val body =
      generate(
        useConsumerApplication = false,
        override = null,
        compileSdk = 38,
        maxSupportedSdkOverride = 38,
      )
    assertThat(body).contains("sdk=38")
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

  @Test
  fun `compileSdk 36 clamps to 35 when the build JVM is older than Java 21`() {
    // The Robolectric DefaultSdkProvider.verifySupportedSdk gate: SDK 36 (Baklava) needs JDK 21+.
    // On JDK 17 a consumer on compileSdk = 36 must still render — at 35 — not fail every preview.
    val body =
      generate(
        useConsumerApplication = false,
        override = null,
        compileSdk = 36,
        buildJavaMajor = 17,
      )
    assertThat(body)
      .contains("sdk=${GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK_BELOW_JAVA_21}")
    assertThat(body).contains("sdk=35")
  }

  @Test
  fun `compileSdk 36 renders at 36 when the build JVM is Java 21+`() {
    val body =
      generate(
        useConsumerApplication = false,
        override = null,
        compileSdk = 36,
        buildJavaMajor = 21,
      )
    assertThat(body).contains("sdk=36")
  }

  @Test
  fun `compileSdk 35 is unaffected by the Java 17 ceiling`() {
    val body =
      generate(
        useConsumerApplication = false,
        override = null,
        compileSdk = 35,
        buildJavaMajor = 17,
      )
    assertThat(body).contains("sdk=35")
  }

  @Test
  fun `explicit sdkVersion 36 fails strictly on Java 17`() {
    // On a JDK that can't bootstrap SDK 36, an explicit pick is a configuration error — fail fast
    // with the JDK reason rather than the opaque Robolectric UnsupportedOperationException.
    val exception =
      assertThrows(GradleException::class.java) {
        generate(
          useConsumerApplication = false,
          override = 36,
          compileSdk = 36,
          buildJavaMajor = 17,
        )
      }
    assertThat(exception.message).contains("composePreview.sdkVersion = 36")
    assertThat(exception.message).contains("JDK 21")
  }

  @Test
  fun `default also writes the daemon-package Application override`() {
    // The daemon's SandboxRunner can't read the renderer-package file and Robolectric 4.16 no
    // longer merges its `buildGlobalConfig`, so without this it runs the consumer's Application.
    val body = generateDaemon(useConsumerApplication = false)
    assertThat(body).contains("application=android.app.Application")
  }

  @Test
  fun `useConsumerApplication drops the daemon-package Application override`() {
    val body = generateDaemon(useConsumerApplication = true)
    assertThat(body).doesNotContain("application=")
  }

  /**
   * The app-tour lane's file is a sibling package of `…renderer`, so nothing merges into it:
   * omitting `application=` means "use the manifest", not "inherit the stub".
   */
  @Test
  fun `resolvedSdkIn reads back the sdk the task wrote, for the daemon`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task =
      project.tasks
        .register(
          "composePreviewGenerateRobolectricProperties",
          GenerateRobolectricPropertiesTask::class.java,
        )
        .get()
    task.useConsumerApplication.set(false)
    task.appTourUseConsumerApplication.set(false)
    task.consumerCompileSdk.set(37)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    task.buildJavaMajor.set(21)
    val out = tmp.newFolder("resolved-sdk-out")
    assertThat(GenerateRobolectricPropertiesTask.resolvedSdkIn(out)).isNull()
    task.outputDir.set(out)
    task.generate()
    assertThat(GenerateRobolectricPropertiesTask.resolvedSdkIn(out)).isEqualTo("37")
  }

  private fun generateAppTour(
    useConsumerApplication: Boolean,
    appTourUseConsumerApplication: Boolean,
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
    task.appTourUseConsumerApplication.set(appTourUseConsumerApplication)
    task.consumerCompileSdk.set(36)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    task.buildJavaMajor.set(21)
    task.outputDir.set(
      tmp.newFolder("apptour-out-$useConsumerApplication-$appTourUseConsumerApplication")
    )
    task.generate()
    return task.outputDir
      .get()
      .asFile
      .resolve("ee/schimke/composeai/apptour/robolectric.properties")
      .readText()
  }

  @Test
  fun `app-tour lane leaves the Application to the manifest by default`() {
    // Activities need their real Application (Hilt, Koin, AppComponentFactory); no `application=`
    // line means the manifest's.
    val body = generateAppTour(useConsumerApplication = false, appTourUseConsumerApplication = true)
    assertThat(body.lines().none { it.startsWith("application=") }).isTrue()
  }

  @Test
  fun `app-tour lane can be put back on the stub`() {
    val body =
      generateAppTour(useConsumerApplication = false, appTourUseConsumerApplication = false)
    assertThat(body.lines()).contains("application=android.app.Application")
  }

  @Test
  fun `useConsumerApplication true keeps the app-tour lane on the manifest`() {
    // The module-wide opt-in must not be undone by the app-tour flag: if composable previews are
    // already running the consumer's Application, activities certainly should be.
    val body = generateAppTour(useConsumerApplication = true, appTourUseConsumerApplication = false)
    assertThat(body.lines().none { it.startsWith("application=") }).isTrue()
  }

  @Test
  fun `app-tour lane pins the same sdk graphics and shadows as the composable lane`() {
    // A module must not render its activities at a different API level, or without the font / coil
    // / paused-clock shadows, than its composables.
    val appTour =
      generateAppTour(useConsumerApplication = false, appTourUseConsumerApplication = true)
    val composable = generate(useConsumerApplication = false, override = null, compileSdk = 36)
    val carried = { body: String ->
      body.lines().filter {
        it.startsWith("sdk=") ||
          it.startsWith("graphicsMode=") ||
          it.startsWith("shadows=") ||
          it.startsWith("instrumentedPackages=")
      }
    }
    assertThat(carried(appTour)).isEqualTo(carried(composable))
  }

  private fun generateDaemon(useConsumerApplication: Boolean): String {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task =
      project.tasks
        .register(
          "composePreviewGenerateRobolectricProperties",
          GenerateRobolectricPropertiesTask::class.java,
        )
        .get()
    task.useConsumerApplication.set(useConsumerApplication)
    task.appTourUseConsumerApplication.set(true)
    task.consumerCompileSdk.set(36)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    task.buildJavaMajor.set(21)
    task.outputDir.set(tmp.newFolder("daemon-out-$useConsumerApplication"))
    task.generate()
    return task.outputDir
      .get()
      .asFile
      .resolve("ee/schimke/composeai/daemon/robolectric.properties")
      .readText()
  }

  private fun generate(
    useConsumerApplication: Boolean,
    override: Int?,
    compileSdk: Int?,
    maxSupportedSdkOverride: Int? = null,
    // Default to a JDK-21+ build so the SDK-36 assertions exercise the jar ceiling, not the
    // JDK gate. Tests that target the gate pass `buildJavaMajor = 17` explicitly.
    buildJavaMajor: Int = 21,
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
    task.appTourUseConsumerApplication.set(true)
    if (override != null) task.sdkOverride.set(override)
    if (compileSdk != null) task.consumerCompileSdk.set(compileSdk)
    if (maxSupportedSdkOverride != null) task.maxSupportedSdkOverride.set(maxSupportedSdkOverride)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    task.buildJavaMajor.set(buildJavaMajor)
    task.outputDir.set(
      tmp.newFolder(
        "out-$override-$compileSdk-$useConsumerApplication-$maxSupportedSdkOverride-$buildJavaMajor"
      )
    )
    task.generate()
    val file =
      task.outputDir.get().asFile.resolve("ee/schimke/composeai/renderer/robolectric.properties")
    return file.readText()
  }

  @Test
  fun `pinAppClock false writes byte-identical files in every lane`() {
    // Pinned to the bytes this task wrote before `pinAppClock` existed: the opt-out
    // (`pinAppClock = false`, which wires an empty list) must restore them exactly. `clockPackages`
    // unset and explicitly empty both count as off.
    listOf(null, emptyList<String>()).forEach { packages ->
      val lanes = generateLanes(clockPackages = packages, name = "off-${packages != null}")
      assertThat(lanes.getValue("renderer")).isEqualTo(PRE_CLOCK_RENDERER)
      assertThat(lanes.getValue("apptour")).isEqualTo(PRE_CLOCK_APPTOUR)
      assertThat(lanes.getValue("daemon")).isEqualTo(PRE_CLOCK_DAEMON)
    }
  }

  @Test
  fun `pinAppClock on instruments the prefixes and registers ShadowConsumerClock in both lanes`() {
    val lanes =
      generateLanes(clockPackages = listOf("com.example.app.", "com.example.lib."), name = "on")
    listOf("renderer", "apptour").forEach { lane ->
      val lines = lanes.getValue(lane).lines()
      assertThat(lines)
        .contains(
          "instrumentedPackages=coil.compose,androidx.wear.compose.materialcore.ResourcesKt," +
            "androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl," +
            "com.example.app.,com.example.lib."
        )
      assertThat(lines)
        .contains(
          "shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat," +
            "ee.schimke.composeai.renderer.ShadowAsyncImagePainter," +
            "ee.schimke.composeai.renderer.ShadowWearTimeSource," +
            "ee.schimke.composeai.renderer.ShadowPausedClockHardwareRenderer," +
            "ee.schimke.composeai.daemon.ShadowSdkGestureInputManager," +
            "ee.schimke.composeai.renderer.ShadowConsumerClock"
        )
    }
    // Everything else in those lanes is untouched.
    assertThat(
        lanes.getValue("renderer").lines().filterNot {
          it.startsWith("instrumented") || it.startsWith("shadows=")
        }
      )
      .isEqualTo(
        PRE_CLOCK_RENDERER.lines().filterNot {
          it.startsWith("instrumented") || it.startsWith("shadows=")
        }
      )
    // The daemon configures its sandbox from `composeai.render.clockPackages` itself.
    assertThat(lanes.getValue("daemon")).isEqualTo(PRE_CLOCK_DAEMON)
  }

  @Test
  fun `clockPackagesOf drops blanks duplicates and protected roots`() {
    assertThat(
        clockPackagesOf(
          listOf(
            " com.example.app. ",
            "",
            "com.example.app.",
            "android.",
            "androidx.",
            "java",
            "kotlinx.coroutines.",
            "ee.schimke.composeai.renderer.",
            "ee.",
            "com.example.lib",
            ".",
            "..",
          )
        )
      )
      .containsExactly("com.example.app.", "androidx.", "com.example.lib")
      .inOrder()
  }

  @Test
  fun `the render JVM's clock prefixes resolve only at execution time`() {
    // #6039: an eager read during task configuration hit AGP's "configuration of project has not
    // completed yet" on the namespace the default derives from.
    var resolved = false
    val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
    val provider =
      AndroidPreviewSupport.ClockPackagesSystemPropProvider(
        project.provider {
          resolved = true
          listOf("com.example.app.", "com.example.lib.")
        }
      )
    assertThat(resolved).isFalse()
    assertThat(provider.asArguments())
      .containsExactly("-D$CLOCK_PACKAGES_PROPERTY=com.example.app.,com.example.lib.")
    assertThat(
        AndroidPreviewSupport.ClockPackagesSystemPropProvider(project.provider { emptyList() })
          .asArguments()
      )
      .isEmpty()
  }

  @Test
  fun `pinAppClock is on by default and the opt-out empties the prefixes`() {
    fun packages(gradleProperties: String?, configure: PreviewExtension.() -> Unit): List<String> {
      val dir = tmp.newFolder()
      if (gradleProperties != null) dir.resolve("gradle.properties").writeText(gradleProperties)
      val project = ProjectBuilder.builder().withProjectDir(dir).build()
      val extension =
        project.extensions.create("composePreview", PreviewExtension::class.java).apply(configure)
      return composeAiClockPackages(project, extension).get()
    }
    val namespace: PreviewExtension.() -> Unit = {
      appClockPackages.convention(listOf("com.example.app."))
    }
    assertThat(packages(null, namespace)).containsExactly("com.example.app.")
    assertThat(
        packages(null) {
          namespace()
          pinAppClock.set(false)
        }
      )
      .isEmpty()
    assertThat(packages("composePreview.pinAppClock=false\n", namespace)).isEmpty()
    assertThat(
        packages("composePreview.pinAppClock=true\n") {
          namespace()
          pinAppClock.set(false)
        }
      )
      .containsExactly("com.example.app.")
    // No namespace resolved: nothing is pinned, rather than an empty prefix matching every class.
    assertThat(packages(null) {}).isEmpty()
    assertThat(packages(null) { appClockPackages.set(listOf("", " ", ".")) }).isEmpty()
  }

  private fun generateLanes(clockPackages: List<String>?, name: String): Map<String, String> {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task =
      project.tasks
        .register(
          "composePreviewGenerateRobolectricProperties",
          GenerateRobolectricPropertiesTask::class.java,
        )
        .get()
    task.useConsumerApplication.set(false)
    task.appTourUseConsumerApplication.set(true)
    task.consumerCompileSdk.set(36)
    task.defaultSdk.set(GenerateRobolectricPropertiesTask.DEFAULT_SDK)
    task.buildJavaMajor.set(21)
    if (clockPackages != null) task.clockPackages.set(clockPackages)
    task.outputDir.set(tmp.newFolder("lanes-$name"))
    task.generate()
    val root = task.outputDir.get().asFile.resolve("ee/schimke/composeai")
    return listOf("renderer", "apptour", "daemon").associateWith {
      root.resolve("$it/robolectric.properties").readText()
    }
  }

  private companion object {
    val PRE_CLOCK_SHADOWS =
      "shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat," +
        "ee.schimke.composeai.renderer.ShadowAsyncImagePainter," +
        "ee.schimke.composeai.renderer.ShadowWearTimeSource," +
        "ee.schimke.composeai.renderer.ShadowPausedClockHardwareRenderer," +
        "ee.schimke.composeai.daemon.ShadowSdkGestureInputManager"
    val PRE_CLOCK_INSTRUMENTED =
      "instrumentedPackages=coil.compose,androidx.wear.compose.materialcore.ResourcesKt," +
        "androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl"

    val PRE_CLOCK_RENDERER =
      "# Generated by compose-ai-tools.\n" +
        "# Override the consumer's Application to a Robolectric-safe stub so\n" +
        "# preview rendering skips app-lifecycle side effects (DI, BridgingManager,\n" +
        "# Firebase, WorkManager, …). Set composePreview.useConsumerApplication = true\n" +
        "# to restore the manifest-declared Application.\n" +
        "application=android.app.Application\n" +
        "sdk=36\n" +
        "graphicsMode=NATIVE\n" +
        "$PRE_CLOCK_SHADOWS\n" +
        "$PRE_CLOCK_INSTRUMENTED\n"

    val PRE_CLOCK_APPTOUR =
      "# Generated by compose-ai-tools — the kind=ACTIVITY / kind=APP_TOUR render lane.\n" +
        "# Set composePreview.appTourUseConsumerApplication = false to render activities against\n" +
        "# a stub Application instead (they then skip the app's own onCreate, and any that need\n" +
        "# app-level DI will fail).\n" +
        "# Application omitted — Robolectric falls back to the manifest-declared Application, " +
        "which is what an Activity needs.\n" +
        "sdk=36\n" +
        "graphicsMode=NATIVE\n" +
        "$PRE_CLOCK_SHADOWS\n" +
        "$PRE_CLOCK_INSTRUMENTED\n"

    val PRE_CLOCK_DAEMON =
      "# Generated by compose-ai-tools.\n" +
        "# Override the consumer's Application to a Robolectric-safe stub for daemon-driven preview\n" +
        "# rendering, so app-lifecycle init (DI, Firebase, WorkManager, …) never runs and crashes\n" +
        "# the render sandbox. sdk/graphicsMode are supplied by @Config/@GraphicsMode on the\n" +
        "# daemon's SandboxRunner; only the Application override needs this file because Robolectric\n" +
        "# 4.16 no longer merges the deprecated buildGlobalConfig override over the manifest.\n" +
        "application=android.app.Application\n"
  }
}
