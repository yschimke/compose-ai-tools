package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServeSessionRegistryTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-registry").toFile().also { it.deleteOnExit() }

  private fun newHost(streaming: Boolean = false): ServeRenderHost =
    ServeRenderHost(
      session = FakeRenderSession(newRenderRoot(), streaming = streaming),
      previews = listOf(ServePreview(previewId, "Red")),
      renderTimeoutSeconds = 30,
    )

  /** A factory that hands out a fresh host per id and records how many times it was called. */
  private class CountingFactory : ServeSessionFactory {
    var created = 0
      private set

    override fun create(sessionId: String): ServeRenderHost? {
      if (sessionId == "missing") return null
      created++
      return ServeRenderHost(
        session = FakeRenderSession(java.nio.file.Files.createTempDirectory("h").toFile()),
        previews = listOf(ServePreview("com.example.Red", "Red")),
        renderTimeoutSeconds = 30,
      )
    }
  }

  @Test
  fun `acquire forks once per id and caches`() {
    val factory = CountingFactory()
    ServeSessionRegistry(factory, reaperIntervalMillis = 0).use { reg ->
      val first = assertNotNull(reg.acquire("a"))
      val second = assertNotNull(reg.acquire("a"))
      assertSame(first, second, "the same id returns the cached host")
      assertEquals(1, factory.created, "a cached id is not re-forked")
      assertEquals(1, reg.activeCount())

      reg.acquire("b")
      assertEquals(2, factory.created, "a new id forks a new host")
      assertEquals(2, reg.activeCount())
    }
  }

  @Test
  fun `acquire returns null when the factory cannot create the session`() {
    ServeSessionRegistry(CountingFactory(), reaperIntervalMillis = 0).use { reg ->
      assertNull(reg.acquire("missing"))
      assertEquals(0, reg.activeCount())
    }
  }

  @Test
  fun `registered sessions are pinned and never evicted`() {
    val clock = AtomicLong(0)
    ServeSessionRegistry(idleTimeoutMillis = 100, reaperIntervalMillis = 0, clock = clock::get)
      .use { reg ->
        reg.register("primary", newHost())
        clock.set(10_000) // well past the idle window
        assertEquals(0, reg.evictIdle(), "a pinned session is never evicted")
        assertEquals(1, reg.activeCount())
      }
  }

  @Test
  fun `idle forked sessions are evicted, fresh ones are kept`() {
    val clock = AtomicLong(0)
    val factory = CountingFactory()
    ServeSessionRegistry(
        factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        reg.acquire("old") // lastAccess = 0
        clock.set(50)
        reg.acquire("fresh") // lastAccess = 50
        clock.set(120) // old idle 120ms (≥100 → evict); fresh idle 70ms (<100 → keep)
        assertEquals(1, reg.evictIdle())
        assertEquals(1, reg.activeCount())
        assertNotNull(reg.acquire("fresh"))
      }
  }

  @Test
  fun `a session with live watchers is not evicted`() {
    val clock = AtomicLong(0)
    val streamingHost = newHost(streaming = true)
    val factory = ServeSessionFactory { streamingHost }
    ServeSessionRegistry(
        factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val host = assertNotNull(reg.acquire("s"))
        // Open a live watcher: the host now reports an active stream.
        val handle = assertNotNull(host.subscribeStream(previewId, PreviewOverrides()) {})
        clock.set(10_000)
        assertEquals(0, reg.evictIdle(), "a host with a live watcher must not be evicted")
        handle.close()
        assertEquals(1, reg.evictIdle(), "once the watcher leaves it can be evicted")
      }
  }

  @Test
  fun `a leased session is not evicted until the lease closes`() {
    val clock = AtomicLong(0)
    val factory = CountingFactory()
    ServeSessionRegistry(
        factory,
        idleTimeoutMillis = 100,
        reaperIntervalMillis = 0,
        clock = clock::get,
      )
      .use { reg ->
        val lease = assertNotNull(reg.lease("s"))
        clock.set(10_000)
        assertEquals(
          0,
          reg.evictIdle(),
          "an open lease keeps the session alive past the idle window",
        )
        lease.close()
        clock.set(10_200) // idle again past the window now that the holder is gone
        assertEquals(1, reg.evictIdle(), "after the lease closes the idle session is reaped")
      }
  }

  @Test
  fun `close releases every host`() {
    val factory = CountingFactory()
    val reg = ServeSessionRegistry(factory, reaperIntervalMillis = 0)
    reg.acquire("a")
    reg.acquire("b")
    assertEquals(2, reg.activeCount())
    reg.close()
    assertEquals(0, reg.activeCount())
    assertTrue(runCatching { reg.acquire("c") }.isFailure, "a closed registry rejects acquire")
  }
}
