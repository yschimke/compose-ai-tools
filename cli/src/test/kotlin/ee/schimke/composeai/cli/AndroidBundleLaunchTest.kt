package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the Phase 1 Android-bundle launch foundation: the deterministic assembly the
 * standalone Robolectric render subprocess needs. The render itself isn't exercised here (it needs
 * an Android SDK + Robolectric runtime — Phase 2 / the SDK-gated CI chain); these tests pin the
 * inputs the spawn is built from.
 */
class AndroidBundleLaunchTest {

  private fun tempDir(): File = Files.createTempDirectory("android-bundle-launch-test-").toFile()

  @Test
  fun `jvm args carry the robolectric add-opens set`() {
    val args = AndroidBundleLaunch().jvmArgs()
    assertTrue(args.contains("--enable-native-access=ALL-UNNAMED"))
    // The full --add-opens set Robolectric needs on JDK 17+ (mirrors AndroidPreviewClasspath).
    assertTrue(args.contains("--add-opens=java.base/java.io=ALL-UNNAMED"))
    assertTrue(args.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"))
    assertTrue(args.contains("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"))
    assertTrue(args.contains("--add-opens=java.base/java.nio=ALL-UNNAMED"))
    assertTrue(args.contains("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED"))
  }

  @Test
  fun `system properties drive the renderer at the manifest and output dir`() {
    val props = AndroidBundleLaunch().systemProperties("/tmp/previews.json", "/tmp/out")
    assertEquals("NATIVE", props["robolectric.graphicsMode"])
    assertEquals("PAUSED", props["robolectric.looperMode"])
    assertEquals("OFF", props["robolectric.conscryptMode"])
    assertEquals("hardware", props["robolectric.pixelCopyRenderMode"])
    assertEquals("true", props["roborazzi.test.record"])
    assertEquals("/tmp/previews.json", props["composeai.render.manifest"])
    assertEquals("/tmp/out", props["composeai.render.outputDir"])
  }

  @Test
  fun `robolectric-only system properties omit the render batch io props`() {
    // The daemon path consumes these (it routes via userClassDirs/previewsJsonPath) and must NOT
    // carry the one-shot renderer's manifest/outputDir props.
    val props = AndroidBundleLaunch().robolectricSystemProperties()
    assertEquals("NATIVE", props["robolectric.graphicsMode"])
    assertEquals("PAUSED", props["robolectric.looperMode"])
    assertEquals("OFF", props["robolectric.conscryptMode"])
    assertEquals("hardware", props["robolectric.pixelCopyRenderMode"])
    assertEquals("true", props["roborazzi.test.record"])
    assertTrue(!props.containsKey("composeai.render.manifest"))
    assertTrue(!props.containsKey("composeai.render.outputDir"))
  }

  @Test
  fun `robolectric properties pin the sdk, graphics mode, stub application and font shadow`() {
    val body = AndroidBundleLaunch(sdkLevel = 34).robolectricPropertiesBody()
    val lines = body.lines()
    assertTrue(lines.contains("sdk=34"))
    assertTrue(lines.contains("graphicsMode=NATIVE"))
    assertTrue(lines.contains("application=android.app.Application"))
    assertTrue(lines.contains("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat"))
  }

  @Test
  fun `useConsumerApplication drops the stub application line`() {
    val body =
      AndroidBundleLaunch(sdkLevel = 34, useConsumerApplication = true).robolectricPropertiesBody()
    assertTrue(body.lines().none { it.startsWith("application=") })
  }

  @Test
  fun `sdk level is clamped to the supported robolectric range`() {
    assertEquals(AndroidBundleLaunch.MIN_SDK, AndroidBundleLaunch(sdkLevel = 5).sdkLevel)
    assertEquals(AndroidBundleLaunch.MAX_SDK, AndroidBundleLaunch(sdkLevel = 99).sdkLevel)
    assertEquals(30, AndroidBundleLaunch(sdkLevel = 30).sdkLevel)
  }

  @Test
  fun `sdk level system-property override parses and falls back`() {
    assertEquals(33, AndroidBundleLaunch.sdkLevelFromSystemProperty("33"))
    assertEquals(
      AndroidBundleLaunch.DEFAULT_SDK,
      AndroidBundleLaunch.sdkLevelFromSystemProperty(null),
    )
    assertEquals(
      AndroidBundleLaunch.DEFAULT_SDK,
      AndroidBundleLaunch.sdkLevelFromSystemProperty("nope"),
    )
  }

  @Test
  fun `writeRobolectricConfig materialises the package-level properties file`() {
    val root = tempDir()
    val returned = AndroidBundleLaunch(sdkLevel = 35).writeRobolectricConfig(root)
    assertEquals(root, returned)
    val props = File(root, "ee/schimke/composeai/renderer/robolectric.properties")
    assertTrue(
      props.isFile,
      "robolectric.properties should be written at the renderer package path",
    )
    assertTrue(props.readText().contains("sdk=35"))
  }

  @Test
  fun `resolveAndroidJar reads sdk_dir from local properties and picks the highest platform`() {
    val sdk = tempDir()
    for (level in listOf(30, 34, 28)) {
      File(sdk, "platforms/android-$level").mkdirs()
      File(sdk, "platforms/android-$level/android.jar").writeText("stub")
    }
    val localProps =
      File(tempDir(), "local.properties").apply { writeText("sdk.dir=${sdk.absolutePath}\n") }

    val jar = AndroidBundleLaunch.resolveAndroidJar(localProps, env = { null })
    assertEquals(File(sdk, "platforms/android-34/android.jar"), jar)
  }

  @Test
  fun `resolveAndroidJar falls back to ANDROID_HOME when local properties is absent`() {
    val sdk = tempDir()
    File(sdk, "platforms/android-33").mkdirs()
    File(sdk, "platforms/android-33/android.jar").writeText("stub")

    val jar =
      AndroidBundleLaunch.resolveAndroidJar(
        localPropertiesFile = null,
        env = { name -> if (name == "ANDROID_HOME") sdk.absolutePath else null },
      )
    assertEquals(File(sdk, "platforms/android-33/android.jar"), jar)
  }

  @Test
  fun `resolveAndroidJar returns null when no sdk is reachable`() {
    assertNull(AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = null, env = { null }))
  }
}
