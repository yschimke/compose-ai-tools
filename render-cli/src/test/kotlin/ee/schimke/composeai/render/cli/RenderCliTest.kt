package ee.schimke.composeai.render.cli

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.RenderTier
import org.junit.Assert.assertThrows
import org.junit.Test

class RenderCliTest {

  @Test
  fun `parse fills every required field`() {
    val parsed =
      RenderCli.parse(
        arrayOf(
          "--descriptor",
          "/abs/build/daemon-launch.json",
          "--workspace-root",
          "/abs/repo",
          "--previews",
          "Foo.Greeting,Bar.Welcome",
        )
      )

    assertThat(parsed.descriptor.path).isEqualTo("/abs/build/daemon-launch.json")
    assertThat(parsed.workspaceRoot.path).isEqualTo("/abs/repo")
    assertThat(parsed.workspaceName).isEqualTo("repo")
    assertThat(parsed.previewIds).containsExactly("Foo.Greeting", "Bar.Welcome").inOrder()
    assertThat(parsed.tier).isEqualTo(RenderTier.FULL)
    assertThat(parsed.reason).isNull()
    assertThat(parsed.timeoutSeconds).isEqualTo(60L)
  }

  @Test
  fun `repeated --previews concatenates`() {
    val parsed =
      RenderCli.parse(
        arrayOf(
          "--descriptor",
          "/d",
          "--workspace-root",
          "/r",
          "--previews",
          "a,b",
          "--previews",
          "c",
        )
      )
    assertThat(parsed.previewIds).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun `--workspace-name overrides default`() {
    val parsed =
      RenderCli.parse(
        arrayOf(
          "--descriptor",
          "/d",
          "--workspace-root",
          "/some/repo",
          "--workspace-name",
          "my-workspace",
          "--previews",
          "a",
        )
      )
    assertThat(parsed.workspaceName).isEqualTo("my-workspace")
  }

  @Test
  fun `--tier accepts FULL and FAST and rejects nonsense`() {
    val full = RenderCli.parse(base() + arrayOf("--tier", "FULL"))
    assertThat(full.tier).isEqualTo(RenderTier.FULL)

    val fast = RenderCli.parse(base() + arrayOf("--tier", "FAST"))
    assertThat(fast.tier).isEqualTo(RenderTier.FAST)

    val error =
      assertThrows(RenderCli.ArgError::class.java) {
        RenderCli.parse(base() + arrayOf("--tier", "EXTRA_CRISPY"))
      }
    assertThat(error.message).contains("--tier must be one of")
  }

  @Test
  fun `--timeout-seconds requires a positive integer`() {
    val parsed = RenderCli.parse(base() + arrayOf("--timeout-seconds", "120"))
    assertThat(parsed.timeoutSeconds).isEqualTo(120L)

    val negative =
      assertThrows(RenderCli.ArgError::class.java) {
        RenderCli.parse(base() + arrayOf("--timeout-seconds", "-1"))
      }
    assertThat(negative.message).contains("positive integer")

    val notANumber =
      assertThrows(RenderCli.ArgError::class.java) {
        RenderCli.parse(base() + arrayOf("--timeout-seconds", "soon"))
      }
    assertThat(notANumber.message).contains("positive integer")
  }

  @Test
  fun `missing --descriptor errors`() {
    val error =
      assertThrows(RenderCli.ArgError::class.java) {
        RenderCli.parse(arrayOf("--workspace-root", "/r", "--previews", "a"))
      }
    assertThat(error.message).contains("--descriptor is required")
  }

  @Test
  fun `empty --previews errors`() {
    val error =
      assertThrows(RenderCli.ArgError::class.java) {
        RenderCli.parse(arrayOf("--descriptor", "/d", "--workspace-root", "/r", "--previews", ""))
      }
    assertThat(error.message).contains("--previews requires at least one id")
  }

  private fun base(): Array<String> =
    arrayOf("--descriptor", "/d", "--workspace-root", "/r", "--previews", "a")
}
