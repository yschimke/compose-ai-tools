package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class SubprocessDaemonSpawnTest {
  @Test
  fun `bounded shutdown force kills after one total deadline`() {
    val process = NonTerminatingProcess()
    val spawn = SubprocessDaemonSpawn(process)
    spawn.client(onNotification = { _, _ -> }, onClose = {})

    val startedNanos = System.nanoTime()
    spawn.shutdown(50.milliseconds)
    val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

    assertThat(process.forciblyDestroyed).isTrue()
    assertThat(elapsedMillis).isLessThan(1_000L)
  }

  private class NonTerminatingProcess : Process() {
    private val stdin = ByteArrayOutputStream()
    private val stdout = ByteArrayInputStream(ByteArray(0))
    private val stderr = ByteArrayInputStream(ByteArray(0))
    private var alive = true

    var forciblyDestroyed = false
      private set

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = throw InterruptedException("process does not exit")

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
      Thread.sleep(unit.toMillis(timeout))
      return false
    }

    override fun exitValue(): Int = throw IllegalThreadStateException("process is still running")

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
      forciblyDestroyed = true
      alive = false
      return this
    }

    override fun isAlive(): Boolean = alive
  }
}
