package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

/**
 * The `pm` / `cmd package install` surface: accept the APK over each transport, reply `Success`.
 */
class ShellInstallTest {
  private val display = DisplaySize(1080, 2340, 420)

  private fun interpreter() =
    ShellInterpreter(
      DeviceProperties.defaults("emulator-5554", display),
      MutableFrameSource(display),
      PreviewLauncher.NOOP,
    )

  private fun stdout(result: ShellInterpreter.Result) = String(result.stdout, Charsets.UTF_8)

  @Test
  fun `cmd package install -S streams the piped APK and records it`() {
    val shell = interpreter()
    val apk = ApkFixtures.apk("com.example.app")
    val result = shell.execute("cmd package install -S ${apk.size}", ByteArrayInputStream(apk))
    assertThat(stdout(result)).contains("Success")
    assertThat(shell.apkStore.findByPackage("com.example.app")).isNotNull()
    assertThat(shell.apkStore.findByPackage("com.example.app")!!.info.declaresComposePreviews)
      .isTrue()
  }

  @Test
  fun `pm install -S is equivalent to cmd package install -S`() {
    val shell = interpreter()
    val apk = ApkFixtures.apk("com.example.pm")
    val result = shell.execute("pm install -r -S ${apk.size}", ByteArrayInputStream(apk))
    assertThat(stdout(result)).contains("Success")
    assertThat(shell.apkStore.findByPackage("com.example.pm")?.transport)
      .isEqualTo(ApkStore.Transport.STREAMING)
  }

  @Test
  fun `abb_exec argv drives the same install path`() {
    val shell = interpreter()
    val apk = ApkFixtures.apk("com.example.abb")
    // abb_exec:package\0install\0-S\0<size> arrives as this argv (with a leading "cmd").
    val result =
      shell.executeArgv(
        listOf("cmd", "package", "install", "-S", apk.size.toString()),
        ByteArrayInputStream(apk),
      )
    assertThat(stdout(result)).contains("Success")
    assertThat(shell.apkStore.findByPackage("com.example.abb")).isNotNull()
  }

  @Test
  fun `legacy pm install of a pushed path installs the pushed bytes`() {
    val shell = interpreter()
    val apk = ApkFixtures.apk("com.example.legacy")
    shell.apkStore.putPushedFile("/data/local/tmp/base.apk", apk)
    val result = shell.execute("pm install -r /data/local/tmp/base.apk")
    assertThat(stdout(result)).contains("Success")
    assertThat(shell.apkStore.findByPackage("com.example.legacy")?.transport)
      .isEqualTo(ApkStore.Transport.LEGACY_PUSH)
  }

  @Test
  fun `install-create install-write install-commit assembles the session`() {
    val shell = interpreter()
    val apk = ApkFixtures.apk("com.example.session")

    val created = stdout(shell.execute("cmd package install-create -r"))
    assertThat(created).contains("Success")
    val session = Regex("\\[(\\w+)]").find(created)!!.groupValues[1]

    val written =
      shell.execute(
        "cmd package install-write -S ${apk.size} $session base.apk -",
        ByteArrayInputStream(apk),
      )
    assertThat(stdout(written)).contains("Success")

    val committed = shell.execute("cmd package install-commit $session")
    assertThat(stdout(committed)).contains("Success")
    assertThat(shell.apkStore.findByPackage("com.example.session")?.transport)
      .isEqualTo(ApkStore.Transport.SESSION)
  }

  @Test
  fun `pm list packages reports installed package names`() {
    val shell = interpreter()
    val apk = ApkFixtures.apk("com.example.listed")
    shell.execute("cmd package install -S ${apk.size}", ByteArrayInputStream(apk))
    assertThat(stdout(shell.execute("pm list packages"))).contains("package:com.example.listed")
  }
}
