package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [BundleClasspathGaps] — the record that lets a fatal linkage trip name the
 * coordinates this server could not resolve, instead of only the class the JVM couldn't find
 * (issues #4259 / #4265).
 */
class BundleClasspathGapsTest {

  private val destDir = Files.createTempDirectory("classpath-gaps-test-").toFile()
  private val descriptor = File(destDir, "daemon-launch.json")
  private val logs = mutableListOf<String>()

  @AfterTest fun cleanup() = destDir.deleteRecursively().let {}

  private fun maven(group: String, artifact: String, version: String = "1.0.0-SNAPSHOT") =
    BundleReader.ClasspathEntry.Maven(
      group = group,
      artifact = artifact,
      version = version,
      type = "aar",
      sha256 = null,
    )

  /** The real shape from the reports: the whole Remote Compose runtime unresolved. */
  private fun recordRemoteComposeGap() =
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved =
        listOf(
          maven("androidx.compose.remote", "remote-core"),
          maven("androidx.compose.remote", "remote-player-view"),
          maven("androidx.wear.compose.remote", "remote-material3"),
        ),
      total = 84,
      system = "remote-m3",
      onLog = { logs += it },
    )

  @Test
  fun `a complete classpath records nothing and diagnoses nothing`() {
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved = emptyList(),
      total = 84,
      system = "remote-m3",
      onLog = { logs += it },
    )

    assertTrue(logs.isEmpty(), "a healthy catalog must not log a gap")
    assertEquals(emptyList(), destDir.listFiles()?.toList() ?: emptyList())
    assertNull(
      BundleClasspathGaps.linkageDiagnosis("render failed: NoClassDefFoundError: a/B", descriptor)
    )
  }

  @Test
  fun `an unresolved coordinate whose group owns the missing type is named`() {
    recordRemoteComposeGap()

    val diagnosis =
      BundleClasspathGaps.linkageDiagnosis(
        "render failed: NoClassDefFoundError: " +
          "androidx/compose/remote/player/view/RemoteComposePlayer",
        descriptor,
      )

    val text = requireNotNull(diagnosis) { "a linkage failure with a recorded gap must diagnose" }
    // The artifact whose id tokens (`player`, `view`) also appear in the package wins over its
    // siblings in the same group — otherwise the sentence would blame `remote-core`.
    assertContains(text, "androidx.compose.remote:remote-player-view:1.0.0-SNAPSHOT, which is")
    assertContains(text, "3 of them")
    assertContains(text, "84 Maven coordinate(s)")
  }

  @Test
  fun `a linkage failure the gap cannot attribute still reports the gap`() {
    recordRemoteComposeGap()

    val text =
      requireNotNull(
        BundleClasspathGaps.linkageDiagnosis(
          "render failed: NoClassDefFoundError: com/example/other/Thing",
          descriptor,
        )
      )

    assertContains(text, "one of them is likely")
    assertContains(text, "androidx.compose.remote:remote-core:1.0.0-SNAPSHOT")
  }

  @Test
  fun `failures an absent artifact does not explain are left alone`() {
    recordRemoteComposeGap()

    // Skiko's split-native fault has its own diagnosis; a VerifyError is bad bytecode, not a
    // missing jar. Neither is made clearer by listing unresolved coordinates.
    assertNull(
      BundleClasspathGaps.linkageDiagnosis(
        "render failed: UnsatisfiedLinkError: 'long org.jetbrains.skia.Foo._nMake()'",
        descriptor,
      )
    )
    assertNull(BundleClasspathGaps.linkageDiagnosis("render failed: VerifyError: bad", descriptor))
  }

  @Test
  fun `the aggregate is logged once with every unresolved coordinate`() {
    recordRemoteComposeGap()

    assertEquals(1, logs.size)
    assertContains(logs.single(), "3 of 84 classpath coordinate(s) did not resolve")
    assertContains(logs.single(), "androidx.wear.compose.remote:remote-material3:1.0.0-SNAPSHOT")
  }
}
