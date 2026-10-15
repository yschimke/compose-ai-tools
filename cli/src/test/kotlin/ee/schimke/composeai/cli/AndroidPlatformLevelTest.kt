package ee.schimke.composeai.cli

import ee.schimke.composeai.buildartifacts.protocol.AndroidBuildArtifacts
import ee.schimke.composeai.buildartifacts.protocol.AndroidLinkInputs
import ee.schimke.composeai.bundle.AndroidBundleLaunch
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPlatformLevelTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("android-platform").toFile()
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

  @Test
  fun `source properties names the platform level`() = fixture { root ->
    val jar = root.file("sdk/custom-platform/android.jar")
    root.file(
      "sdk/custom-platform/source.properties",
      "Pkg.Desc=Android SDK Platform 36\nAndroidVersion.ApiLevel=36\nPkg.Revision=2\n",
    )
    // build.prop disagreeing proves source.properties is read first.
    root.file("sdk/custom-platform/build.prop", "ro.build.version.sdk=35\n")
    assertEquals(36, AndroidPlatformLevel.of(jar))
  }

  @Test
  fun `a minor platform renders on its major level`() = fixture { root ->
    val jar = root.file("sdk/platform/android.jar")
    root.file("sdk/platform/source.properties", "AndroidVersion.ApiLevel=36.1\n")
    assertEquals(36, AndroidPlatformLevel.of(jar))
  }

  @Test
  fun `build prop is the fallback`() = fixture { root ->
    val jar = root.file("sdk/platform/android.jar")
    root.file("sdk/platform/source.properties", "Pkg.Desc=no level here\n")
    root.file("sdk/platform/build.prop", "# comment\nro.build.version.sdk=34\nro.x=y\n")
    assertEquals(34, AndroidPlatformLevel.of(jar))
  }

  @Test
  fun `neither file leaves the level unknown`() = fixture { root ->
    val jar = root.file("sdk/platform/android.jar")
    assertNull(AndroidPlatformLevel.of(jar))
    root.file("sdk/platform/build.prop", "ro.build.version.sdk=Baklava\n")
    assertNull(AndroidPlatformLevel.of(jar))
  }

  @Test
  fun `a symlinked jar is looked up beside its target`() = fixture { root ->
    val real = root.file("sdk/platforms/p/android.jar")
    root.file("sdk/platforms/p/source.properties", "AndroidVersion.ApiLevel=35\n")
    val link = root.resolve("execroot/external/androidsdk/android.jar")
    link.parentFile.mkdirs()
    Files.createSymbolicLink(link.toPath(), real.toPath())
    assertEquals(35, AndroidPlatformLevel.of(link))
  }

  private fun artifacts(root: File, compileSdk: Int?): BazelArtifacts {
    listOf("bin/ui.jar", "bin/ui.ap_", "ui/AndroidManifest.xml", "link.sh", "aapt2", "ui.zip")
      .forEach { root.file(it) }
    root.resolve("jdk").mkdirs()
    root.file("sdk/platform/android.jar")
    root.file("sdk/platform/source.properties", "AndroidVersion.ApiLevel=36\n")
    return BazelArtifacts(
      "//ui",
      "bin",
      listOf("bin/ui.jar"),
      listOf("bin/ui.jar"),
      android =
        AndroidBuildArtifacts.Builder("com.example.ui", "bin/ui.ap_", "ui/AndroidManifest.xml")
          .apply {
            this.compileSdk = compileSdk
            link =
              AndroidLinkInputs.Builder(
                  "link.sh",
                  "aapt2",
                  "jdk",
                  "sdk/platform/android.jar",
                  listOf("com.example.ui"),
                  listOf("ui.zip"),
                )
                .build()
          }
          .build(),
    )
  }

  @Test
  fun `a target without compileSdk takes its android jar's platform level`() = fixture { root ->
    assertEquals(36, artifacts(root, compileSdk = null).resolve(root).android!!.compileSdk)
    // What the aspect wrote wins over the platform files.
    assertEquals(34, artifacts(root, compileSdk = 34).resolve(root).android!!.compileSdk)
  }

  @Test
  fun `the android jar is read even when other link inputs are missing`() = fixture { root ->
    val metadata = artifacts(root, compileSdk = null)
    root.resolve("aapt2").delete()
    val android = metadata.resolve(root).android!!
    assertNull(android.link)
    assertEquals(36, android.compileSdk)
  }

  @Test
  fun `the render SDK follows compileSdk within what Robolectric runs`() {
    val notes = mutableListOf<String>()
    assertEquals(AndroidBundleLaunch.DEFAULT_SDK, bazelAndroidSdkLevel(null, 21, notes::add))
    assertEquals(34, bazelAndroidSdkLevel(34, 17, notes::add))
    assertEquals(36, bazelAndroidSdkLevel(36, 21, notes::add))
    assertTrue(notes.isEmpty())
    assertEquals(35, bazelAndroidSdkLevel(36, 17, notes::add))
    assertTrue(notes.single().contains("JDK 17"))
    assertEquals(AndroidBundleLaunch.MAX_SDK, bazelAndroidSdkLevel(99, 21, notes::add))
    assertEquals(AndroidBundleLaunch.MIN_SDK, bazelAndroidSdkLevel(19, 21, notes::add))
  }
}
