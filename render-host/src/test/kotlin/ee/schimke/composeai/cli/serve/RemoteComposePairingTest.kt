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
 * Coverage for [RemoteComposePairing] — the diagnosis for a daemon classpath that carries two
 * Remote Compose lines because the bundle recorded part of the family and the sidecar supplied the
 * rest (compose-preview-server#187: `NoSuchFieldError … RemoteClock … SYSTEM` on every IR replay,
 * with a breaker reason that named no cause).
 */
class RemoteComposePairingTest {

  private val destDir = Files.createTempDirectory("remote-compose-line-test-").toFile()
  private val descriptor = File(destDir, "daemon-launch.json")
  private val logs = mutableListOf<String>()

  @AfterTest fun cleanup() = destDir.deleteRecursively().let {}

  private fun maven(artifact: String, version: String, group: String = "androidx.compose.remote") =
    BundleReader.ClasspathEntry.Maven(
      group = group,
      artifact = artifact,
      version = version,
      type = "aar",
      sha256 = null,
    )

  private fun sidecar(vararg jars: String) = jars.map { "/opt/compose-preview/lib/$it" }

  /** The `meshcore-mobile` shape: a snapshot bundle half-covering a sidecar on released alphas. */
  private fun recordMeshcoreShape() =
    RemoteComposePairing.record(
      destDir = destDir,
      bundle =
        RemoteComposePairing.bundleMembers(
          listOf(
            maven("remote-player-core", "1.0.0-SNAPSHOT"),
            maven("remote-creation", "1.0.0-SNAPSHOT"),
          )
        ),
      sidecar =
        RemoteComposePairing.sidecarMembers(
          sidecar(
            "remote-core-1.0.0-alpha18.jar",
            "remote-player-core-1.0.0-alpha18.jar",
            "kotlin-stdlib-2.4.10.jar",
          )
        ),
      system = "meshcore-mobile",
      onLog = { logs += it },
    )

  @Test
  fun `a bundle carrying part of the family at another version is a skew`() {
    recordMeshcoreShape()

    val logged = logs.singleOrNull()
    assertNotNull(logged, "a mixed family must be logged at materialization: $logs")
    assertContains(logged, "remote-core")
    assertContains(logged, "1.0.0-SNAPSHOT")
    assertContains(logged, "1.0.0-alpha18")
  }

  @Test
  fun `the skew is the diagnosis a Remote Compose linkage trip carries`() {
    recordMeshcoreShape()

    val diagnosis =
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchFieldError: Class androidx.compose.remote.core.RemoteClock does not " +
          "have member field 'androidx.compose.remote.core.RemoteClock SYSTEM'",
        descriptor,
      )

