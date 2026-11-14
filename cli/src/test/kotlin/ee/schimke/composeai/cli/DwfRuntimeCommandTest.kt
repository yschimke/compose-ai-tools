package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DwfRuntimeCommandTest {

  @Test
  fun `installLocalApk validates runtime manifest and caches metadata`() {
    val cacheDir = Files.createTempDirectory("dwf-runtime-cache-").toFile()
    val validApk = Files.createTempFile("FakeDwfRuntime-", ".apk").toFile()
    val wffNoDexApk = Files.createTempFile("ResourceOnlyWff-", ".apk").toFile()
    try {
      writeTestRuntimeApk(validApk, pkg = "com.google.wear.watchface.runtime", includeDex = true)
      writeTestRuntimeApk(wffNoDexApk, pkg = "com.example.wff", includeDex = false)

      // Resource-only WFF APK must NOT be accepted as a DWF runtime APK.
      assertNull(DwfRuntimeCommand.inspectRuntimeApk(wffNoDexApk))

      val meta = DwfRuntimeCommand.installLocalApk(validApk, cacheDir, source = "test")
      assertEquals("com.google.wear.watchface.runtime", meta.packageName)
      assertTrue(DwfRuntimeCommand.cachedApkFile(cacheDir).isFile)
      assertEquals(64, meta.sha256.length)

      // resolveAuto finds the cached runtime APK without needing adb.
      val resolved =
        DwfRuntimeCommand.resolveAuto(
          cacheRoot = cacheDir,
          env = { null },
          allowAdbPull = false,
        )
      assertNotNull(resolved)
      assertEquals(DwfRuntimeCommand.cachedApkFile(cacheDir).canonicalPath, resolved.canonicalPath)
    } finally {
      validApk.delete()
      wffNoDexApk.delete()
      cacheDir.deleteRecursively()
    }
  }

  @Test
  fun `pullFromAdb discovers runtime package and caches pulled apk`() {
    val cacheDir = Files.createTempDirectory("dwf-runtime-adb-").toFile()
    val remoteSourceApk = Files.createTempFile("AdbPulledRuntime-", ".apk").toFile()
    try {
      writeTestRuntimeApk(
        remoteSourceApk,
        pkg = "com.google.wear.watchface.runtime",
        includeDex = true,
      )
      val fakeAdb =
        object : DwfRuntimeCommand.AdbRunner {
          override fun run(
            serial: String?,
            vararg adbArgs: String,
          ): DwfRuntimeCommand.AdbRunner.CommandOutput {
            val joined = adbArgs.joinToString(" ")
            return when {
              joined == "devices" ->
                DwfRuntimeCommand.AdbRunner.CommandOutput(
                  0,
                  "List of devices attached\nemulator-5554\tdevice\n",
                  "",
                )
              joined == "shell pm list packages" ->
                DwfRuntimeCommand.AdbRunner.CommandOutput(
                  0,
                  "package:com.google.wear.watchface.runtime\n",
                  "",
                )
              joined == "shell pm path com.google.wear.watchface.runtime" ->
                DwfRuntimeCommand.AdbRunner.CommandOutput(
                  0,
                  "package:/system_ext/priv-app/DeclarativeWatchFaceRuntime/DeclarativeWatchFaceRuntime.apk\n",
                  "",
                )
              adbArgs.firstOrNull() == "pull" -> {
                val dest = File(adbArgs[2])
                remoteSourceApk.copyTo(dest, overwrite = true)
                DwfRuntimeCommand.AdbRunner.CommandOutput(0, "1 file pulled", "")
              }
              else -> DwfRuntimeCommand.AdbRunner.CommandOutput(1, "", "unexpected: $joined")
            }
          }
        }

      val resolved =
        DwfRuntimeCommand.resolveAuto(
          cacheRoot = cacheDir,
          env = { null },
          adbRunner = fakeAdb,
          allowAdbPull = true,
        )
      assertNotNull(resolved)
      assertTrue(resolved.isFile)
      val meta = DwfRuntimeCommand.cachedMetadata(cacheDir)
      assertNotNull(meta)
      assertEquals("com.google.wear.watchface.runtime", meta.packageName)
    } finally {
      remoteSourceApk.delete()
      cacheDir.deleteRecursively()
    }
  }

  private fun writeTestRuntimeApk(target: File, pkg: String, includeDex: Boolean) {
    val manifest =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
        <application>
          <service android:name=".WatchFaceControlService">
            <intent-filter>
              <action android:name="${DwfRuntimeCommand.WATCH_FACE_CONTROL_ACTION}" />
            </intent-filter>
          </service>
        </application>
      </manifest>
      """
        .trimIndent()
    ZipOutputStream(target.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write(manifest.toByteArray(Charsets.UTF_8))
      zos.closeEntry()
      if (includeDex) {
        zos.putNextEntry(ZipEntry("classes.dex"))
        zos.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A))
        zos.closeEntry()
      }
    }
  }
}
