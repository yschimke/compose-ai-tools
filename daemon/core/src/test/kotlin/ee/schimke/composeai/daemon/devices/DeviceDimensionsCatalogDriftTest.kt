package ee.schimke.composeai.daemon.devices

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceDimensionsCatalogDriftTest {

  @Test
  fun daemonCatalogMatchesGradlePluginCatalog() {
    val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
    val plugin =
      readCatalog(
        repoRoot.resolve(
          "gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DeviceDimensions.kt"
        )
      )
    val daemon =
      readCatalog(
        repoRoot.resolve(
          "daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/devices/DeviceDimensions.kt"
        )
      )

    assertFalse("plugin DeviceDimensions catalog should not be empty", plugin.isEmpty())
    assertFalse("daemon DeviceDimensions catalog should not be empty", daemon.isEmpty())
    assertEquals(
      "DeviceDimensions ids drifted between plugin and daemon copies",
      plugin.keys,
      daemon.keys,
    )
    assertEquals(
      "DeviceDimensions geometry drifted between plugin and daemon copies",
      plugin,
      daemon,
    )
  }

  @Test
  fun readCatalogIgnoresCommentedEntries() {
    val temp = Files.createTempFile("device-dimensions-catalog", ".kt")
    try {
      Files.writeString(
        temp,
        """
        val KNOWN_DEVICES = mapOf(
          // "commented" to DeviceSpec(1, 2, 3.0f),
          "active" to DeviceSpec(4, 5, 6.0f),
        )
        """
          .trimIndent(),
      )

      assertEquals(mapOf("active" to DeviceEntry(4, 5, 6.0f)), readCatalog(temp))
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  private fun readCatalog(path: Path): Map<String, DeviceEntry> {
    val text = Files.readString(path)
    return entryRegex.findAll(text).associate { match ->
      val (id, width, height, density) = match.destructured
      id to DeviceEntry(width.toInt(), height.toInt(), density.toFloat())
    }
  }

  private fun findRepoRoot(start: Path): Path {
    var current: Path? = start
    while (current != null) {
      if (
        Files.exists(current.resolve("settings.gradle.kts")) &&
          Files.exists(
            current.resolve(
              "gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DeviceDimensions.kt"
            )
          )
      ) {
        return current
      }
      current = current.parent
    }
    error("Could not locate repository root from $start")
  }

  private data class DeviceEntry(val widthDp: Int, val heightDp: Int, val density: Float)

  companion object {
    private val entryRegex =
      Regex("(?m)^\\s*\"([^\"]+)\"\\s+to\\s+DeviceSpec\\((\\d+),\\s*(\\d+),\\s*([0-9.]+)f\\)")
  }
}