    assertNotNull(diagnosis, "the trip that this record exists for must be diagnosed")
    assertContains(diagnosis, "remote-core")
  }

  @Test
  fun `the internal class-name form is diagnosed too`() {
    recordMeshcoreShape()

    assertNotNull(
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoClassDefFoundError: androidx/compose/remote/player/core/RemoteDocument",
        descriptor,
      )
    )
  }

  @Test
  fun `a bundle carrying the whole family shadows the sidecar and is coherent`() {
    RemoteComposePairing.record(
      destDir = destDir,
      bundle =
        RemoteComposePairing.bundleMembers(
          listOf(
            maven("remote-core", "1.0.0-SNAPSHOT"),
            maven("remote-player-core", "1.0.0-SNAPSHOT"),
          )
        ),
      sidecar =
        RemoteComposePairing.sidecarMembers(
          sidecar("remote-core-1.0.0-alpha18.jar", "remote-player-core-1.0.0-alpha18.jar")
        ),
      system = "meshcore-mobile",
      onLog = { logs += it },
    )

    assertTrue(logs.isEmpty(), "a fully-carried family is coherent whatever its version: $logs")
    assertNull(
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchFieldError: androidx.compose.remote.core.RemoteClock",
        descriptor,
      )
    )
  }

  @Test
  fun `a bundle on the sidecar's own version is coherent`() {
    RemoteComposePairing.record(
      destDir = destDir,
      bundle = RemoteComposePairing.bundleMembers(listOf(maven("remote-core", "1.0.0-alpha18"))),
      sidecar =
        RemoteComposePairing.sidecarMembers(
          sidecar("remote-core-1.0.0-alpha18.jar", "remote-player-core-1.0.0-alpha18.jar")
        ),
      system = "meshcore-mobile",
      onLog = { logs += it },
    )

    assertTrue(logs.isEmpty(), "one version across both sides is one line: $logs")
  }

  @Test
  fun `a bundle that names no Remote Compose artifact records nothing`() {
    RemoteComposePairing.record(
      destDir = destDir,
      bundle = RemoteComposePairing.bundleMembers(listOf(maven("material3", "1.10.0", "androidx"))),
      sidecar = RemoteComposePairing.sidecarMembers(sidecar("remote-core-1.0.0-alpha18.jar")),
      system = "desktop-catalog",
      onLog = { logs += it },
    )

    assertTrue(logs.isEmpty(), "a catalog with no Remote Compose previews says nothing: $logs")
    assertTrue(
      File(destDir, "remote-compose-line.json").exists().not(),
      "no record is written for a catalog the family cannot explain",
    )
  }

  @Test
  fun `a failure outside the Remote Compose packages is not this record's to claim`() {
    recordMeshcoreShape()

    assertNull(
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchMethodError: androidx.compose.material3.AppBarKt.TopAppBar-gNPyAyM",
        descriptor,
      ),
      "a Material3 linkage error says nothing about the Remote Compose family",
    )
  }

  @Test
  fun `a non-linkage failure is not diagnosed`() {
    recordMeshcoreShape()

    assertNull(
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.IllegalStateException: androidx.compose.remote.core.RemoteClock is not ready",
        descriptor,
      )
    )
  }

  @Test
  fun `an unrelated remote- jar on the sidecar is not read as a family member`() {
    assertTrue(
      RemoteComposePairing.sidecarMembers(sidecar("remote-config-21.6.0.jar")).isEmpty(),
      "only the published Remote Compose artifacts count",
    )
  }

  @Test
  fun `the wear family travels with the rest`() {
    assertContains(
      RemoteComposePairing.bundleMembers(
          listOf(maven("remote-material3", "1.0.0-alpha10", "androidx.wear.compose.remote"))
        )
        .map { it.artifact },
      "remote-material3",
    )
  }

  @Test
  fun `a demoted line says the sidecar won, and the record remembers it`() {
    RemoteComposePairing.record(
      destDir = destDir,
      bundle =
        RemoteComposePairing.bundleMembers(listOf(maven("remote-creation", "1.0.0-SNAPSHOT"))),
      sidecar =
        RemoteComposePairing.sidecarMembers(
          sidecar("remote-core-1.0.0-alpha18.jar", "remote-player-core-1.0.0-alpha18.jar")
        ),
      system = "meshcore-mobile",
      onLog = { logs += it },
      demoted = true,
    )

    val logged = logs.single()
    assertContains(logged, "sidecar's line win")
    val diagnosis =
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchFieldError: androidx.compose.remote.core.RemoteClock",
        descriptor,
      )
    assertNotNull(diagnosis)
    assertContains(diagnosis, "sidecar's line win")
  }

  @Test
  fun `family membership is what the demotion keys on`() {
    assertTrue(RemoteComposePairing.isFamilyMember(maven("remote-core", "1.0.0-alpha18")))
    assertTrue(
      RemoteComposePairing.isFamilyMember(
        maven("remote-material3", "1.0.0-alpha10", "androidx.wear.compose.remote")
      )
    )
    assertTrue(
      RemoteComposePairing.isFamilyMember(
          maven("material3", "1.10.0", "androidx.compose.material3")
        )
        .not()
    )
  }

  @Test
  fun `a skewed family is demoted out of the parent overlay, and nothing else is`() {
    val remoteCore = maven("remote-core", "1.0.0-SNAPSHOT")
    val material3 = maven("material3", "1.10.0", "androidx.compose.material3")

    val base = setOf(RemoteComposePairing.BASE_GROUP)
    assertTrue(
      ServeBundleDaemon.overlaysDaemonSidecar(remoteCore, demotedRemoteComposeGroups = emptySet()),
      "a coherent family keeps the catalog's own versions, as every other androidx artifact does",
    )
    assertTrue(
      ServeBundleDaemon.overlaysDaemonSidecar(remoteCore, demotedRemoteComposeGroups = base).not(),
      "a half-carried family must fall behind the sidecar so one line answers the IR replay",
    )
    assertTrue(
      ServeBundleDaemon.overlaysDaemonSidecar(material3, demotedRemoteComposeGroups = base),
      "the demotion is the Remote Compose family's alone — Material3 still wins for the catalog",
    )
    assertTrue(
      ServeBundleDaemon.overlaysDaemonSidecar(
        maven("remote-material3", "1.0.0-alpha10", "androidx.wear.compose.remote"),
        demotedRemoteComposeGroups = base,
      ),
      "a split base line must not drag the independently-versioned Wear line down with it",
    )
  }

  /**
   * The demotion's justification is that the sidecar's IR replay links against the family. A bundle
   * with no IR has consumer bytecode compiled against the versions it records, so the catalog keeps
   * them — the split is reported without being rearranged (Codex review on #219).
   */
  @Test
  fun `only an IR bundle demotes, a class-backed one keeps its own versions`() {
    val skewed =
      RemoteComposePairing.Line(
        bundle = listOf(RemoteComposePairing.Member("remote-creation", "1.0.0-SNAPSHOT")),
        sidecar = listOf(RemoteComposePairing.Member("remote-core", "1.0.0-alpha18")),
      )
    assertNotNull(RemoteComposePairing.skew(skewed), "the split itself is reported either way")

    for (hasIr in listOf(true, false)) {
      val demoted = if (hasIr) RemoteComposePairing.skewedGroups(skewed) else emptySet()
      assertEquals(
        hasIr,
        ServeBundleDaemon.overlaysDaemonSidecar(maven("remote-core", "1.0.0-SNAPSHOT"), demoted)
          .not(),
        "hasIr=$hasIr must decide whether the family falls behind the sidecar",
      )
    }
  }

  /**
   * Two sides reading one `-SNAPSHOT` are not two sides reading one build. Not enough to demote on,
   * but enough to say once a linkage error has actually happened (Codex review on #219).
   */
  @Test
  fun `a mutable version on both sides is not proof of coherence once something breaks`() {
    val line =
      RemoteComposePairing.Line(
        bundle = listOf(RemoteComposePairing.Member("remote-creation", "1.0.0-SNAPSHOT")),
        sidecar = listOf(RemoteComposePairing.Member("remote-player-core", "1.0.0-SNAPSHOT")),
      )
    RemoteComposePairing.record(
      destDir = destDir,
      bundle = line.bundle,
      sidecar = line.sidecar,
      system = "meshcore-mobile",
      onLog = { logs += it },
    )

    assertTrue(logs.isEmpty(), "a matching version must not demote or shout at materialization")
    val diagnosis =
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchFieldError: androidx.compose.remote.core.RemoteClock",
        descriptor,
      )
    assertNotNull(diagnosis, "a Remote Compose trip is the evidence the version string withheld")
    assertContains(diagnosis, "names no single build")
    assertContains(diagnosis, "remote-player-core")
  }

  @Test
  fun `released versions that agree stay coherent even after a trip`() {
    RemoteComposePairing.record(
      destDir = destDir,
      bundle =
        RemoteComposePairing.bundleMembers(listOf(maven("remote-creation", "1.0.0-alpha18"))),
      sidecar = RemoteComposePairing.sidecarMembers(sidecar("remote-core-1.0.0-alpha18.jar")),
      system = "meshcore-mobile",
      onLog = { logs += it },
    )

    assertNull(
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchFieldError: androidx.compose.remote.core.RemoteClock",
        descriptor,
      ),
      "one released version on both sides names one build — this failure is someone else's",
    )
  }

  /**
   * `androidx.compose.remote` and `androidx.wear.compose.remote` version independently — this
   * server's own sidecar ships `remote-core` alpha18 beside `remote-material3` alpha10 — so one
   * "single version across the whole family" rule would call every such bundle skewed and demote a
   * base family it carries coherently (Codex review on #219).
   */
  @Test
  fun `the base and Wear lines are compared apart, not against each other`() {
    val line =
      RemoteComposePairing.Line(
        bundle =
          RemoteComposePairing.bundleMembers(
            listOf(
              maven("remote-core", "1.0.0-alpha18"),
              maven("remote-player-core", "1.0.0-alpha18"),
              maven("remote-material3", "1.0.0-alpha10", "androidx.wear.compose.remote"),
            )
          ),
        sidecar =
          RemoteComposePairing.sidecarMembers(
            sidecar("remote-core-1.0.0-alpha18.jar", "remote-material3-1.0.0-alpha10.jar")
          ),
      )

    assertTrue(
      RemoteComposePairing.skewedGroups(line).isEmpty(),
      "two lines at their own versions are two coherent lines, not one skewed family",
    )
    assertNull(RemoteComposePairing.skew(line))
  }

  @Test
  fun `only a group whose own supply is split is demoted`() {
    val line =
      RemoteComposePairing.Line(
        bundle =
          RemoteComposePairing.bundleMembers(
            listOf(
              maven("remote-creation", "1.0.0-SNAPSHOT"),
              maven("remote-material3", "1.0.0-alpha10", "androidx.wear.compose.remote"),
            )
          ),
        sidecar =
          RemoteComposePairing.sidecarMembers(
            sidecar("remote-core-1.0.0-alpha18.jar", "remote-material3-1.0.0-alpha10.jar")
          ),
      )

    assertEquals(
      setOf(RemoteComposePairing.BASE_GROUP),
      RemoteComposePairing.skewedGroups(line),
      "the base line is split snapshot-against-alpha18; the Wear line the bundle covers is intact",
    )
  }

  @Test
  fun `a missing record diagnoses nothing`() {
    assertNull(
      RemoteComposePairing.linkageDiagnosis(
        "java.lang.NoSuchFieldError: androidx.compose.remote.core.RemoteClock",
        descriptor,
      )
    )
  }
}
