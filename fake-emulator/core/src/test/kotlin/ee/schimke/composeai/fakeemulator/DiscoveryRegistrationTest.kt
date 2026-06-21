package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryRegistrationTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `writes a pid ini Studio can read and deletes it`() {
    val dir = tmp.newFolder("avd-running").toOkioPath()
    val discovery = DiscoveryRegistration(overrideDir = dir)
    val file =
      discovery.write(
        DiscoveryRegistration.Registration(
          pid = 4242,
          consolePort = 5554,
          adbPort = 5555,
          avdName = "Compose_Preview",
          avdDir = "/tmp/avd",
          grpcPort = 8554,
          grpcToken = "secret-token",
        )
      )

    assertThat(file.name).isEqualTo("pid_4242.ini")
    val text = FileSystem.SYSTEM.read(file) { readUtf8() }
    assertThat(text).contains("port.serial=5554")
    assertThat(text).contains("port.adb=5555")
    assertThat(text).contains("grpc.port=8554")
    assertThat(text).contains("grpc.token=secret-token")

    discovery.delete(file)
    assertThat(FileSystem.SYSTEM.exists(file)).isFalse()
  }
}
