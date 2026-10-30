package ee.schimke.composeai.render.session.subprocess

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.BootPhase
import ee.schimke.composeai.daemon.protocol.BootProgressParams
import org.junit.Test

class BootProgressLinesTest {

  private fun progress(phase: String, configure: BootProgressParams.Builder.() -> Unit = {}) =
    BootProgressParams.Builder(phase).apply(configure).build()

  @Test
  fun `names the slot, the pool and the time`() {
    val line =
      BootProgressLines.describe(
        "//app:previews",
        progress(BootPhase.SANDBOX_BOOTING) {
          slot = 0
          totalSlots = 3
          elapsedMs = 2_340
        },
      )
    assertThat(line).isEqualTo("//app:previews: booting sandbox 0 (0/3 ready) [+2.3s]")
  }

  @Test
  fun `a host that does not boot in slots reports no counts`() {
    val line = BootProgressLines.describe(":app", progress(BootPhase.READY) { elapsedMs = 900 })
    assertThat(line).isEqualTo(":app: daemon ready [+0.9s]")
  }

  @Test
  fun `a failure carries its reason and an unknown phase still reads`() {
    assertThat(
        BootProgressLines.describe(
          ":app",
          progress(BootPhase.FAILED) { message = "no android-all" },
        )
      )
      .contains("daemon failed to boot: no android-all")
    assertThat(BootProgressLines.describe(":app", progress("downloadingAndroidAll")))
      .contains("daemon boot: downloadingAndroidAll")
  }
}
