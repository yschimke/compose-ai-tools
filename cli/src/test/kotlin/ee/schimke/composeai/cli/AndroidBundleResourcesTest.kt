package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the packed-bundle Android app-resource carriage — the fix that lets a detached
 * Robolectric render resolve `stringResource(R.string.…)` (`0x7f…`) instead of throwing
 * `Resources$NotFoundException`. Pure file/zip logic, so it runs on any `:cli:test` (no Android
 * render toolchain needed).
 */
class AndroidBundleResourcesTest {

  private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      for ((name, bytes) in entries) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
      }
    }
    return out.toByteArray()
  }

  @Test
  fun `extract pulls the resource apk, manifest and r-classes from an android bundle`() {
    val dir = Files.createTempDirectory("abr-extract").toFile()
    val zip =
      zipOf(
        mapOf(
          "classes/app.jar" to byteArrayOf(1, 2, 3),
          "android/resources.ap_" to "APK".toByteArray(),
          "android/AndroidManifest.xml" to "<manifest/>".toByteArray(),
          "android/r-classes.jar" to "RJAR".toByteArray(),
        )
      )

    val res = AndroidBundleResources.extract(zip, File(dir, "android"))

    assertTrue(res != null)
    assertEquals("APK", res.resourceApk.readText())
    assertEquals("<manifest/>", res.mergedManifest.readText())
    assertEquals("RJAR", res.rClassesJar?.readText())
  }

  @Test
  fun `extract returns null when the bundle carries no android payload`() {
    val dir = Files.createTempDirectory("abr-none").toFile()
    val zip =
      zipOf(mapOf("classes/app.jar" to byteArrayOf(1), "previews.json" to "{}".toByteArray()))

    assertNull(AndroidBundleResources.extract(zip, File(dir, "android")))
  }

  @Test
  fun `extract needs both apk and manifest — apk alone is not enough`() {
    val dir = Files.createTempDirectory("abr-partial").toFile()
    val zip = zipOf(mapOf("android/resources.ap_" to "APK".toByteArray()))

    assertNull(AndroidBundleResources.extract(zip, File(dir, "android")))
  }

  @Test
  fun `writeTestConfig emits the Robolectric properties AGP would, incl the custom package`() {
    val dir = Files.createTempDirectory("abr-cfg").toFile()
    val apk = File(dir, "resources.ap_").apply { writeText("x") }
    val manifest = File(dir, "AndroidManifest.xml").apply { writeText("y") }

    val root =
      AndroidBundleResources.writeTestConfig(File(dir, "cfg"), apk, manifest, "com.example.app")

    val props = File(root, "com/android/tools/test_config.properties").readText()
    assertTrue(props.contains("android_resource_apk=${apk.absolutePath}"), props)
    assertTrue(props.contains("android_merged_manifest=${manifest.absolutePath}"), props)
    assertTrue(props.contains("android_custom_package=com.example.app"), props)
  }

  @Test
  fun `writeTestConfig omits the custom package when none was recorded`() {
    val dir = Files.createTempDirectory("abr-cfg-nopkg").toFile()
    val apk = File(dir, "resources.ap_").apply { writeText("x") }
    val manifest = File(dir, "AndroidManifest.xml").apply { writeText("y") }

    val root = AndroidBundleResources.writeTestConfig(File(dir, "cfg"), apk, manifest, null)

    val props = File(root, "com/android/tools/test_config.properties").readText()
    assertTrue(!props.contains("android_custom_package"), props)
  }

  @Test
  fun `daemonClasspath returns the test-config dir plus r-classes jar for an android bundle`() {
    val dir = Files.createTempDirectory("abr-cp").toFile()
    val zip =
      zipOf(
        mapOf(
          "android/resources.ap_" to "APK".toByteArray(),
          "android/AndroidManifest.xml" to "<manifest/>".toByteArray(),
          "android/r-classes.jar" to "RJAR".toByteArray(),
        )
      )

    val cp = AndroidBundleResources.daemonClasspath(zip, dir, "com.example.app")

    assertEquals(2, cp.size)
    // First entry is the synthesized test-config root; the Robolectric properties live under it.
    val props = File(cp[0], "com/android/tools/test_config.properties")
    assertTrue(props.isFile, "expected test_config.properties under ${cp[0]}")
    assertTrue(props.readText().contains("android_resource_apk="))
    // Second entry is the extracted R-classes jar.
    assertEquals("RJAR", cp[1].readText())
  }

  @Test
  fun `daemonClasspath is empty for a bundle with no android payload`() {
    val dir = Files.createTempDirectory("abr-cp-none").toFile()
    val zip = zipOf(mapOf("classes/app.jar" to byteArrayOf(1)))

    assertTrue(AndroidBundleResources.daemonClasspath(zip, dir, null).isEmpty())
  }

  // --- <application android:name> sanitization (fix for the custom-Application boot crash) ---

  private val androidNs = "xmlns:android=\"http://schemas.android.com/apk/res/android\""

  @Test
  fun `stripApplicationName removes a custom application name`() {
    val xml =
      """<manifest $androidNs package="ee.schimke.meshcore.app">
           <application android:name=".MeshcoreApp" android:theme="@style/T"/>
         </manifest>"""
    val out = AndroidBundleResources.stripApplicationName(xml)
    assertTrue(out != null, "expected a rewrite")
    assertTrue(!out!!.contains("MeshcoreApp"), out)
    // The theme (and every other attribute) survives — only android:name is removed.
    assertTrue(out.contains("@style/T"), out)
  }

  @Test
  fun `stripApplicationName returns null when there is no application name to strip`() {
    val xml =
      """<manifest $androidNs package="com.example">
           <application android:theme="@style/T"/>
         </manifest>"""
    assertNull(AndroidBundleResources.stripApplicationName(xml))
  }

  @Test
  fun `stripApplicationName returns null for an unparseable manifest`() {
    assertNull(AndroidBundleResources.stripApplicationName("<manifest><application"))
  }

  private fun bundleWithApplicationManifest(): ByteArray {
    val manifest =
      """<manifest $androidNs package="ee.schimke.meshcore.app">
           <application android:name="ee.schimke.meshcore.app.MeshcoreApp"/>
         </manifest>"""
    return zipOf(
      mapOf(
        "android/resources.ap_" to "APK".toByteArray(),
        "android/AndroidManifest.xml" to manifest.toByteArray(),
      )
    )
  }

  private fun manifestHandedToRobolectric(configRoot: File): String {
    val props = File(configRoot, "com/android/tools/test_config.properties").readText()
    val manifestPath =
      props.lineSequence().first { it.startsWith("android_merged_manifest=") }.substringAfter('=')
    return File(manifestPath).readText()
  }

  @Test
  fun `daemonClasspath points test-config at a manifest with no custom application name`() {
    val dir = Files.createTempDirectory("abr-sanitize").toFile()

    val cp =
      AndroidBundleResources.daemonClasspath(
        bundleWithApplicationManifest(),
        dir,
        "ee.schimke.meshcore.app",
      )

    assertTrue(
      !manifestHandedToRobolectric(cp[0]).contains("MeshcoreApp"),
      "the daemon manifest must not name a consumer Application",
    )
  }

  @Test
  fun `daemonClasspath keeps the consumer application name when the daemon opts in`() {
    val dir = Files.createTempDirectory("abr-optin").toFile()

    // useConsumerApplication=true mirrors composeai.daemon.useConsumerApplication — the daemon will
    // run the manifest Application, so the name must survive the manifest we hand it.
    val cp =
      AndroidBundleResources.daemonClasspath(
        bundleWithApplicationManifest(),
        dir,
        "ee.schimke.meshcore.app",
        useConsumerApplication = true,
      )

    assertTrue(
      manifestHandedToRobolectric(cp[0]).contains("MeshcoreApp"),
      "the opt-in must preserve the consumer Application declaration",
    )
  }

  // --- daemon-package robolectric.properties (the load-bearing Application pin for Robolectric
  // 4.16) ---

  private fun daemonRobolectricProperties(configRoot: File): File =
    File(configRoot, "ee/schimke/composeai/daemon/robolectric.properties")

  @Test
  fun `daemonClasspath pins android app Application via the daemon-package robolectric properties`() {
    val dir = Files.createTempDirectory("abr-roboprops").toFile()

    val cp =
      AndroidBundleResources.daemonClasspath(
        bundleWithApplicationManifest(),
        dir,
        "ee.schimke.meshcore.app",
      )

    // The file must live in the daemon's OWN package (that's the only robolectric.properties
    // Robolectric merges for RobolectricHost.SandboxRunner) and pin the framework Application —
    // the deprecated buildGlobalConfig override no longer merges over the manifest in 4.16.
    val props = daemonRobolectricProperties(cp[0])
    assertTrue(props.isFile, "expected daemon-package robolectric.properties under ${cp[0]}")
    assertTrue(props.readText().contains("application=android.app.Application"), props.readText())
  }

  @Test
  fun `daemonClasspath omits the Application pin when the daemon opts in`() {
    val dir = Files.createTempDirectory("abr-roboprops-optin").toFile()

    val cp =
      AndroidBundleResources.daemonClasspath(
        bundleWithApplicationManifest(),
        dir,
        "ee.schimke.meshcore.app",
        useConsumerApplication = true,
      )

    // Opt-in: no `application=` line, so Robolectric falls back to the manifest Application.
    val props = daemonRobolectricProperties(cp[0])
    assertTrue(props.isFile, "the properties file is still written (documents the opt-in)")
    assertTrue(!props.readText().contains("application="), props.readText())
  }
}
