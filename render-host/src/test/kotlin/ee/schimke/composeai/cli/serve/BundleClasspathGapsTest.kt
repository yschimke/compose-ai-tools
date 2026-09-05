package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

  /**
   * meshcore-mobile's shape (#187): nothing unresolved, but part of the Remote Compose family
   * resolved to another androidx.dev build's bytes under the same `1.0.0-SNAPSHOT` version string.
   */
  private fun recordRemoteComposeMismatch() =
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved = emptyList(),
      total = 119,
      system = "meshcore-mobile",
      onLog = { logs += it },
      mismatched =
        listOf(
          maven("androidx.compose.remote", "remote-player-core"),
          maven("androidx.compose.remote", "remote-player-view"),
        ),
    )

  @Test
  fun `a mismatched coordinate that owns the missing member is named as the cause`() {
    recordRemoteComposeMismatch()

    val text =
      requireNotNull(
        BundleClasspathGaps.linkageDiagnosis(
          "render failed: NoSuchFieldError: Class androidx.compose.remote.core.RemoteClock does " +
            "not have member field 'androidx.compose.remote.core.RemoteClock SYSTEM'",
          descriptor,
        )
      ) {
        "a hash mismatch is the whole cause here — nothing was unresolved to fall back on"
      }

    assertContains(text, "resolved to different bytes")
    assertContains(text, "androidx.compose.remote:remote-player-core:1.0.0-SNAPSHOT")
    assertContains(text, "two builds of one library")
    assertContains(text, "-SNAPSHOT")
  }

  @Test
  fun `a mismatch nothing in the failure points at is not claimed as the cause`() {
    recordRemoteComposeMismatch()

    // The wrong bytes are real, but they are not what this `NoSuchMethodError` is about — blaming
    // them would send the reader to the wrong artifact. Better to say nothing.
    assertNull(
      BundleClasspathGaps.linkageDiagnosis(
        "render failed: NoSuchMethodError: 'void com.example.other.Thing.go()'",
        descriptor,
      )
    )
  }

  @Test
  fun `an attributable mismatch outranks the unresolved list`() {
    // Both shapes at once. The mismatch names the artifact AND what is wrong with it, so it wins.
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved = listOf(maven("com.example.other", "widgets")),
      total = 119,
      system = "meshcore-mobile",
      onLog = { logs += it },
      mismatched = listOf(maven("androidx.compose.remote", "remote-player-core")),
    )

    val text =
      requireNotNull(
        BundleClasspathGaps.linkageDiagnosis(
          "render failed: NoSuchFieldError: Class androidx.compose.remote.core.RemoteClock does " +
            "not have member field 'androidx.compose.remote.core.RemoteClock SYSTEM'",
          descriptor,
        )
      )

    assertContains(text, "resolved to different bytes")
    assertTrue(
      "could not resolve" !in text,
      "the unresolved sentence must not be appended to the mismatch one: $text",
    )
  }

  @Test
  fun `mismatches are logged separately from unresolved coordinates`() {
    recordRemoteComposeMismatch()

    assertEquals(1, logs.size, "nothing was unresolved, so only the mismatch line: $logs")
    assertContains(logs.single(), "2 of 119 classpath coordinate(s) resolved to bytes")
    assertContains(logs.single(), "androidx.compose.remote:remote-player-view:1.0.0-SNAPSHOT")
  }

  /**
   * The generic half fires on any unresolved coordinate, related or not. Split out so a caller with
   * a more specific diagnosis — a Remote Compose family split, say — can sit between attribution
   * and "something is missing", instead of having its answer swallowed by one unrelated optional
   * dependency the server could not fetch (Codex review on compose-preview-server#219).
   */
  @Test
  fun `an unattributed gap answers last, not first`() {
    BundleClasspathGaps.record(
      destDir = destDir,
      unresolved = listOf(maven("com.squareup.okhttp3", "okhttp", "5.5.0")),
      total = 84,
      system = "meshcore-mobile",
      onLog = { logs += it },
    )
    val reason = "java.lang.NoSuchFieldError: androidx.compose.remote.core.RemoteClock"

    assertNull(
      BundleClasspathGaps.attributedDiagnosis(reason, descriptor),
      "an unresolved okhttp explains nothing about a Remote Compose field",
    )
    val unattributed = BundleClasspathGaps.unattributedDiagnosis(reason, descriptor)
    assertNotNull(unattributed, "it is still worth saying once nothing better has been found")
    assertContains(unattributed, "one of them is likely where the missing type lives")
    assertEquals(
      unattributed,
      BundleClasspathGaps.linkageDiagnosis(reason, descriptor),
      "the combined entry point keeps its old answer",
    )
  }
}
