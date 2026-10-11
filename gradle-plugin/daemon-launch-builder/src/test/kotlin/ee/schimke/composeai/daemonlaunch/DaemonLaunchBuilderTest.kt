package ee.schimke.composeai.daemonlaunch

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.BtaCompileConfig
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DaemonLaunchBuilderTest {

  @Test
  fun `newDescriptorBuilder stamps the current schema version and passes inputs through`() {
    val descriptor =
      DaemonLaunchBuilder.newDescriptorBuilder(
          modulePath = ":app",
          variant = "debug",
          mainClass = "ee.schimke.composeai.daemon.DaemonMain",
          classpath = listOf("/a.jar", "/b.jar"),
          jvmArgs = listOf("-Xmx1024m"),
          systemProperties = linkedMapOf("composeai.daemon.protocolVersion" to "1"),
          workingDirectory = "/abs/app",
          manifestPath = "/abs/app/previews.json",
        )
        .build()

    assertThat(descriptor.schemaVersion).isEqualTo(DaemonLaunchDescriptor.SCHEMA_VERSION)
    assertThat(descriptor.modulePath).isEqualTo(":app")
    assertThat(descriptor.variant).isEqualTo("debug")
    assertThat(descriptor.enabled).isTrue()
    assertThat(descriptor.mainClass).isEqualTo("ee.schimke.composeai.daemon.DaemonMain")
    assertThat(descriptor.javaLauncher).isNull()
    assertThat(descriptor.classpath).containsExactly("/a.jar", "/b.jar").inOrder()
    assertThat(descriptor.jvmArgs).containsExactly("-Xmx1024m")
    assertThat(descriptor.systemProperties).containsEntry("composeai.daemon.protocolVersion", "1")
    assertThat(descriptor.workingDirectory).isEqualTo("/abs/app")
    assertThat(descriptor.manifestPath).isEqualTo("/abs/app/previews.json")
    assertThat(descriptor.jailCommand).isEmpty()
    assertThat(descriptor.hardTtlSeconds).isNull()
    assertThat(descriptor.btaCompile).isNull()
  }

  @Test
  fun `encode round-trips through decodeLaunchDescriptor`() {
    val original =
      DaemonLaunchBuilder.newDescriptorBuilder(
          modulePath = "//app",
          variant = "desktop",
          mainClass = "ee.schimke.composeai.daemon.DaemonMain",
          classpath = listOf("/r.jar"),
          jvmArgs = emptyList(),
          systemProperties = emptyMap(),
          workingDirectory = "/x",
          manifestPath = "/x/p.json",
          enabled = false,
        )
        .also {
          it.javaLauncher = "/opt/jdk17/bin/java"
          it.btaCompile = btaCompile()
        }
        .build()

    val json = DaemonLaunchBuilder.encode(original)
    assertThat(DaemonLaunchBuilder.decodeLaunchDescriptor(json)).isEqualTo(original)
    // The lenient reader every consumer uses reads the canonical encoding identically.
    assertThat(DaemonLaunchDescriptor.parse(json)).isEqualTo(original)
  }

  @Test
  fun `encoded json includes explicit null for javaLauncher when unset`() {
    val descriptor =
      DaemonLaunchBuilder.newDescriptorBuilder(
          modulePath = ":app",
          variant = "debug",
          mainClass = "Main",
          classpath = emptyList(),
          jvmArgs = emptyList(),
          systemProperties = emptyMap(),
          workingDirectory = "/x",
          manifestPath = "/x/p.json",
        )
        .build()
    // explicitNulls = true on the canonical encoder; readers can distinguish "missing" from
    // "set to null" without inspecting field presence.
    val encoded = DaemonLaunchBuilder.encode(descriptor)
    assertThat(encoded).contains("\"javaLauncher\": null")
    assertThat(encoded).contains("\"btaCompile\": null")
  }

  @Test
  fun `published schema metadata matches the descriptor schema version`() {
    val resource =
      checkNotNull(javaClass.getResource("/META-INF/compose-preview/daemon-launch-schema.json"))
    val metadata = Json.parseToJsonElement(resource.readText()).jsonObject

    assertThat(metadata["schema"]?.jsonPrimitive?.content)
      .isEqualTo("compose-preview-daemon-launch")
    assertThat(metadata["schemaVersion"]?.jsonPrimitive?.content?.toInt())
      .isEqualTo(DaemonLaunchDescriptor.SCHEMA_VERSION)
  }

  private fun btaCompile(): BtaCompileConfig =
    BtaCompileConfig.Builder(
        implClasspath = listOf("/bta/impl.jar"),
        compileClasspath = listOf("/cp/stdlib.jar"),
        compilerPlugins = listOf("/plugins/compose.jar"),
        outputDir = "/out",
        moduleName = "app_debug",
        icWorkingDir = "/ic",
      )
      .also { it.ineligibilityReason = "KSP" }
      .build()
}
