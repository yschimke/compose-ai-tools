package ee.schimke.composeai.tui

import ee.schimke.composeai.cli.PreviewModule
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Filesystem-watch coverage for [DiscoveryWatcher]. These tests drive the real JDK [java.nio.file]
 * watch service, so they assert with a generous timeout — watch latency varies by platform — rather
 * than a tight sleep.
 */
class DiscoveryWatcherTest {
  private fun previewsJson(moduleDir: File): File =
    File(moduleDir, "build/compose-previews/previews.json")

  private fun writeManifest(file: File, vararg ids: String) {
    file.parentFile.mkdirs()
    val entries =
      ids.joinToString(",\n") { """    { "id": "$it", "functionName": "$it", "className": "C" }""" }
    file.writeText(
      """
      { "module": ":sample", "variant": "debug", "previews": [
      $entries
      ] }
      """
        .trimIndent()
    )
  }

  @Test
  fun firesWhenManifestIsRewritten(@TempDir tmp: File) {
    val moduleDir = File(tmp, "sample")
    val manifest = previewsJson(moduleDir)
    writeManifest(manifest, "A", "B") // exists before the watcher starts

    val module = PreviewModule(gradlePath = ":sample", projectDir = moduleDir)
    val latch = CountDownLatch(1)
    val fires = AtomicInteger(0)
    val watcher = DiscoveryWatcher(listOf(module), debounceMillis = 50)
    try {
      watcher.start {
        fires.incrementAndGet()
        latch.countDown()
      }
      // Give the watch thread a beat to register, then rewrite the manifest (a @Preview renamed).
      Thread.sleep(300)
      writeManifest(manifest, "A", "B", "C")

      assertTrue(
        latch.await(15, TimeUnit.SECONDS),
        "DiscoveryWatcher did not fire after previews.json was rewritten",
      )
    } finally {
      watcher.close()
    }
  }

  @Test
  fun firesWhenManifestAppearsLater(@TempDir tmp: File) {
    // Module has rendered nothing yet: build/ doesn't exist when the watcher starts. The watcher
    // should still pick up previews.json once the chain is created.
    val moduleDir = File(tmp, "sample").apply { mkdirs() }
    val module = PreviewModule(gradlePath = ":sample", projectDir = moduleDir)
    val latch = CountDownLatch(1)
    val watcher = DiscoveryWatcher(listOf(module), debounceMillis = 50)
    try {
      watcher.start { latch.countDown() }
      Thread.sleep(300)
      // Create build/, then compose-previews/, then the manifest — each level should be registered
      // as it appears so the leaf write is observed.
      writeManifest(previewsJson(moduleDir), "A")

      assertTrue(
        latch.await(15, TimeUnit.SECONDS),
        "DiscoveryWatcher did not fire when previews.json was created late",
      )
    } finally {
      watcher.close()
    }
  }
}
