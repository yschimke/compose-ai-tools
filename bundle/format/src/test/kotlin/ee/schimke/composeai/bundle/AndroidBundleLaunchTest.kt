package ee.schimke.composeai.bundle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the deterministic inputs the standalone Robolectric render subprocess is spawned from; the
 * render itself needs an Android SDK and is covered elsewhere.
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
    // The shared GoogleFont cache dir rides these too, so a downloadable Font(GoogleFont(...))
    // resolves on a detached/serve render instead of silently falling back to the platform default.
    assertTrue(props.getValue("composeai.fonts.cacheDir").isNotBlank())
  }

  @Test
  fun `the daemon sandbox sdk follows the clamped sdk level`() {
    // The daemon's sandbox class is outside the packages robolectric.properties covers, so the
    // level reaches it only through this property.
    assertEquals(
      "37",
      AndroidBundleLaunch(sdkLevel = 37)
        .robolectricSystemProperties()["composeai.daemon.sandboxSdk"],
    )
    assertEquals(
      AndroidBundleLaunch.MAX_SDK.toString(),
      AndroidBundleLaunch(sdkLevel = 99)
        .robolectricSystemProperties()["composeai.daemon.sandboxSdk"],
    )
  }

  @Test
  fun `no clock packages leaves the config and properties as before`() {
    val launch = AndroidBundleLaunch(sdkLevel = 35)
    val pinned = launch.withClockPackages(listOf(" ", ""))
    assertTrue(pinned.clockPackages.isEmpty())
    assertEquals(launch.robolectricPropertiesBody(), pinned.robolectricPropertiesBody())
    assertEquals(launch.robolectricSystemProperties(), pinned.robolectricSystemProperties())
    assertTrue("composeai.render.clockPackages" !in launch.robolectricSystemProperties())
    assertTrue("ShadowConsumerClock" !in launch.robolectricPropertiesBody())
  }

  @Test
  fun `clock packages reach both lanes and the render jvm`() {
    val launch =
      AndroidBundleLaunch(sdkLevel = 35)
        .withClockPackages(listOf("com.example.app.", "android.", "com.example.app."))
    // The framework is never instrumented; duplicates collapse.
    assertEquals(listOf("com.example.app."), launch.clockPackages)
    assertEquals(35, launch.sdkLevel)
    for (body in
      listOf(launch.robolectricPropertiesBody(), launch.appTourRobolectricPropertiesBody())) {
      assertTrue(
        body.lines().any { it.startsWith("instrumentedPackages=") && "com.example.app." in it }
      )
      assertTrue("ShadowConsumerClock" in body, body)
    }
    assertEquals(
      "com.example.app.",
      launch.systemProperties("/m.json", "/out")["composeai.render.clockPackages"],
    )
    val root = launch.writeRobolectricConfig(tempDir())
    val written = File(root, "ee/schimke/composeai/renderer/robolectric.properties").readText()
    assertTrue("ShadowConsumerClock" in written)
  }

  @Test
  fun `font cache dir is injectable and forwarded to the renderer`() {
    val props =
      AndroidBundleLaunch(fontsCacheDir = "/cache/composeai/fonts").robolectricSystemProperties()
    assertEquals("/cache/composeai/fonts", props["composeai.fonts.cacheDir"])
    // Also present on the one-shot render batch props.
    val batch =
      AndroidBundleLaunch(fontsCacheDir = "/cache/composeai/fonts")
        .systemProperties("/tmp/previews.json", "/tmp/out")
    assertEquals("/cache/composeai/fonts", batch["composeai.fonts.cacheDir"])
  }

  @Test
  fun `fail-on-fallback opt-out is forwarded to the daemon only when set on this process`() {
    val prop = "composeai.fonts.failOnFallback"
    val saved = System.getProperty(prop)
    try {
      System.clearProperty(prop)
      assertTrue(!AndroidBundleLaunch().robolectricSystemProperties().containsKey(prop))
      System.setProperty(prop, "false")
      assertEquals("false", AndroidBundleLaunch().robolectricSystemProperties()[prop])
    } finally {
      if (saved == null) System.clearProperty(prop) else System.setProperty(prop, saved)
    }
  }

  /**
   * Properties the Android renderer and figma-svg connector read must be passed to the child
   * explicitly, since a `-D` on this process doesn't reach a spawned JVM (e.g.
   * `composeai.fonts.offline` previously never reached Android lanes). Table-driven so a forgotten
   * property is caught.
   */
  @Test
  fun `font and svg opt-outs are forwarded to the android child when set on this process`() {
    for (prop in
      listOf(
        "composeai.fonts.offline",
        "composeai.svg.embedFonts",
        "composeai.svg.background",
      )) {
      val saved = System.getProperty(prop)
      try {
        System.clearProperty(prop)
        assertTrue(
          !AndroidBundleLaunch().robolectricSystemProperties().containsKey(prop),
          "$prop must be absent when this process does not set it",
        )
        System.setProperty(prop, "true")
        assertEquals(
          "true",
          AndroidBundleLaunch().robolectricSystemProperties()[prop],
          "$prop must reach the child when set here",
        )
      } finally {
        if (saved == null) System.clearProperty(prop) else System.setProperty(prop, saved)
      }
    }
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
  fun `writeRobolectricConfig also materialises the app-tour lane's file`() {
    // `AndroidRendererMainKt` runs both render classes, and Robolectric resolves each one's
    // Application from its OWN package's properties file. Without this second file the app-tour
    // lane would fall back to whatever the shipped jar carries.
    val root = tempDir()
    AndroidBundleLaunch(sdkLevel = 35).writeRobolectricConfig(root)
    val props = File(root, "ee/schimke/composeai/apptour/robolectric.properties")
    assertTrue(props.isFile, "the app-tour lane needs its own package-level properties file")
    assertTrue(props.readText().contains("sdk=35"))
  }

  @Test
  fun `the app-tour lane leaves the Application to the manifest`() {
    // An Activity is the app, so app-tour omits `application=`; the app-tour package is a sibling
    // of the renderer package, so the composable lane's stub isn't merged in.
    val body = AndroidBundleLaunch(sdkLevel = 34).appTourRobolectricPropertiesBody()
    assertTrue(
      body.lines().none { it.startsWith("application=") },
      "app-tour lane must not pin an Application: $body",
    )
    // Everything else has to match the composable lane, or a module would render its activities at
    // a different API level, or without the font shadow, than its composables.
    val composable = AndroidBundleLaunch(sdkLevel = 34).robolectricPropertiesBody()
    val carried = { text: String ->
      text.lines().filter { !it.startsWith("application=") && it.isNotBlank() }
    }
    assertEquals(carried(composable), carried(body))
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
  fun `resolveAndroidJar orders minor-versioned platform directories`() {
    val sdk = tempDir()
    for (name in listOf("android-36", "android-37.0", "android-36.1", "android-preview")) {
      File(sdk, "platforms/$name").mkdirs()
      File(sdk, "platforms/$name/android.jar").writeText("stub")
    }
    val localProps =
      File(tempDir(), "local.properties").apply { writeText("sdk.dir=${sdk.absolutePath}\n") }

    val jar = AndroidBundleLaunch.resolveAndroidJar(localProps, env = { null })
    assertEquals(File(sdk, "platforms/android-37.0/android.jar"), jar)
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
