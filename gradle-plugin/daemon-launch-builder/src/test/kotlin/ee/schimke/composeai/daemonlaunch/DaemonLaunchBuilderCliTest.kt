package ee.schimke.composeai.daemonlaunch

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class DaemonLaunchBuilderCliTest {

  @Test
  fun `parse fills every required field`() {
    val parsed =
      DaemonLaunchBuilderCli.parse(
        arrayOf(
          "--module-path",
          ":app",
          "--variant",
          "debug",
          "--main-class",
          "ee.schimke.composeai.daemon.DaemonMain",
          "--classpath",
          "/a.jar${File.pathSeparator}/b.jar",
          "--jvm-arg",
          "-Xmx1024m",
          "--system-property",
          "composeai.daemon.protocolVersion=1",
          "--system-property",
          "composeai.daemon.modulePath=:app",
          "--working-directory",
          "/abs/app",
          "--manifest-path",
          "/abs/app/previews.json",
          "--out",
          "/abs/app/daemon-launch.json",
        )
      )

    assertThat(parsed.modulePath).isEqualTo(":app")
    assertThat(parsed.variant).isEqualTo("debug")
    assertThat(parsed.mainClass).isEqualTo("ee.schimke.composeai.daemon.DaemonMain")
    assertThat(parsed.classpath).containsExactly("/a.jar", "/b.jar").inOrder()
    assertThat(parsed.jvmArgs).containsExactly("-Xmx1024m")
    assertThat(parsed.systemProperties)
      .containsExactly(
        "composeai.daemon.protocolVersion",
        "1",
        "composeai.daemon.modulePath",
        ":app",
      )
    assertThat(parsed.enabled).isTrue()
    assertThat(parsed.javaLauncher).isNull()
    assertThat(parsed.outFile.path).isEqualTo("/abs/app/daemon-launch.json")
  }

  @Test
  fun `repeated --classpath concatenates`() {
    val parsed =
      DaemonLaunchBuilderCli.parse(
        baseArgs() + arrayOf("--classpath", "/a", "--classpath", "/b${File.pathSeparator}/c")
      )
    assertThat(parsed.classpath).containsExactly("/a", "/b", "/c").inOrder()
  }

  @Test
  fun `--enabled false flips the descriptor enabled flag`() {
    val parsed = DaemonLaunchBuilderCli.parse(baseArgs() + arrayOf("--enabled", "false"))
    assertThat(parsed.enabled).isFalse()
  }

  @Test
  fun `--enabled rejects non-boolean values`() {
    val error =
      assertThrows(DaemonLaunchBuilderCli.ArgError::class.java) {
        DaemonLaunchBuilderCli.parse(baseArgs() + arrayOf("--enabled", "yes"))
      }
    assertThat(error.message).contains("must be true|false")
  }

  @Test
  fun `--system-property without = errors`() {
    val error =
      assertThrows(DaemonLaunchBuilderCli.ArgError::class.java) {
        DaemonLaunchBuilderCli.parse(baseArgs() + arrayOf("--system-property", "no_equals_here"))
      }
    assertThat(error.message).contains("key=value")
  }

  @Test
  fun `missing --module-path errors`() {
    val error =
      assertThrows(DaemonLaunchBuilderCli.ArgError::class.java) {
        DaemonLaunchBuilderCli.parse(
          arrayOf(
            "--variant",
            "v",
            "--main-class",
            "M",
            "--working-directory",
            "/w",
            "--manifest-path",
            "/m",
            "--out",
            "/o",
          )
        )
      }
    assertThat(error.message).contains("--module-path is required")
  }

  /** Minimal arg set that satisfies every required field but produces an empty inputs surface. */
  private fun baseArgs(): Array<String> =
    arrayOf(
      "--module-path",
      ":app",
      "--variant",
      "debug",
      "--main-class",
      "Main",
      "--working-directory",
      "/w",
      "--manifest-path",
      "/m",
      "--out",
      "/o",
    )
}
