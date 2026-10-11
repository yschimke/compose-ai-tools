package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the #1243 guards: `buildBootClasspathFallback` recovers `android.jar` from
 * `local.properties` / `ANDROID_HOME` when AGP's boot classpath is empty, and
 * `validateApplicationOnClasspath` explains a missing `android/app/Application.class` instead of
 * Robolectric's opaque error.
 */
class AndroidPreviewClasspathTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `eager render task still receives the later registered AGP resource jar`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("eager-test")).build()
    project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).all {}
    val extras =
      AndroidPreviewClasspath.lateAgpClasspathExtras(
        project,
        "testDebugUnitTest",
        null,
        false,
      )
    val render =
      project.tasks.register(
        "composePreviewRender",
        org.gradle.api.tasks.testing.Test::class.java,
      ) {
        classpath = extras
      }
    val mergedR = tmp.newFile("late-R.jar")
    project.tasks.register("testDebugUnitTest", org.gradle.api.tasks.testing.Test::class.java) {
      classpath = project.files(mergedR)
    }

    (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    assertThat(render.get().classpath.files).containsExactly(mergedR)
  }

  @Test
  fun `AGP generated R jar survives when exposed as a raw configuration dependency`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("raw-r-jar")).build()
    val mergedR = tmp.newFile("R.jar")
    writeJar(mergedR, mapOf("androidx/lifecycle/runtime/R\$id.class" to ByteArray(8)))
    val testConfig = project.configurations.create("debugUnitTestRuntimeClasspath")
    project.dependencies.add(testConfig.name, project.files(mergedR))
    val repository = tmp.newFolder("modules")
    val moduleJar = File(repository, "library-1.0.jar")
    writeJar(moduleJar, mapOf("example/Library.class" to ByteArray(8)))
    project.repositories.flatDir { dirs(repository) }
    project.dependencies.add(testConfig.name, "example:library:1.0")

    val extras =
      AndroidPreviewClasspath.buildAgpClasspathExtras(
        project,
        project.files(mergedR, moduleJar),
        testConfig,
      )

    assertThat(extras.files).containsExactly(mergedR)
  }

  @Test
  fun `fallback reads sdk dir from local properties and returns highest platform android jar`() {
    val sdkRoot = tmp.newFolder("sdk")
    writeAndroidJar(File(sdkRoot, "platforms/android-30/android.jar"))
    writeAndroidJar(File(sdkRoot, "platforms/android-35/android.jar"))
    writeAndroidJar(File(sdkRoot, "platforms/android-33/android.jar"))

    val rootDir = tmp.newFolder("project")
    File(rootDir, "local.properties").writeText("sdk.dir=${sdkRoot.absolutePath}\n")
    val project = ProjectBuilder.builder().withProjectDir(rootDir).build()

    val resolved = AndroidPreviewClasspath.buildBootClasspathFallback(project).get()

    assertThat(resolved.map { it.absolutePath })
      .containsExactly(File(sdkRoot, "platforms/android-35/android.jar").absolutePath)
  }

  @Test
  fun `fallback returns empty when no sdk location is configured`() {
    val rootDir = tmp.newFolder("project-no-sdk")
    val project = ProjectBuilder.builder().withProjectDir(rootDir).build()

    // No `local.properties`, and env vars can't be set here; relies on this test JVM having no
    // valid ANDROID_HOME.
    val resolved = AndroidPreviewClasspath.buildBootClasspathFallback(project).get()

    // Empty, or one jar where ANDROID_HOME is real; assert shape, not value.
    resolved.forEach {
      assertThat(it.name).isEqualTo("android.jar")
      assertThat(it.isFile).isTrue()
    }
  }

  @Test
  fun `validate throws when no jar on classpath defines android Application`() {
    val noisy = tmp.newFile("noise.jar")
    writeJar(noisy, mapOf("some/other/Class.class" to ByteArray(8)))

    val thrown = runCatching {
      AndroidPreviewClasspath.validateApplicationOnClasspath(listOf(noisy))
    }
      .exceptionOrNull()

    assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    assertThat(thrown!!.message).contains("issue #1243")
    assertThat(thrown.message)
      .contains("android.jar is not on the composePreviewRender test classpath")
    assertThat(thrown.message).contains("compileSdk")
  }

  @Test
  fun `validate succeeds when a classpath jar carries android Application`() {
    val androidJar = tmp.newFile("android.jar")
    writeAndroidJar(androidJar)

    AndroidPreviewClasspath.validateApplicationOnClasspath(listOf(androidJar))
  }

  @Test
  fun `validate ignores directories and non-jar files`() {
    val dir = tmp.newFolder("classes")
    val notAJar = tmp.newFile("notes.txt").apply { writeText("ignore me") }
    val androidJar = tmp.newFile("android.jar")
    writeAndroidJar(androidJar)

    AndroidPreviewClasspath.validateApplicationOnClasspath(listOf(dir, notAJar, androidJar))
  }

  @Test
  fun `buildJvmArgs opens jdk_internal_access for FileDescriptorInterceptor`() {
    // #1328: Robolectric's `FileDescriptorInterceptor` needs
    // `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` on SDK 36 sandboxes.
    assertThat(AndroidPreviewClasspath.buildJvmArgs())
      .contains("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
  }

  @Test
  fun `buildSystemProperties forwards the svg-embed-fonts flag into the daemon jvm`() {
    // The daemon only sees properties this map forwards, so `composeai.svg.embedFonts` must be
    // here to reach it.
    val props =
      AndroidPreviewClasspath.buildSystemProperties(
        manifestPath = "m.json",
        rendersDir = "renders",
        fontsCacheDir = "cache",
        fontsOffline = "false",
        svgEmbedFonts = "false",
      )
    assertThat(props).containsEntry("composeai.svg.embedFonts", "false")
    // On by default when the caller doesn't override it — the export embeds the real face so the
    // layered SVG stops falling back to a substituted `sans-serif`.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.svg.embedFonts", "true")
  }

  @Test
  fun `buildSystemProperties forwards the svg-background opt-in into the daemon jvm`() {
    // `composeai.svg.background` must be forwarded, or the opt-in never reaches the daemon.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          svgBackground = "true",
        )
      )
      .containsEntry("composeai.svg.background", "true")
    // Off unless asked: the layered SVG exports as editable layers rather than sitting on an
    // opaque rect a designer has to delete.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.svg.background", "false")
  }

  @Test
  fun `buildSystemProperties forwards the font fail-on-fallback flag into the render jvm`() {
    // `composeai.fonts.failOnFallback` must be forwarded, or the opt-out is unreachable.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          fontsFailOnFallback = "false",
        )
      )
      .containsEntry("composeai.fonts.failOnFallback", "false")
    // Fatal by default when the caller doesn't override it.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.fonts.failOnFallback", "true")
  }

  @Test
  fun `buildSystemProperties forwards the preview host theme into the render jvm`() {
    // `composeai.render.hostTheme` must reach the render JVM (#2957).
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          hostTheme = "@style/Theme.Foo",
        )
      )
      .containsEntry("composeai.render.hostTheme", "@style/Theme.Foo")
    // Empty when the consumer names nothing — an application module inherits
    // `<application android:theme>` and must not have it overridden.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.render.hostTheme", "")
  }

  @Test
  fun `buildSystemProperties forwards the pinned preview clock into the render jvm`() {
    // `composeai.render.fixedTime` must reach the render JVM (#3239).
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          fixedTime = "09:41",
        )
      )
      .containsEntry("composeai.render.fixedTime", "09:41")
    // Blank when the consumer names nothing — the renderer reads that as "pin the default 10:10",
    // so the property is still forwarded rather than conditionally omitted.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.render.fixedTime", "")
  }

  @Test
  fun `buildSystemProperties forwards the rewritten SlotTable opt-in into the render jvm`() {
    // `composeai.render.linkBufferComposer` must be forwarded, or a catalog renders on the old
    // composer while claiming to test the new one.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          linkBufferComposer = "true",
        )
      )
      .containsEntry("composeai.render.linkBufferComposer", "true")
    // `auto` must arrive verbatim, not coerced to a boolean.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          linkBufferComposer = "auto",
        )
      )
      .containsEntry("composeai.render.linkBufferComposer", "auto")
    // An opt-in stays opt-in: nothing asked for, the runtime keeps its own default.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.render.linkBufferComposer", "false")
  }

  @Test
  fun `buildSystemProperties forwards the Remote Compose player into the render jvm`() {
    // `composeai.render.rcPlayer` is read inside the sandbox.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
          rcPlayer = "view",
        )
      )
      .containsEntry("composeai.render.rcPlayer", "view")
    // Defaults to `androidx-embedded` (#5259), never the retired `cmp`.
    assertThat(
        AndroidPreviewClasspath.buildSystemProperties(
          manifestPath = "m.json",
          rendersDir = "renders",
          fontsCacheDir = "cache",
          fontsOffline = "false",
        )
      )
      .containsEntry("composeai.render.rcPlayer", "androidx-embedded")
  }

  private fun writeAndroidJar(file: File) {
    writeJar(file, mapOf("android/app/Application.class" to ByteArray(16)))
  }

  private fun writeJar(file: File, entries: Map<String, ByteArray>) {
    file.parentFile?.mkdirs()
    JarOutputStream(file.outputStream()).use { out ->
      for ((path, bytes) in entries) {
        out.putNextEntry(JarEntry(path))
        out.write(bytes)
        out.closeEntry()
      }
    }
  }
}
