package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmulatorAdbServicesTest {
  private val display = DisplaySize(1080, 2340, 420)
  private val resolver =
    EmulatorAdbServices(
      ShellInterpreter(
        DeviceProperties.defaults("emulator-5554", display),
        MutableFrameSource(display),
        PreviewLauncher.NOOP,
      )
    )

  @Test
  fun `routes shell, exec, and sync destinations`() {
    assertThat(resolver.resolve("shell,v2:getprop")).isInstanceOf(ShellService::class.java)
    assertThat(resolver.resolve("shell:ls")).isInstanceOf(ShellService::class.java)
    // adb exec-out <cmd> opens exec:<cmd> — must be handled (raw, un-framed) for screencap piping.
    assertThat(resolver.resolve("exec:screencap -p")).isInstanceOf(ShellService::class.java)
    assertThat(resolver.resolve("sync:")).isInstanceOf(SyncService::class.java)
  }

  @Test
  fun `rejects unimplemented destinations`() {
    assertThat(resolver.resolve("framebuffer:")).isNull()
    assertThat(resolver.resolve("jdwp:1234")).isNull()
  }

  @Test
  fun `exec runs the command un-framed so screencap returns raw PNG bytes`() {
    // exec: output is raw (no shell-v2 packet framing), so the first bytes are the PNG signature.
    val io = CollectingStreamIo("exec:screencap -p")
    ShellService(
        ShellInterpreter(
          DeviceProperties.defaults("emulator-5554", display),
          MutableFrameSource(display),
          PreviewLauncher.NOOP,
        ),
        "exec:screencap -p",
      )
      .run(io)
    val out = io.written()
    assertThat(out.size).isGreaterThan(8)
    assertThat(out[0].toInt() and 0xff).isEqualTo(0x89) // PNG signature, not a shell-v2 id byte
    assertThat(out[1].toInt() and 0xff).isEqualTo(0x50)
  }

  /** Captures everything a service writes; [read] returns EOF immediately (no inbound data). */
  private class CollectingStreamIo(override val destination: String) : AdbStreamIo {
    private val buffer = java.io.ByteArrayOutputStream()

    override fun write(bytes: ByteArray) {
      buffer.write(bytes)
    }

    override fun read(): ByteArray? = null

    override fun close() {}

    fun written(): ByteArray = buffer.toByteArray()
  }
}
