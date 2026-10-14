package ee.schimke.composeai.cli

import ee.schimke.composeai.buildartifacts.protocol.AndroidBuildArtifacts
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BazelAndroidTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-android").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun File.file(path: String, text: String = ""): File =
    resolve(path).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  /** The shape the Android aspect writes: final-ID R classes first, then the target's jars. */
  private fun androidArtifacts(root: File): BazelArtifacts {
    listOf("bin/_compose_preview/ui/r-final.jar", "bin/ui.jar", "external/compose.jar").forEach {
      root.file(it)
    }
    root.file("bin/_compose_preview/ui/resources.ap_")
    root.file(
      "ui/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.ui">
        <application android:name="com.example.ui.App" />
      </manifest>
      """
        .trimIndent(),
    )
    return BazelArtifacts(
      "//ui",
      "bin",
      listOf("bin/ui.jar"),
      listOf("bin/_compose_preview/ui/r-final.jar", "bin/ui.jar", "external/compose.jar"),
      android =
        AndroidBuildArtifacts.Builder(
            "com.example.ui",
            "bin/_compose_preview/ui/resources.ap_",
            "ui/AndroidManifest.xml",
          )
          .apply {
            rJar = "bin/_compose_preview/ui/r-final.jar"
            compileSdk = 36
          }
          .build(),
    )
  }

  @Test
  fun `android block resolves materialized resources and keeps schema 1 Android-free`() =
    fixture { root ->
      val artifacts = androidArtifacts(root)
      val resolved = artifacts.resolve(root)
      val android = checkNotNull(resolved.android)
      assertEquals("com.example.ui", android.packageName)
      assertEquals(
        root.resolve("bin/_compose_preview/ui/resources.ap_").absoluteFile,
        android.resourceApk,
      )
      assertEquals(root.resolve("bin/_compose_preview/ui/r-final.jar").absoluteFile, android.rJar)
      assertEquals(36, android.compileSdk)
      assertNull(
        BazelArtifacts("//:app", "bin", listOf("bin/ui.jar"), listOf("bin/ui.jar"))
          .resolve(root)
          .android
      )

      // A missing linked APK is a build that did not materialize its outputs, not a JVM target.
      root.resolve("bin/_compose_preview/ui/resources.ap_").delete()
      assertTrue(
        assertFailsWith<IllegalArgumentException> { artifacts.resolve(root) }
          .message!!
          .contains("not materialized")
      )
    }

  @Test
  fun `schema 1 artifacts carrying an android block are refused`() = fixture { root ->
    val encoded = bazelJson.encodeToString(androidArtifacts(root))
    assertFailsWith<IllegalArgumentException> {
      bazelJson
        .decodeFromString<BazelArtifacts>(
          encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        )
        .resolve(root)
    }
  }

  @Test
  fun `robolectric runs offline only against an existing dependency directory`() = fixture { root ->
    assertEquals(emptyMap(), robolectricOfflineProperties(null))
    assertEquals(
      mapOf("robolectric.offline" to "true", "robolectric.dependency.dir" to root.absolutePath),
      robolectricOfflineProperties(root.path),
    )
    assertFailsWith<IllegalArgumentException> {
      robolectricOfflineProperties(root.resolve("missing").path)
    }
  }

  @Test
  fun `android launch feeds Robolectric the linked resources with final R classes first`() =
    fixture { root ->
      val artifacts = androidArtifacts(root).resolve(root)
      val sidecar = root.resolve("lib-daemon-android").apply { mkdirs() }
      sidecar.file("daemon-android.jar")
      val androidJar = root.file("sdk/platforms/android-36/android.jar")
      root.file("local.properties", "sdk.dir=${root.resolve("sdk").absolutePath}\n")
      val output = root.resolve("out").apply { mkdirs() }
      val manifestFile =
        output.resolve("previews.json").apply {
          writeText(bazelJson.encodeToString(PreviewManifest("//ui", "bin", emptyList())))
        }
      val property = DaemonSidecarProvision.sidecarProperty("lib-daemon-android")
      val previous = System.getProperty(property)
      System.setProperty(property, sidecar.absolutePath)
      val descriptor =
        try {
          DaemonLaunchBuilder.decode(
            prepareBazelDaemon(
                artifacts,
                PreparedBazelPreviews(PreviewManifest("//ui", "bin", emptyList()), manifestFile),
                root,
              )
              .readText()
          )
        } finally {
          if (previous == null) System.clearProperty(property)
          else System.setProperty(property, previous)
        }

      // Robolectric finds the APK, manifest and package through the synthesized test config,
      // which leads the daemon classpath; the Application is pinned to the safe stub.
      val testConfig = File(descriptor.classpath.first())
      val config = testConfig.resolve("com/android/tools/test_config.properties").readText()
      assertTrue(
        config.contains("android_resource_apk=${artifacts.android!!.resourceApk.absolutePath}")
      )
      assertTrue(config.contains("android_custom_package=com.example.ui"))
      val daemonManifest =
        File(config.lines().first { it.startsWith("android_merged_manifest=") }.substringAfter('='))
      assertTrue(daemonManifest.startsWith(output), "sanitized copy stays out of the build tree")
      assertTrue("com.example.ui.App" !in daemonManifest.readText())
      assertTrue(
        testConfig
          .resolve("ee/schimke/composeai/daemon/robolectric.properties")
          .readText()
          .contains("application=android.app.Application")
      )
      assertTrue(androidJar.absolutePath in descriptor.classpath)
      assertTrue(sidecar.resolve("daemon-android.jar").absolutePath in descriptor.classpath)

      val userClasses = descriptor.systemProperties.getValue("composeai.daemon.userClassDirs")
      assertEquals(
        artifacts.android!!.rJar!!.absolutePath,
        userClasses.split(File.pathSeparator).first(),
      )
      assertEquals(
        userClasses.split(File.pathSeparator).distinct(),
        userClasses.split(File.pathSeparator),
      )
      assertTrue("robolectric.offline" !in descriptor.systemProperties)
    }
}
