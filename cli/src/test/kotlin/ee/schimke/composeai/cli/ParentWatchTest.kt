package ee.schimke.composeai.cli

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * `build-host --stdio` must notice its server has gone even when stdin stays open, or it runs on
 * under PID 1 (yschimke/compose-ai-tools#6364).
 */
class ParentWatchTest {

  private val self = ProcessHandle.current()

  @Test
  fun `a live parent that is still ours is not gone`() {
    val parent = self.parent().orElse(null)
    assumeTrue(parent != null)
    assertFalse(ParentWatch.parentGone(parent, self.parent().orElse(null)))
  }

  @Test
  fun `no parent at the start is never reported gone`() {
    assertFalse(ParentWatch.parentGone(null, null))
  }

  @Test
  fun `being reparented counts as gone even while the old parent lives`() {
    val parent = self.parent().orElse(null)
    assumeTrue(parent != null)
    // Here "current" is ourselves: any handle other than the original parent.
    assertTrue(ParentWatch.parentGone(parent, self))
    assertTrue(ParentWatch.parentGone(parent, null))
  }

  @Test
  fun `a parent that exited is gone`() {
    assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
    val process = ProcessBuilder("sh", "-c", "sleep 300").start()
    val handle = process.toHandle()
    assertFalse(ParentWatch.parentGone(handle, handle))
    process.destroyForcibly()
    assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    assertTrue(ParentWatch.parentGone(handle, handle))
  }
}
