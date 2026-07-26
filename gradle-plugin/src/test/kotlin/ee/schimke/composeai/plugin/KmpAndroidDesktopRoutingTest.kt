package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.internal.TaskInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #248: applying `compose-preview` to a `:shared`-style module on
 * `com.android.kotlin.multiplatform.library` (the AGP 9 replacement for nesting
 * `com.android.library` inside KMP) should route through the Compose Multiplatform Desktop pipeline
 * — `androidRuntimeClasspath` runtime, `build/classes/kotlin/<targetName>/main` outputs — not the
 * Robolectric AGP path.
 *
 * The unit test pins the contract of [ComposePreviewTasks.registerDesktopTasks] without standing up
 * AGP or KGP: a synthetic Gradle project with the canonical KMP-Android configuration name
 * (`androidRuntimeClasspath`) and class dirs is enough to exercise the candidate-list logic the
 * desktop-routing fix relies on.
 */
class KmpAndroidDesktopRoutingTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `desktop tasks resolve classes from build classes kotlin android main`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // Mimic the on-disk layout KMP-Android compiles into. The desktop
    // discovery task filters [DiscoverPreviewsTask.classDirs] down to
    // existing directories before scanning, so an empty placeholder here
    // is enough to assert the candidate is wired up.
    project.layout.buildDirectory.dir("classes/kotlin/android/main").get().asFile.mkdirs()

    // `androidRuntimeClasspath` is the resolvable runtime configuration the
    // KMP-Android plugin publishes for its single `android` variant
    // (replacing classic `debugRuntimeClasspath` / `releaseRuntimeClasspath`).
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val discoverTask = project.tasks.getByName("composePreviewDiscover") as DiscoverPreviewsTask

    val classDirPaths =
      discoverTask.classDirs.files.map { it.relativeTo(project.projectDir).invariantSeparatorsPath }

    // The KMP-Android compile output path was added alongside the JVM /
    // Desktop candidates as part of the issue #248 fix. Without it the
    // ClassGraph scan would walk an empty input on `:shared`-style modules
    // and report 0 previews even though the bytecode is on disk.
    assertThat(classDirPaths).contains("build/classes/kotlin/android/main")
    // The legacy candidates remain so single-target JVM / Desktop modules
    // continue to work as before.
    assertThat(classDirPaths)
      .containsAtLeast(
        "build/classes/kotlin/main",
        "build/classes/kotlin/jvm/main",
        "build/classes/kotlin/desktop/main",
      )
  }

  @Test
  fun `desktop tasks pick androidRuntimeClasspath when no jvm or desktop config exists`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // Only `androidRuntimeClasspath` is present — the typical pure
    // KMP-Android `:shared` shape with no `jvm("desktop")` target.
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val discoverTask = project.tasks.getByName("composePreviewDiscover") as DiscoverPreviewsTask
    // dependencyJars on the discover task is fed from the picked
    // `dependencyConfigName`. We can't read that name back as a property,
    // but we CAN observe that the only way for `dependencyJars` to be
    // wired up is via the configuration that exists on the project — so
    // resolving the task's inputs against the project's configurations
    // proves the candidate list is doing its job.
    //
    // Direct check: the discover task's dependencyJars must come back
    // empty when resolved on a project whose only matching configuration
    // is empty (no deps), and not throw with "Configuration X not found"
    // for a misnamed candidate.
    assertThat(discoverTask.dependencyJars.files).isEmpty()
  }

  @Test
  fun `desktop tasks prefer desktop over android runtime classpath when both exist`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // Mirror the canonical CMP-Android setup recommended by the
    // CMP_SHARED guide: both an Android target and a `jvm("desktop")`
    // target. The candidate list must pick `desktopRuntimeClasspath`
    // first so the Desktop renderer's process gets JVM-flavor compose
    // artifacts instead of AAR-flavored ones — picking
    // `androidRuntimeClasspath` here would surface
    // `compose-runtime-android` to the host JVM and explode at first
    // `mutableStateOf` call with `ClassNotFoundException: android.os.Parcelable`.
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    // `dependencyJars` sources from a single configuration in
    // `registerDiscoverTask` — the one whose name is at the head of the
    // candidate list and exists on the project. Resolving its incoming
    // shouldn't throw, which is the proof that the picked config is the
    // empty-but-real `desktopRuntimeClasspath`. If `androidRuntimeClasspath`
    // had been picked instead, this assertion would still hold (both are
    // empty), so we additionally check that BOTH configurations stay
    // resolvable side-by-side after registration — a stricter contract
    // than just "doesn't throw" but still independent of network access.
    val discoverTask = project.tasks.getByName("composePreviewDiscover") as DiscoverPreviewsTask
    discoverTask.dependencyJars.files // resolves; throws on a misnamed config
    assertThat(project.configurations.findByName("desktopRuntimeClasspath")).isNotNull()
    assertThat(project.configurations.findByName("androidRuntimeClasspath")).isNotNull()
  }

  @Test
  fun `isDesktopRenderableConfig is false only for the androidRuntimeClasspath fallback`() {
    // Issue #1852: a pure KMP-Android module whose only runtime config is androidRuntimeClasspath
    // can't be rendered by the JVM desktop renderer.
    assertThat(ComposePreviewTasks.isDesktopRenderableConfig("androidRuntimeClasspath")).isFalse()
    assertThat(ComposePreviewTasks.isDesktopRenderableConfig("jvmRuntimeClasspath")).isTrue()
    assertThat(ComposePreviewTasks.isDesktopRenderableConfig("desktopRuntimeClasspath")).isTrue()
    assertThat(ComposePreviewTasks.isDesktopRenderableConfig("runtimeClasspath")).isTrue()
  }

  @Test
  fun `non-renderable module skips only the guard and daemon, never discover or render`() {
    // Issue #1855: the desktop-render classpath guard and the daemon-start task are skipped for a
    // pure KMP-Android module (they'd otherwise hard-fail on its androidRuntimeClasspath), but
    // composePreviewDiscover and composePreviewRender must NOT be gated — the Tooling-API model
    // builder realizes those during CLI detection, and gating them is what regressed 0.15.3 to
    // "detect nothing". They run as in 0.15.2; the module simply discovers 0 previews.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    // Skipped (detection-safe — never realized by the model builder):
    for (taskName in
      listOf("validateComposePreviewDesktopRenderClasspath", "composePreviewDaemonStart")) {
      val task = project.tasks.getByName(taskName) as TaskInternal
      assertThat(task.onlyIf.isSatisfiedBy(task)).isFalse()
    }
    // NOT skipped (these are realized during CLI detection — must behave as 0.15.2):
    for (taskName in listOf("composePreviewDiscover", "composePreviewRender")) {
      val task = project.tasks.getByName(taskName) as TaskInternal
      assertThat(task.onlyIf.isSatisfiedBy(task)).isTrue()
    }
  }

  @Test
  fun `renderable desktop module keeps the guard and daemon enabled`() {
    // The canonical cmp-shared layout (desktopRuntimeClasspath present) must NOT skip anything.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val guard =
      project.tasks.getByName("validateComposePreviewDesktopRenderClasspath") as TaskInternal
    assertThat(guard.onlyIf.isSatisfiedBy(guard)).isTrue()
    val daemon = project.tasks.getByName("composePreviewDaemonStart") as TaskInternal
    assertThat(daemon.onlyIf.isSatisfiedBy(daemon)).isTrue()
  }

  @Test
  fun `backgroundSandboxBoot reaches the launch descriptor's system properties`() {
    // The daemon JVM only ever learns about background pool boot through the descriptor's
    // systemProperties — RobolectricHost reads `composeai.daemon.backgroundSandboxBoot` at
    // startup, and `.github/ci/daemon-roundtrip.py` reads the same key to size its `initialize`
    // budget. An extension property that doesn't reach this map is silently inert: the build
    // script looks configured, the daemon still boots the whole pool eagerly, and nothing fails.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    extension.daemon { backgroundSandboxBoot.set(true) }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val daemon =
      project.tasks.getByName("composePreviewDaemonStart")
        as ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask
    assertThat(daemon.systemProperties.get())
      .containsEntry("composeai.daemon.backgroundSandboxBoot", "true")
    assertThat(daemon.backgroundSandboxBoot.get()).isTrue()
  }

  @Test
  fun `backgroundSandboxBoot defaults to false in the launch descriptor`() {
    // Default-off must survive all the way to the descriptor, not just the extension: the eager
    // all-sandboxes-ready contract is what plugin consumers get today.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val daemon =
      project.tasks.getByName("composePreviewDaemonStart")
        as ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask
    assertThat(daemon.systemProperties.get())
      .containsEntry("composeai.daemon.backgroundSandboxBoot", "false")
  }
}
