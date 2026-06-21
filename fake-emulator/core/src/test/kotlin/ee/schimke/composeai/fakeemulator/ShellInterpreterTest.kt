package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import org.junit.Test

class ShellInterpreterTest {
  private val display = DisplaySize(1080, 2340, 420)
  private val frames = MutableFrameSource(display)
  private var launched: PreviewLaunchRequest? = null
  private val interpreter =
    ShellInterpreter(
      properties = DeviceProperties.defaults("emulator-5554", display),
      frameSource = frames,
      previewLauncher = { request ->
        launched = request
        PreviewLaunchResult.Launched
      },
    )

  @Test
  fun `getprop returns single value`() {
    val result = interpreter.execute("getprop ro.kernel.qemu")
    assertThat(result.exitCode).isEqualTo(0)
    assertThat(stdout(result).trim()).isEqualTo("1")
  }

  @Test
  fun `getprop with no args lists all props`() {
    val out = stdout(interpreter.execute("getprop"))
    assertThat(out).contains("[ro.product.model]: [Compose Preview Emulator]")
  }

  @Test
  fun `am start PreviewActivity routes to the launcher`() {
    val result =
      interpreter.execute(
        "am start -n com.example.app/androidx.compose.ui.tooling.PreviewActivity " +
          "--es composable com.example.PreviewsKt.MyPreview"
      )
    assertThat(stdout(result)).contains("Starting:")
    assertThat(launched).isNotNull()
    assertThat(launched!!.composableFqn).isEqualTo("com.example.PreviewsKt.MyPreview")
    assertThat(launched!!.component)
      .isEqualTo("com.example.app/androidx.compose.ui.tooling.PreviewActivity")
  }

  @Test
  fun `am start carries the parameter provider extra`() {
    interpreter.execute(
      "am start -n app/androidx.compose.ui.tooling.PreviewActivity " +
        "--es composable com.x.FooKt.Bar --es parameterProviderClassName com.x.Provider"
    )
    assertThat(launched!!.parameterProviderClassName).isEqualTo("com.x.Provider")
  }

  @Test
  fun `am start for a non-preview activity does not launch a preview`() {
    interpreter.execute("am start -n com.example.app/.MainActivity")
    assertThat(launched).isNull()
  }

  @Test
  fun `screencap returns PNG bytes`() {
    val png = interpreter.execute("screencap -p").stdout
    assertThat(png.size).isGreaterThan(8)
    // PNG signature: 89 50 4E 47 0D 0A 1A 0A
    assertThat(png[0].toInt() and 0xff).isEqualTo(0x89)
    assertThat(png[1].toInt() and 0xff).isEqualTo(0x50)
    assertThat(png[2].toInt() and 0xff).isEqualTo(0x4E)
    assertThat(png[3].toInt() and 0xff).isEqualTo(0x47)
  }

  @Test
  fun `wm size reports the display`() {
    assertThat(stdout(interpreter.execute("wm size")).trim()).isEqualTo("Physical size: 1080x2340")
  }

  @Test
  fun `unknown command succeeds quietly`() {
    val result = interpreter.execute("definitely-not-a-real-command --flags")
    assertThat(result.exitCode).isEqualTo(0)
    assertThat(result.stdout).isEmpty()
  }

  @Test
  fun `tokenize honours quotes`() {
    assertThat(ShellInterpreter.tokenize("am start --es k 'hello world'"))
      .containsExactly("am", "start", "--es", "k", "hello world")
      .inOrder()
  }

  private fun stdout(result: ShellInterpreter.Result): String =
    String(result.stdout, StandardCharsets.UTF_8)
}
