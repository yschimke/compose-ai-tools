package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServeSharedDaemonPoolTest {
  private class BlockingHost(
    private val name: String,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
  ) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = name
    override val daemonProcessCount: Int = 1
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      entered.countDown()
      assertTrue(release.await(5, TimeUnit.SECONDS), "timed out waiting to release $name")
      return RenderOutcome.Ok(name.encodeToByteArray())
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `five overlapping leased renders use five shared daemon instances`() {
    val entered = CountDownLatch(5)
    val release = CountDownLatch(1)
    val opened = AtomicInteger()
    val replicas = Collections.synchronizedList(mutableListOf<BlockingHost>())
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary) {
        BlockingHost("replica-${opened.incrementAndGet()}", entered, release).also(replicas::add)
      }
    val executor = Executors.newFixedThreadPool(5)

    try {
      val results =
        (0 until 5).map { i ->
          executor.submit<RenderOutcome> { pool.render("preview-$i", PreviewOverrides()) }
        }

      assertTrue(entered.await(5, TimeUnit.SECONDS), "all five daemon renders should overlap")
      assertEquals(4, opened.get())
      assertEquals(5, 1 + pool.replicaProcessCount())
      assertEquals(DaemonPoolSnapshot("shared-replicas", 4, 4, 0), pool.snapshot())

      release.countDown()
      results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok) }
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }

    assertEquals(4, replicas.count { it.closed })
    assertEquals(false, primary.closed, "the composite owns the primary daemon")
  }
}
