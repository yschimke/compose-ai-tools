package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The launch descriptor's containment fields — how the playground's per-session sandbox reaches a
 * daemon spawned from a descriptor on disk (`docs/design/PLAYGROUND.md` §6, issue #3016). Serve
 * writes the jail argv + hard TTL into the snippet's own `daemon-launch.json`, so the ordinary
 * descriptor→spawn path applies both without any intermediate layer knowing about sandboxes.
 */
class DaemonLaunchDescriptorJailTest {

  private val descriptorJson = Json { encodeDefaults = true }

  private val plain =
    DaemonLaunchDescriptor(
      schemaVersion = 2,
      modulePath = ":playground",
      variant = "",
      enabled = true,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = listOf("/lib/daemon.jar"),
      jvmArgs = listOf("-Xmx512m"),
      systemProperties = mapOf("composeai.daemon.userClassDirs" to "/work/classes"),
      workingDirectory = "/work",
      manifestPath = "/work/previews.json",
    )

  @Test
  fun `an ordinary descriptor carries no jail, so nothing about the old launch changes`() {
    assertThat(plain.jailCommand).isEmpty()
    assertThat(plain.hardTtlSeconds).isNull()
  }

  @Test
  fun `jailed carries the argv and the deadline`() {
    val jailed = plain.jailed(listOf("bwrap", "--unshare-net", "--"), hardTtlSeconds = 900)

    assertThat(jailed.jailCommand).containsExactly("bwrap", "--unshare-net", "--").inOrder()
    assertThat(jailed.hardTtlSeconds).isEqualTo(900)
    // Nothing else moves — the jail is additive to the launch the daemon already had.
    assertThat(jailed.classpath).isEqualTo(plain.classpath)
    assertThat(jailed.systemProperties).isEqualTo(plain.systemProperties)
  }

  @Test
  fun `the fields survive the round trip through the descriptor file`() {
    val jailed = plain.jailed(listOf("unshare", "--net"), hardTtlSeconds = 300)

    val parsed =
      DaemonLaunchDescriptor.parse(
        descriptorJson.encodeToString(DaemonLaunchDescriptor.serializer(), jailed)
      )

    assertThat(parsed.jailCommand).containsExactly("unshare", "--net").inOrder()
    assertThat(parsed.hardTtlSeconds).isEqualTo(300)
  }

  @Test
  fun `a descriptor written before these fields existed still parses`() {
    val legacy =
      """
      {"schemaVersion":2,"modulePath":":app","variant":"debug","enabled":true,
       "mainClass":"ee.schimke.composeai.daemon.DaemonMain","classpath":["/a.jar"],
       "jvmArgs":[],"systemProperties":{},"workingDirectory":"/w","manifestPath":"/w/p.json"}
      """
        .trimIndent()

    val parsed = DaemonLaunchDescriptor.parse(legacy)

    assertThat(parsed.jailCommand).isEmpty()
    assertThat(parsed.hardTtlSeconds).isNull()
  }
}
