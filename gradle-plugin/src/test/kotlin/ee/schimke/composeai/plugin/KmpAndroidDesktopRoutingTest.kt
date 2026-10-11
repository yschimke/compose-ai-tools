package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.internal.TaskInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #248: `compose-preview` on a `com.android.kotlin.multiplatform.library` module routes through the
 * Desktop pipeline (`androidRuntimeClasspath`, `build/classes/kotlin/<target>/main`), not
 * Robolectric. Pinned with a synthetic project (no AGP/KGP) carrying the canonical configuration
 * name and class dirs.
 */
class KmpAndroidDesktopRoutingTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `desktop tasks resolve classes from build classes kotlin android main`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // Discovery filters class dirs to existing directories, so an empty placeholder suffices.
    project.layout.buildDirectory.dir("classes/kotlin/android/main").get().asFile.mkdirs()

    // The runtime configuration KMP-Android publishes for its single variant.
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val discoverTask = project.tasks.getByName("composePreviewDiscover") as DiscoverPreviewsTask

    val classDirPaths =
      discoverTask.classDirs.files.map { it.relativeTo(project.projectDir).invariantSeparatorsPath }

    // Without the KMP-Android output path the scan finds 0 previews (#248).
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
    // `dependencyJars` comes from the picked configuration; resolving it against an empty one must
    // yield nothing and not throw for a misnamed candidate.
    assertThat(discoverTask.dependencyJars.files).isEmpty()
  }

  @Test
  fun `desktop tasks prefer desktop over android runtime classpath when both exist`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    // Android target plus `jvm("desktop")`: `desktopRuntimeClasspath` must win, or the host JVM
    // gets `-android` artifacts and fails with `ClassNotFoundException: android.os.Parcelable`.
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    project.configurations.create("androidRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    // Both configurations must stay resolvable after registration (both are empty, so resolution
    // alone wouldn't distinguish them).
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
  fun `the bundle renderability gate does not apply to the Android registration`() {
    // On the desktop registration `androidRuntimeClasspath` is the unrenderable fallback; on the
    // Android registration it's the real runtime config of every KMP-Android module. Applying the
    // desktop check there skipped `composePreviewBundle` and made `bundle pack` fail.
    assertThat(ComposePreviewTasks.bundleRenderable("android", "androidRuntimeClasspath")).isTrue()
    assertThat(ComposePreviewTasks.bundleRenderable("android", "debugRuntimeClasspath")).isTrue()

    // The desktop registration keeps the gate exactly as it was.
    assertThat(ComposePreviewTasks.bundleRenderable("desktop", "androidRuntimeClasspath")).isFalse()
    assertThat(ComposePreviewTasks.bundleRenderable("desktop", "desktopRuntimeClasspath")).isTrue()
    assertThat(ComposePreviewTasks.bundleRenderable("desktop", "jvmRuntimeClasspath")).isTrue()
    assertThat(ComposePreviewTasks.bundleRenderable("desktop", "runtimeClasspath")).isTrue()
  }

  @Test
  fun `non-renderable module skips only the guard and daemon, never discover or render`() {
    // #1855: the classpath guard and daemon-start skip pure KMP-Android modules, but discover and
    // render must not be gated, since the Tooling-API model builder realizes them during CLI
    // detection.
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
  fun `backgroundSandboxBoot opt-out flows from the daemon extension onto the bootstrap task`() {
    // Only the extension → task `@Input` half; the descriptor's `systemProperties` would need a
    // resolvable daemon config (covered by DaemonBootstrapFunctionalTest).
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    // Explicitly opting OUT is the interesting direction now that the default is on.
    extension.daemon { backgroundSandboxBoot.set(false) }

    ComposePreviewTasks.registerDesktopTasks(project, extension)

    val daemon =
      project.tasks.getByName("composePreviewDaemonStart")
        as ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask
    assertThat(daemon.backgroundSandboxBoot.get()).isFalse()
  }

  @Test
  fun `backgroundSandboxBoot defaults to true on the bootstrap task`() {
    // Default-on must survive the extension -> task hop, not just the extension's convention.
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
    assertThat(daemon.backgroundSandboxBoot.get()).isTrue()
  }

  @Test
  fun `desktop tasks can be registered from afterEvaluate`() {
    // Backs the deferred lane decision in [ComposePreviewPlugin]: when `org.jetbrains.compose`
    // lands before KMP-Android, desktop registration waits for `afterEvaluate`.
    // `registerDesktopTasks` schedules its own `afterEvaluate` blocks, so this nests them, which
    // Gradle rejects once evaluation has finished; pinned here.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    project.configurations.create("desktopRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }

    project.afterEvaluate { ComposePreviewTasks.registerDesktopTasks(project, extension) }
    (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    // Registered, and reachable — the nested `afterEvaluate` blocks did not throw on the way.
    assertThat(project.tasks.findByName("composePreviewDiscover")).isNotNull()
    assertThat(project.tasks.findByName("composePreviewRender")).isNotNull()
    assertThat(project.tasks.findByName("composePreviewRenderAll")).isNotNull()
  }
}
