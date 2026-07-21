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
 * Pins the issue #1243 guards on the renderer test classpath:
 * * `buildBootClasspathFallback` recovers `android.jar` from `local.properties` / `ANDROID_HOME`
 *   when AGP's `sdkComponents.bootClasspath` is empty.
 * * `validateApplicationOnClasspath` surfaces a precise error when no entry on the resolved
 *   classpath defines `android/app/Application.class`, replacing the opaque Robolectric
 *   `Config.<clinit>` `NoClassDefFoundError` the user otherwise sees.
 */
class AndroidPreviewClasspathTest {

  @get:Rule val tmp = TemporaryFolder()

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

    // No local.properties, and we can't set env vars from a unit test — relying on the test
    // process's ANDROID_HOME not pointing at a valid platforms tree. This is true for the
    // gradle-plugin test JVM in this repo (uses JDK toolchain, no Android SDK env).
    val resolved = AndroidPreviewClasspath.buildBootClasspathFallback(project).get()

    // Either truly empty, or — on hosts where ANDROID_HOME points at a real SDK — a single jar.
    // Asserting the type/shape (not the exact value) keeps the test stable on developer machines
    // and CI alike. The exact-local-properties path is asserted by the previous test.
    resolved.forEach {
      assertThat(it.name).isEqualTo("android.jar")
      assertThat(it.isFile).isTrue()
    }
  }

  @Test
  fun `validate throws when no jar on classpath defines android Application`() {
    val noisy = tmp.newFile("noise.jar")
    writeJar(noisy, mapOf("some/other/Class.class" to ByteArray(8)))

    val thrown =
      runCatching { AndroidPreviewClasspath.validateApplicationOnClasspath(listOf(noisy)) }
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
    // Regression for issue #1328: Robolectric 4.16's `FileDescriptorInterceptor.setInt`
    // reflects into `jdk.internal.access.SharedSecrets`, which the JDK refuses to expose to
    // an unnamed module without `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED`. On
    // SDK 36 sandboxes `ApplicationSharedMemory.create()` runs during Robolectric setup and
    // hits that interceptor, surfacing as `Failed to interact with raw FileDescriptor
    // internals; perhaps JRE has changed?`.
    assertThat(AndroidPreviewClasspath.buildJvmArgs())
      .contains("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
  }

  @Test
  fun `buildSystemProperties forwards the figma-embed-fonts flag into the daemon jvm`() {
    // Regression: the daemon JVM only sees the system properties this map forwards, so the
    // `composeai.figma.embedFonts` value must land here or the Android export never honours it
    // (the value set on the Gradle invocation wouldn't reach the daemon).
    val props =
      AndroidPreviewClasspath.buildSystemProperties(
        manifestPath = "m.json",
        rendersDir = "renders",
        fontsCacheDir = "cache",
        fontsOffline = "false",
        figmaEmbedFonts = "false",
      )
    assertThat(props).containsEntry("composeai.figma.embedFonts", "false")
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
      .containsEntry("composeai.figma.embedFonts", "true")
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
