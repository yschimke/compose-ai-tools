package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * The split-Skiko repair from #4220: a bundle's recorded coordinates name the bindings but not the
 * platform native they link against, and serve promotes those bindings ahead of its own daemon
 * sidecar. Unpaired, they link against the server's older `libskiko` and every render dies with
 * `UnsatisfiedLinkError`.
 */
class SkikoNativePairingTest {

  private fun maven(artifact: String, version: String, group: String = "org.jetbrains.skiko") =
    BundleReader.ClasspathEntry.Maven(
      group = group,
      artifact = artifact,
      version = version,
      type = "jar",
      sha256 = null,
    )

  @Test
  fun `host runtime artifact covers every target skiko publishes`() {
    assertEquals("linux-x64", SkikoNativePairing.hostRuntimeArtifact("Linux", "amd64")?.suffix())
    assertEquals(
      "linux-arm64",
      SkikoNativePairing.hostRuntimeArtifact("Linux", "aarch64")?.suffix(),
    )
    assertEquals(
      "macos-arm64",
      SkikoNativePairing.hostRuntimeArtifact("Mac OS X", "aarch64")?.suffix(),
    )
    assertEquals(
      "macos-x64",
      SkikoNativePairing.hostRuntimeArtifact("Mac OS X", "x86_64")?.suffix(),
    )
    assertEquals(
      "windows-x64",
      SkikoNativePairing.hostRuntimeArtifact("Windows 11", "amd64")?.suffix(),
    )
    assertEquals(
      "windows-arm64",
      SkikoNativePairing.hostRuntimeArtifact("Windows 11", "arm64")?.suffix(),
    )
    // A host Skiko publishes no native for: there is nothing useful to add, so say so rather than
    // synthesizing a coordinate that can only 404.
    assertNull(SkikoNativePairing.hostRuntimeArtifact("SunOS", "sparc"))
    assertNull(SkikoNativePairing.hostRuntimeArtifact("Linux", "riscv64"))
  }

  private fun String.suffix() = removePrefix("skiko-awt-runtime-")

  /**
   * The exact m3-catalog shape that took the public server's live lane down: `skiko-awt:0.148.2`
   * and no runtime artifact at all, because the six platform natives reach a Gradle-resolved
   * classpath through `strictly` constraints rather than as recorded coordinates.
   */
  @Test
  fun `bindings recorded without their native synthesize the host runtime coordinate`() {
    val coords =
      listOf(
        maven("material3-desktop", "1.12.0-alpha02", group = "org.jetbrains.compose.material3"),
        maven("skiko-awt", "0.148.2"),
      )

    val repair = SkikoNativePairing.missingHostRuntime(coords, "Linux", "amd64")

    assertEquals("org.jetbrains.skiko", repair?.group)
    assertEquals("skiko-awt-runtime-linux-x64", repair?.artifact)
    assertEquals("0.148.2", repair?.version)
    assertEquals("jar", repair?.type)
    // The bundle never recorded a hash for an artifact it never listed; claiming one would be a
    // lie the resolver would then "verify".
    assertNull(repair?.sha256)
  }

  @Test
  fun `a bundle that already carries the host native is left alone`() {
    val coords =
      listOf(maven("skiko-awt", "0.148.2"), maven("skiko-awt-runtime-linux-x64", "0.148.2"))

    assertNull(SkikoNativePairing.missingHostRuntime(coords, "Linux", "amd64"))
  }

  /**
   * A native for the *wrong* platform (a bundle packed on a mac, served on Linux) is not the pair
   * this host needs — that is the same skew wearing a matching version number, and it must still be
   * repaired.
   */
  @Test
  fun `a native for another platform does not count as paired`() {
    val coords =
      listOf(maven("skiko-awt", "0.148.2"), maven("skiko-awt-runtime-macos-arm64", "0.148.2"))

    assertEquals(
      "skiko-awt-runtime-linux-x64",
      SkikoNativePairing.missingHostRuntime(coords, "Linux", "amd64")?.artifact,
    )
  }

  /**
   * A stale native at a different version is exactly the split — repair to the bindings' version.
   */
  @Test
  fun `a host native at a different version is repaired to the bindings version`() {
    val coords =
      listOf(maven("skiko-awt", "0.148.2"), maven("skiko-awt-runtime-linux-x64", "0.144.6"))

    assertEquals(
      "0.148.2",
      SkikoNativePairing.missingHostRuntime(coords, "Linux", "amd64")?.version,
    )
  }

  @Test
  fun `a bundle with no skiko at all is untouched`() {
    val coords =
      listOf(
        maven("robolectric", "4.16", group = "org.robolectric"),
        maven("okio", "3.20.0", group = "com.squareup.okio"),
      )

    assertNull(SkikoNativePairing.missingHostRuntime(coords, "Linux", "amd64"))
  }

