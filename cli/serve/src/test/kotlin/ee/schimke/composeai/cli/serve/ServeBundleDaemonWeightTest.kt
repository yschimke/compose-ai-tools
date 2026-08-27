package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.DaemonLaunchDescriptor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Guards [ServeBundleDaemon.liveSeatWeightForDescriptor] — the backend-weight detector for the
 * Gradle **source-build** catalog path, which has no bundle `manifest.backend` to read and instead
 * sniffs the built daemon descriptor's `robolectric.*` sysprops. Without this, a source-served
 * Android catalog would be charged as a cheap desktop daemon and undercut the live-seat budget's
 * OOM protection.
 */
class ServeBundleDaemonWeightTest {

  private val json = Json { encodeDefaults = true }

  private fun descriptorFile(
    systemProperties: Map<String, String>,
    jvmArgs: List<String> = emptyList(),
  ): File {
    val descriptor =
      DaemonLaunchDescriptor(
        schemaVersion = 2,
        modulePath = ":catalog",
        variant = "debug",
        enabled = true,
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = listOf("app.jar"),
        jvmArgs = jvmArgs,
        systemProperties = systemProperties,
        workingDirectory = ".",
        manifestPath = "previews.json",
      )
    return File.createTempFile("daemon-launch", ".json").apply {
      deleteOnExit()
      writeText(json.encodeToString(DaemonLaunchDescriptor.serializer(), descriptor))
    }
  }

  @Test
  fun `an Android descriptor (robolectric sysprops) is charged the Android weight`() {
    val file =
      descriptorFile(
        systemProperties =
          mapOf("robolectric.graphicsMode" to "NATIVE", "robolectric.looperMode" to "PAUSED")
      )
    assertEquals(
      ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT,
      ServeBundleDaemon.liveSeatWeightForDescriptor(file),
    )
  }

  @Test
  fun `robolectric flags carried as jvm args are also detected`() {
    val file =
      descriptorFile(
        systemProperties = emptyMap(),
        jvmArgs =
          listOf(
            "-Drobolectric.graphicsMode=NATIVE",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
          ),
      )
    assertEquals(
      ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT,
      ServeBundleDaemon.liveSeatWeightForDescriptor(file),
    )
  }

  @Test
  fun `a desktop descriptor (no robolectric) keeps weight 1`() {
    val file =
      descriptorFile(
        systemProperties = mapOf("composeai.daemon.userClassDirs" to "classes"),
        jvmArgs = listOf("--enable-native-access=ALL-UNNAMED"),
      )
    assertEquals(1, ServeBundleDaemon.liveSeatWeightForDescriptor(file))
  }

  @Test
  fun `a missing or unreadable descriptor defaults to weight 1`() {
    assertEquals(
      1,
      ServeBundleDaemon.liveSeatWeightForDescriptor(File("/no/such/daemon-launch.json")),
    )
  }
}
