package ee.schimke.composeai.render.session.subprocess

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.DaemonClientFactory
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import java.io.File
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

/**
 * `SubprocessRenderSessions.open` gates the on-disk descriptor's `schemaVersion` before anything
 * acts on its fields (#5105, deferred from #4571).
 *
 * The hazard is specific and silent: the JVM reader tolerates unknown keys and defaults its
 * reader-only fields, so a descriptor written by a NEWER writer parses cleanly and the daemon
 * launches against defaults for everything the new version added — no exception, just a session
 * built from a contract nobody agreed on. Post-split this module is a published contract an
 * extracted preview server links against (#3824), so an older reader meeting a newer descriptor is
 * an ordinary cross-repo pairing rather than a same-commit mistake.
 *
 * The descriptors below are deliberately skewed off the writer's version — that is the point of the
 * test, and `check-daemon-launch-schema.py` excludes test sources from its stamp scan for exactly
 * this case.
 */
class DescriptorSchemaVersionTest {

  private val json = Json { encodeDefaults = true }

  private val fileSystem = FakeFileSystem()

  /** Never reached by a refused descriptor; reaching it is how the accept case is proven. */
  private val spawnMarker = "spawn reached"

  private val factory = DaemonClientFactory { _, _ -> throw IllegalStateException(spawnMarker) }

  @Test
  fun `a newer descriptor is refused, naming the remedy the caller can act on`() {
    val e = openWithSchemaVersion(99)

    assertThat(e).hasMessageThat().contains("schemaVersion=99")
    assertThat(e).hasMessageThat().contains("speaks version 2")
    assertThat(e).hasMessageThat().contains("newer compose-preview plugin")
    assertThat(e).hasMessageThat().contains("Upgrade the consumer")
    // The gate fires before the spawn, so no daemon JVM is forked against an unreadable contract.
    assertThat(e).hasMessageThat().doesNotContain(spawnMarker)
  }

  @Test
  fun `an older descriptor is refused, and its remedy is to regenerate it`() {
    val e = openWithSchemaVersion(1)

    assertThat(e).hasMessageThat().contains("schemaVersion=1")
    assertThat(e).hasMessageThat().contains("older compose-preview plugin")
    assertThat(e).hasMessageThat().contains("composePreviewDaemonStart")
    assertThat(e).hasMessageThat().doesNotContain(spawnMarker)
  }

  @Test
  fun `a matching descriptor passes the gate and reaches the spawn`() {
    // The injected factory always throws, so `open` cannot return a session here. What the test
    // asserts is *which* failure it gets: the spawn's, not the gate's.
    val e = openWithSchemaVersion(2)

    assertThat(e).hasMessageThat().contains("Failed to spawn daemon subprocess")
    assertThat(e).hasMessageThat().contains(spawnMarker)
    assertThat(e).hasMessageThat().doesNotContain("schemaVersion")
  }

  private fun openWithSchemaVersion(schemaVersion: Int): RenderSessionException {
    val path = "/workspace/module/build/compose-previews/daemon-launch.json"
    fileSystem.createDirectories(path.toPath().parent!!)
    fileSystem.write(path.toPath()) { writeUtf8(descriptor(schemaVersion)) }

    return runCatching {
      SubprocessRenderSessions.open(
        config = RenderSessionConfig(descriptorPath = File(path)),
        factory = factory,
        fileSystem = fileSystem,
      )
    }
      .exceptionOrNull() as? RenderSessionException
      ?: error("expected a RenderSessionException from open(schemaVersion=$schemaVersion)")
  }

  private fun descriptor(schemaVersion: Int): String =
    json.encodeToString(
      DaemonLaunchDescriptor.serializer(),
      DaemonLaunchDescriptor(
        schemaVersion = schemaVersion,
        modulePath = ":module",
        variant = "desktop",
        enabled = true,
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = listOf("/lib/daemon.jar"),
        jvmArgs = listOf("-Xmx512m"),
        systemProperties = mapOf("composeai.daemon.userClassDirs" to "/workspace/module/classes"),
        workingDirectory = "/workspace/module",
        manifestPath = "/workspace/module/build/compose-previews/previews.json",
      ),
    )
}
