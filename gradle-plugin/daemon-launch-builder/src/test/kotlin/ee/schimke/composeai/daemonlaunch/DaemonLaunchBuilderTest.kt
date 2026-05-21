package ee.schimke.composeai.daemonlaunch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DaemonLaunchBuilderTest {

  @Test
  fun `build stamps the current schema version and passes inputs through`() {
    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = ":app",
        variant = "debug",
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = listOf("/a.jar", "/b.jar"),
        jvmArgs = listOf("-Xmx1024m"),
        systemProperties = linkedMapOf("composeai.daemon.protocolVersion" to "1"),
        workingDirectory = "/abs/app",
        manifestPath = "/abs/app/previews.json",
      )

    assertThat(descriptor.schemaVersion).isEqualTo(DAEMON_DESCRIPTOR_SCHEMA_VERSION)
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
  }

  @Test
  fun `encode round-trips through decode`() {
    val original =
      DaemonLaunchBuilder.build(
        modulePath = "//app",
        variant = "desktop",
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = listOf("/r.jar"),
        jvmArgs = emptyList(),
        systemProperties = emptyMap(),
        workingDirectory = "/x",
        manifestPath = "/x/p.json",
        enabled = false,
        javaLauncher = "/opt/jdk17/bin/java",
      )

    val json = DaemonLaunchBuilder.encode(original)
    val roundTripped = DaemonLaunchBuilder.decode(json)
    assertThat(roundTripped).isEqualTo(original)
  }

  @Test
  fun `encoded json includes explicit null for javaLauncher when unset`() {
    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = ":app",
        variant = "debug",
        mainClass = "Main",
        classpath = emptyList(),
        jvmArgs = emptyList(),
        systemProperties = emptyMap(),
        workingDirectory = "/x",
        manifestPath = "/x/p.json",
      )
    // explicitNulls = true on the canonical encoder; readers can distinguish "missing" from
    // "set to null" without inspecting field presence.
    assertThat(DaemonLaunchBuilder.encode(descriptor)).contains("\"javaLauncher\": null")
  }
}
