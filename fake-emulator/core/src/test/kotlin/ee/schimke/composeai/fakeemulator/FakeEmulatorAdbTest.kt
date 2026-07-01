package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Drives the fake emulator's ADB transport with **dadb**, which speaks the device protocol directly
 * (no real `adb` binary or device). Proves the connect handshake, shell-v2 framing, flow control,
 * and the `am start … PreviewActivity` preview-launch intent all work end-to-end over a socket.
 */
class FakeEmulatorAdbTest {
  private val display = DisplaySize(1080, 2340, 420)
  private val frames = MutableFrameSource(display)
  @Volatile private var launched: PreviewLaunchRequest? = null

  private lateinit var emulator: FakeEmulator
  private lateinit var dadb: Dadb

  @Before
  fun setUp() {
    emulator =
      FakeEmulator(
          // Console off + no discovery keeps the test to a single ephemeral adb port.
          FakeEmulatorConfig(display = display, enableConsole = false, writeDiscovery = false),
          frameSource = frames,
          previewLauncher = { request ->
            launched = request
            PreviewLaunchResult.Launched
          },
        )
        .start()
    dadb = Dadb.create("localhost", emulator.adbPort)
  }

  @After
  fun tearDown() {
    runCatching { dadb.close() }
    runCatching { emulator.close() }
  }

  @Test
  fun `getprop over adb returns the emulator marker`() {
    val response = dadb.shell("getprop ro.kernel.qemu")
    assertThat(response.exitCode).isEqualTo(0)
    assertThat(response.output.trim()).isEqualTo("1")
  }

  @Test
  fun `am start over adb launches the named preview`() {
    val response =
      dadb.shell(
        "am start -n com.example.app/androidx.compose.ui.tooling.PreviewActivity " +
          "--es composable com.example.PreviewsKt.MyPreview"
      )
    assertThat(response.exitCode).isEqualTo(0)
    assertThat(response.output).contains("Starting:")
    assertThat(launched).isNotNull()
    assertThat(launched!!.composableFqn).isEqualTo("com.example.PreviewsKt.MyPreview")
  }

  @Test
  fun `adb install over the transport is accepted and the package is captured`() {
    val apk = File.createTempFile("preview-app", ".apk")
    apk.deleteOnExit()
    apk.writeBytes(ApkFixtures.apk("com.example.installed"))

    // dadb.install drives the modern streaming path (abb_exec / exec:cmd package install -S) end
    // to end over the socket; it throws unless it reads back "Success".
    dadb.install(apk)

    val installed = emulator.apkStore.findByPackage("com.example.installed")
    assertThat(installed).isNotNull()
    assertThat(installed!!.info.declaresComposePreviews).isTrue()
  }

  @Test
  fun `wm size over adb reports the display geometry`() {
    val response = dadb.shell("wm size")
    assertThat(response.output.trim()).isEqualTo("Physical size: 1080x2340")
  }
}
