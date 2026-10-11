@file:Suppress("DEPRECATION")

package ee.schimke.composeai.daemonlaunch

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The deprecated `DaemonClasspathDescriptor` API forwards to the contracts'
 * [DaemonLaunchDescriptor]: for the same inputs it produces the same descriptor and the same JSON
 * as the new API. Kept until the facade is removed at a major release (`docs/VERSIONING.md` § 5).
 */
class DeprecatedDaemonLaunchFacadeTest {

  private val legacyBta =
    BtaCompileConfig(
      implClasspath = listOf("/bta/impl.jar"),
      compileClasspath = listOf("/cp/stdlib.jar"),
      compilerPlugins = listOf("/plugins/compose.jar"),
      outputDir = "/out",
      moduleName = "app_debug",
      icWorkingDir = "/ic",
      ineligibilityReason = "KSP",
    )

  private fun legacyBuild(): DaemonClasspathDescriptor =
    DaemonLaunchBuilder.build(
      modulePath = ":app",
      variant = "debug",
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = listOf("/a.jar", "/b.jar"),
      jvmArgs = listOf("-Xmx1024m", "--add-opens=java.base/java.lang=ALL-UNNAMED"),
      systemProperties = linkedMapOf("b" to "2", "a" to "1"),
      workingDirectory = "/abs/app",
      manifestPath = "/abs/app/previews.json",
      enabled = false,
      javaLauncher = "/opt/jdk/bin/java",
    )

  private fun newBuild(): DaemonLaunchDescriptor =
    DaemonLaunchBuilder.newDescriptorBuilder(
        modulePath = ":app",
        variant = "debug",
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = listOf("/a.jar", "/b.jar"),
        jvmArgs = listOf("-Xmx1024m", "--add-opens=java.base/java.lang=ALL-UNNAMED"),
        systemProperties = linkedMapOf("b" to "2", "a" to "1"),
        workingDirectory = "/abs/app",
        manifestPath = "/abs/app/previews.json",
        enabled = false,
      )
      .also { it.javaLauncher = "/opt/jdk/bin/java" }
      .build()

  @Test
  fun `the legacy schema version constant forwards the contracts one`() {
    assertThat(DAEMON_DESCRIPTOR_SCHEMA_VERSION).isEqualTo(DaemonLaunchDescriptor.SCHEMA_VERSION)
    assertThat(legacyBuild().schemaVersion).isEqualTo(DaemonLaunchDescriptor.SCHEMA_VERSION)
  }

  @Test
  fun `legacy build converts to exactly what the new builder produces`() {
    assertThat(legacyBuild().toLaunchDescriptor()).isEqualTo(newBuild())
  }

  @Test
  fun `legacy encode writes the same json as the new encode`() {
    assertThat(DaemonLaunchBuilder.encode(legacyBuild()))
      .isEqualTo(DaemonLaunchBuilder.encode(newBuild()))

    val withBta = legacyBuild().copy(btaCompile = legacyBta)
    val expected = newBuild().newBuilder().also { it.btaCompile = legacyBta.toShared() }.build()
    assertThat(DaemonLaunchBuilder.encode(withBta)).isEqualTo(DaemonLaunchBuilder.encode(expected))
  }

  @Test
  fun `the contracts encoding differs from the legacy shape only by the two defaulted keys`() {
    val legacy = legacyBuild().copy(btaCompile = legacyBta)
    // What the module wrote before it moved to the contracts type: the legacy class serialised
    // directly through the canonical encoder.
    val before =
      Json.parseToJsonElement(
          DaemonLaunchBuilder.json.encodeToString(DaemonClasspathDescriptor.serializer(), legacy)
        )
        .jsonObject
    val after = Json.parseToJsonElement(DaemonLaunchBuilder.encode(legacy)).jsonObject

    assertThat(after.keys - before.keys).containsExactly("jailCommand", "hardTtlSeconds")
    assertThat(before.keys - after.keys).isEmpty()
    assertThat(after["jailCommand"]).isEqualTo(JsonArray(emptyList()))
    assertThat(after["hardTtlSeconds"]).isEqualTo(JsonNull)
    assertThat(JsonObject(after - "jailCommand" - "hardTtlSeconds")).isEqualTo(before)
  }

  @Test
  fun `legacy decode reads what the new encode writes, and round-trips`() {
    val legacy = legacyBuild().copy(btaCompile = legacyBta)
    val json = DaemonLaunchBuilder.encode(legacy.toLaunchDescriptor())
    assertThat(DaemonLaunchBuilder.decode(json)).isEqualTo(legacy)
    assertThat(DaemonLaunchBuilder.decodeLaunchDescriptor(json))
      .isEqualTo(legacy.toLaunchDescriptor())
  }

  @Test
  fun `legacy decode still reads a descriptor written before the contracts keys existed`() {
    val legacy = legacyBuild().copy(btaCompile = legacyBta)
    val old =
      DaemonLaunchBuilder.json.encodeToString(DaemonClasspathDescriptor.serializer(), legacy)
    assertThat(DaemonLaunchBuilder.decode(old)).isEqualTo(legacy)
  }

  @Test
  fun `bta config converts both ways without loss`() {
    assertThat(legacyBta.toShared().toLegacy()).isEqualTo(legacyBta)
  }
}
