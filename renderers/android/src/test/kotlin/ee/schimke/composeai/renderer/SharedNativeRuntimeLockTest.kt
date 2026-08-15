package ee.schimke.composeai.renderer

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concurrency contract of the cache lock. Deliberately a plain JVM test rather than a Robolectric
 * one: the bug it covers is that a `FileLock` is held per *JVM*, so two Robolectric sandboxes
 * initializing the cache at once used to get `OverlappingFileLockException` — an unchecked
 * exception `ensureLoaded`'s `IOException | ReflectiveOperationException` catch does not cover, so
 * it escaped raw instead of as the intended `AssertionError`.
 */
class SharedNativeRuntimeLockTest {

  @Test
  fun `concurrent callers serialize instead of overlapping the file lock`() {
    val lockPath = Files.createTempDirectory("cache-lock").resolve("runtime.lock")
    val threads = 8
    val start = CyclicBarrier(threads)
    val done = CountDownLatch(threads)
    val concurrent = AtomicInteger()
    val peak = AtomicInteger()
    val completed = AtomicInteger()
    val failure = AtomicReference<Throwable>()

    repeat(threads) {
      Thread {
        try {
          start.await()
          SharedNativeRuntimeLoader.withExclusiveCacheLock(lockPath) {
            peak.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            // Long enough that an overlapping acquisition would land inside this window.
            Thread.sleep(25)
            concurrent.decrementAndGet()
            completed.incrementAndGet()
          }
        } catch (t: Throwable) {
          failure.compareAndSet(null, t)
        } finally {
          done.countDown()
        }
      }
        .apply { isDaemon = true }
        .start()
    }

    assertTrue("threads did not finish", done.await(60, TimeUnit.SECONDS))
    assertNull("a caller failed: ${failure.get()}", failure.get())
    assertEquals(threads, completed.get())
    assertEquals("callers overlapped inside the lock", 1, peak.get())
  }

  @Test
  fun `the lock is released when the body throws`() {
    val lockPath = Files.createTempDirectory("cache-lock").resolve("runtime.lock")

    runCatching {
      SharedNativeRuntimeLoader.withExclusiveCacheLock<Unit>(lockPath) {
        throw java.io.IOException("boom")
      }
    }

    // A leaked lock would make this second acquisition throw rather than return.
    val second = SharedNativeRuntimeLoader.withExclusiveCacheLock(lockPath) { "ok" }
    assertEquals("ok", second)
  }
}