  /**
   * The backstop reads the pair that will actually LOAD, which classpath order decides — the
   * bindings are classes and `libskiko-<target>.so` is a root resource, both plain classloader
   * lookups. A promoted 0.148.2 bindings jar ahead of the server sidecar's 0.144.6 native is
   * exactly the m3-catalog outage.
   */
  @Test
  fun `classpath skew names the pair that will actually load`() {
    val skewed =
      listOf(
        "/cache/org.jetbrains.skiko/skiko-awt/0.148.2/skiko-awt-0.148.2.jar",
        "/opt/serve/lib-daemon-desktop/skiko-awt-0.144.6.jar",
        "/opt/serve/lib-daemon-desktop/skiko-awt-runtime-linux-x64-0.144.6.jar",
      )

    val warning = SkikoNativePairing.classpathSkew(skewed)

    assertNotNull(warning)
    assertContains(warning, "bindings 0.148.2")
    assertContains(warning, "libskiko 0.144.6")
  }

  @Test
  fun `a repaired classpath reports no skew`() {
    val repaired =
      listOf(
        "/cache/org.jetbrains.skiko/skiko-awt/0.148.2/skiko-awt-0.148.2.jar",
        "/cache/org.jetbrains.skiko/skiko-awt-runtime-linux-x64/0.148.2/skiko-awt-runtime-linux-x64-0.148.2.jar",
        // The sidecar's older pair is still present, and still shadowed by the two above.
        "/opt/serve/lib-daemon-desktop/skiko-awt-0.144.6.jar",
        "/opt/serve/lib-daemon-desktop/skiko-awt-runtime-linux-x64-0.144.6.jar",
      )

    assertNull(SkikoNativePairing.classpathSkew(repaired))
  }

  @Test
  fun `a fatal Skia link error is answered with the skew its daemon classpath carries`() {
    val descriptor =
      writeDescriptor(
        listOf(
          "/cache/org.jetbrains.skiko/skiko-awt/0.148.2/skiko-awt-0.148.2.jar",
          "/opt/serve/lib-daemon-desktop/skiko-awt-runtime-linux-x64-0.144.6.jar",
        )
      )

    val diagnosis =
      assertNotNull(
        SkikoNativePairing.linkageDiagnosis(
          "render failed: UnsatisfiedLinkError: 'int " +
            "org.jetbrains.skia.paragraph.ParagraphKt._nGetUnresolvedCodepointsCount(long)'",
          descriptor,
        )
      )

    assertContains(diagnosis, "bindings 0.148.2")
    assertContains(diagnosis, "libskiko 0.144.6")
  }

  @Test
  fun `a link error with nothing to add says nothing`() {
    val coherent =
      writeDescriptor(
        listOf("/cache/skiko-awt-0.148.2.jar", "/cache/skiko-awt-runtime-linux-x64-0.148.2.jar")
      )
    val skewed =
      writeDescriptor(
        listOf("/cache/skiko-awt-0.148.2.jar", "/cache/skiko-awt-runtime-linux-x64-0.144.6.jar")
      )
    val skiaFailure =
      "render failed: UnsatisfiedLinkError: 'int org.jetbrains.skia.paragraph.ParagraphKt._n(long)'"

    assertNull(
      SkikoNativePairing.linkageDiagnosis(skiaFailure, coherent),
      "a matched pair means this link error has some other cause — don't invent one",
    )
    assertNull(
      SkikoNativePairing.linkageDiagnosis(
        "render failed: UnsatisfiedLinkError: 'void com.example.Native.init()'",
        skewed,
      ),
      "a link error that is not Skia's says nothing about the Skiko pair",
    )
    assertNull(
      SkikoNativePairing.linkageDiagnosis(skiaFailure, File("/no/such/daemon-launch.json")),
      "an unreadable descriptor says nothing rather than guessing",
    )
  }

  private fun writeDescriptor(classpath: List<String>): File {
    val dir = Files.createTempDirectory("skiko-linkage-diagnosis").toFile()
    val file = File(dir, "daemon-launch.json")
    file.writeText(
      Json.encodeToString(
        DaemonLaunchDescriptor.serializer(),
        DaemonLaunchDescriptor(
          schemaVersion = 2,
          modulePath = ":catalog",
          variant = "desktop",
          enabled = true,
          mainClass = "ee.schimke.composeai.daemon.DaemonMain",
          classpath = classpath,
          jvmArgs = emptyList(),
          systemProperties = emptyMap(),
          workingDirectory = dir.absolutePath,
          manifestPath = File(dir, "previews.json").absolutePath,
        ),
      )
    )
    return file
  }

  /** Nothing to compare is not a skew — an Android (Robolectric) daemon carries no Skiko at all. */
  @Test
  fun `a classpath missing one half reports nothing`() {
    assertNull(
      SkikoNativePairing.classpathSkew(listOf("/opt/serve/lib-daemon-android/robolectric-4.16.jar"))
    )
    assertNull(
      SkikoNativePairing.classpathSkew(listOf("/cache/skiko-awt-0.148.2.jar")),
      "bindings with no native on the classpath at all is a different failure, diagnosed elsewhere",
    )
  }
}
