package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SANDBOX-POOL.md — boots [RobolectricHost] with `sandboxCount = 2` and asserts the pool's
 * load-bearing properties in its **out-of-process** shape (issue #3072): slot 0 is the sandbox in
 * this JVM, slot 1 is a worker JVM, and renders dispatch across both.
 *
 * **Why processes and not classloaders.** Robolectric's native-graphics runtime loads
 * `libandroid_runtime.so` once per process and registers its JNI natives against the first
 * sandbox's instrumented framework classes. A second sandbox in the same JVM comes up with a
 * `Typeface` whose system font map never populated (`NullPointerException` at `Typeface.java:928`)
 * and the next native text call takes the process down with a `SIGSEGV`. Every assertion here that
 * used to read "the two slots have distinct classloaders" now reads "the two slots are distinct
 * processes" — `RobolectricHost.workerPidsForTest()` is the seam.
 *
 * **Why ids are bucketed by `id and 1`**: when the payload doesn't carry a `previewId=` key (legacy
 * stub payloads like `render-N`), [RobolectricHost.submit] hashes the request id instead. For small
 * positive Long ids `Long.hashCode()` is the low 32 bits as a signed int, so its parity matches `id
 * and 1L`; bucketing by that aligns with the actual dispatch path.
 */
class RobolectricHostPoolTest {

  @Test
  fun twoSlotsServeRendersFromDistinctProcesses() {
    val host = RobolectricHost(sandboxCount = 2)
    try {
      host.start()
      val results = (1..20).map { i -> host.submit(RenderRequest.Render(payload = "render-$i")) }
      assertEquals(20, results.size)

      val byBucket = results.groupBy { (it.id and 1L).toInt() }
      assertEquals(
        "expected dispatch to land renders in both buckets (sandboxCount=2)",
        setOf(0, 1),
        byBucket.keys,
      )

      // The load-bearing assertion: slot 1 is a *separate JVM*. If the pool ever regresses to
      // allocating its second sandbox in-process, this list is empty (and the JVM most likely dies
      // in `libandroid_runtime.so` long before the assertion runs).
      val workerPids = host.workerPidsForTest()
      assertEquals("sandboxCount=2 should own exactly one worker process", 1, workerPids.size)
      val workerPid = workerPids.single()
      assertNotNull("worker 0 should be booted and alive", workerPid)
      assertNotEquals(
        "the worker must be a different process from the daemon JVM",
        ProcessHandle.current().pid(),
        workerPid,
      )

      // Sanity probe: the in-process slot still renders inside a real Robolectric sandbox.
      val localName = byBucket.getValue(0).first().classLoaderName
      assertTrue(
        "expected an instrumenting/sandbox classloader on slot 0, got '$localName'",
        localName.contains("Instrument") ||
          localName.contains("Sandbox") ||
          localName.contains("Robolectric"),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun samePreviewIdAlwaysLandsOnSameSlot() {
    // Affinity dispatch: the same previewId must resolve to the same slot every time so per-sandbox
    // Compose snapshot caches and Robolectric shadow caches accumulate as intended. Asserted
    // against the dispatch function directly rather than by rendering — with slots in different
    // processes there is no shared classloader identity to read back off a result, and dispatch is
    // the thing under test either way.
    val host = RobolectricHost(sandboxCount = 3)
    val previewIds = (0 until 32).map { i -> "com.example.preview.Foo$i.method" }

    val slotByPreview = previewIds.associateWith { previewId ->
      val slots =
        (1L..8L)
          .map { id -> host.chooseSlotIndexForTest(payload = "previewId=$previewId", id = id) }
          .toSet()
      assertEquals(
        "previewId='$previewId' should always resolve to one slot, saw $slots",
        1,
        slots.size,
      )
      slots.single()
    }

    assertEquals(
      "32 previewIds should spread across all three slots, saw ${slotByPreview.values.toSet()}",
      setOf(0, 1, 2),
      slotByPreview.values.toSet(),
    )
  }

  @Test
  fun normalRendersAvoidInteractiveSlotWhenHeldSessionIsPinned() {
    // A held session pins slot 0 — the in-process sandbox, the only one that can back a live
    // `ComposeTestRule` — so every normal render has to route to a worker process for the
    // session's lifetime.
    val host = RobolectricHost(sandboxCount = 2)
    val slotZeroPayload =
      (0 until 64)
        .map { i -> "previewId=com.example.preview.HashesToInteractiveSlot$i" }
        .first { payload ->
          host.chooseSlotIndexForTest(
            payload = payload,
            id = 100L,
            interactiveSlotPinned = false,
          ) == RobolectricHost.INTERACTIVE_SLOT_INDEX
        }

    assertEquals(
      "test setup should pick a payload that normally hashes to the interactive slot",
      RobolectricHost.INTERACTIVE_SLOT_INDEX,
      host.chooseSlotIndexForTest(
        payload = slotZeroPayload,
        id = 100L,
        interactiveSlotPinned = false,
      ),
    )
    assertNotEquals(
      "while the in-process slot is held by a live session, normal dispatch must move to a worker",
      RobolectricHost.INTERACTIVE_SLOT_INDEX,
      host.chooseSlotIndexForTest(
        payload = slotZeroPayload,
        id = 100L,
        interactiveSlotPinned = true,
      ),
    )
  }

  @Test
  fun backgroundBootServesRendersBeforePoolCompletesAndWarmsTheLateSlot() {
    // Cold-start fast path (`composeai.daemon.backgroundSandboxBoot=true`): start() blocks only
    // until slot 0 (in-process) is up, the worker JVMs boot on a background thread, and dispatch
    // routes across the ready prefix meanwhile. Asserts the three load-bearing pieces:
    //   1. a render succeeds immediately after start() (before the pool completes),
    //   2. the background boot eventually completes the pool,
    //   3. the background-booted worker got its boot-time warm render (the __warmup-slot-1 PNG,
    //      written by the worker process into the shared output dir).
    val outputDir = Files.createTempDirectory("pool-background-boot").toFile()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty(RobolectricHost.BACKGROUND_BOOT_PROP, "true")
    val host = RobolectricHost(sandboxCount = 2)
    try {
      host.start()
      // Must not block on the worker: a stub render right after start() succeeds on the ready
      // prefix (slot 0 alone).
      val first = host.submit(RenderRequest.Render(payload = "render-1"))
      assertNotNull("render immediately after start() should succeed", first)

      // Background boot completes the pool (generous bound — a worker pays a JVM launch plus a
      // full Robolectric boot).
      val poolDeadline = System.currentTimeMillis() + 300_000
      while (host.readySlotCountForTest() < 2 && System.currentTimeMillis() < poolDeadline) {
        Thread.sleep(200)
      }
      assertEquals("background boot should complete the pool", 2, host.readySlotCountForTest())

      // The late slot's boot-time warm render lands (it runs right after the slot turns ready).
      val warmPng = File(outputDir, "__warmup-slot-1.png")
      assertTrue("expected boot-time warm render PNG at $warmPng", warmPng.exists())

      // Steady state: renders dispatched to the worker come back with a real PNG.
      val results =
        (0 until 8).map { i ->
          host.submit(RenderRequest.Render(payload = "previewId=com.example.preview.Bg$i"))
        }
      assertEquals("every steady-state render should return a result", 8, results.size)
    } finally {
      System.clearProperty(RobolectricHost.BACKGROUND_BOOT_PROP)
      host.shutdown()
      outputDir.deleteRecursively()
    }
  }

  @Test
  fun rejectsLegacyHolderPlusFactory() {
    // The two constructor paths are mutually exclusive: use either the legacy holder or the
    // per-slot factory, not both.
    val holder =
      UserClassLoaderHolder(
        urls = emptyList(),
        parentSupplier = { ClassLoader.getSystemClassLoader() },
      )
    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        RobolectricHost(
          userClassloaderHolder = holder,
          userClassloaderHolderFactory = { _ -> holder },
        )
      }
    assertTrue(
      "error should call out the holder-vs-factory exclusivity, got: ${ex.message}",
      ex.message?.contains("not both") == true,
    )
  }

  @Test
  fun rejectsLegacyHolderWithSandboxCountAboveOne() {
    val holder =
      UserClassLoaderHolder(
        urls = emptyList(),
        parentSupplier = { ClassLoader.getSystemClassLoader() },
      )
    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        RobolectricHost(userClassloaderHolder = holder, sandboxCount = 2)
      }
    assertTrue(
      "error should explain that pool callers should use the factory form, got: ${ex.message}",
      ex.message?.contains("userClassloaderHolderFactory") == true,
    )
  }

  @Test
  fun realRendersOnBothSlotsResolveUserClassesThroughTheirOwnChildLoader() {
    // Both halves of the pool have to resolve preview classes out of the disposable user-class
    // loader — the in-process slot through the holder the host allocates, the worker through the
    // one it builds for itself from `composeai.daemon.userClassDirs` (the pool forwards every
    // `composeai.*` property to the worker JVM, which is the same seam `DaemonMain` reads in
    // production). A worker that ignored the sysprop would fail to find the fixture class rather
    // than silently render something else, so a PNG from slot 1 is the assertion.
    val userClassesDir = stageFixtureClassesDir()
    val outputDir = Files.createTempDirectory("pool-real-renders").toFile()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, userClassesDir.absolutePath)

    val probe = RobolectricHost(sandboxCount = 2)
    val slot0PreviewId = previewIdHashingTo(probe, slot = 0, tag = "slot0")
    val slot1PreviewId = previewIdHashingTo(probe, slot = 1, tag = "slot1")

    val urls = listOf(userClassesDir.toURI().toURL())
    val host =
      RobolectricHost(
        sandboxCount = 2,
        userClassloaderHolderFactory = { sandboxClassLoader ->
          UserClassLoaderHolder(urls = urls, parentSupplier = { sandboxClassLoader })
        },
      )
    try {
      host.start()

      val slot0 =
        host.submit(
          RenderRequest.Render(payload = renderPayload(slot0PreviewId, outputBaseName = "slot-0")),
          timeoutMs = 120_000,
        )
      assertNotNull("slot 0 real render should produce a PNG", slot0.pngPath)
      assertTrue("slot 0 PNG should exist", File(slot0.pngPath!!).exists())

      val slot1 =
        host.submit(
          RenderRequest.Render(payload = renderPayload(slot1PreviewId, outputBaseName = "slot-1")),
          timeoutMs = 120_000,
        )
      assertNotNull("slot 1 (worker process) real render should produce a PNG", slot1.pngPath)
      assertTrue("slot 1 PNG should exist", File(slot1.pngPath!!).exists())
    } finally {
      host.shutdown()
      System.clearProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
      outputDir.deleteRecursively()
      userClassesDir.deleteRecursively()
    }
  }

  @Test
  fun interactiveSessionRunsOnTheInProcessSandbox() {
    // #3072 — the held session's `ComposeTestRule`, bridge queues and frame latches are live object
    // handles, so the session runs on the sandbox in *this* JVM (slot 0) while the worker
    // process(es) take the normal renders. This is the test the cap had disabled: with a
    // single-JVM pool it could never boot two sandboxes to get here.
    val userClassesDir = stageFixtureClassesDir()
    val outputDir = Files.createTempDirectory("pool-interactive-renders").toFile()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val urls = listOf(userClassesDir.toURI().toURL())
    val host =
      RobolectricHost(
        sandboxCount = 2,
        userClassloaderHolderFactory = { sandboxClassLoader ->
          UserClassLoaderHolder(urls = urls, parentSupplier = { sandboxClassLoader })
        },
        previewSpecResolver = { previewId ->
          RenderSpec(
            className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            functionName = "RedSquare",
            widthPx = 64,
            heightPx = 64,
            density = 1.0f,
            showBackground = true,
            outputBaseName = previewId,
          )
        },
      )
    try {
      host.start()
      assertTrue(
        "host with a worker process should advertise interactive",
        host.supportsInteractive,
      )

      val session =
        host.acquireInteractiveSession(
          previewId = "interactive-red",
          classLoader = javaClass.classLoader!!,
        )
      try {
        val result = session.render(RenderHost.nextRequestId())
        assertNotNull("interactive render should produce a PNG", result.pngPath)
        assertTrue("interactive PNG should exist", File(result.pngPath!!).exists())

        // While the session holds slot 0, a normal render still succeeds — on the worker.
        val normal =
          host.submit(
            RenderRequest.Render(payload = renderPayload("during-session", "during-session")),
            timeoutMs = 120_000,
          )
        assertNotNull("normal renders must keep flowing during a held session", normal.pngPath)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
      outputDir.deleteRecursively()
      userClassesDir.deleteRecursively()
    }
  }

  @Test
  fun swapUserClassLoadersBroadcastsToTheInProcessHolderAndTheWorkers() {
    // `fileChanged({ kind: "source" })` calls `host.swapUserClassLoaders()`. The in-process holder
    // must observe the swap, and the worker processes must survive their half of the broadcast —
    // a worker that died on the swap would drop out of the pool and later renders would fail.
    val swaps = java.util.concurrent.atomic.AtomicInteger()
    val factory: (ClassLoader) -> UserClassLoaderHolder = { sandboxClassLoader ->
      UserClassLoaderHolder(
        urls = emptyList(),
        parentSupplier = { sandboxClassLoader },
        onSwap = { swaps.incrementAndGet() },
      )
    }
    val host = RobolectricHost(sandboxCount = 2, userClassloaderHolderFactory = factory)
    try {
      host.start()
      // Warm slot 0 so its holder is allocated; without this the swap is a no-op locally. The
      // payload has to be one that actually dispatches to slot 0 — affinity hashing sends most
      // previewIds to a worker, which owns its own holder in its own JVM.
      val slotZeroPayload =
        (0 until 64)
          .map { i -> "previewId=com.example.P$i" }
          .first { payload -> host.chooseSlotIndexForTest(payload = payload, id = 1L) == 0 }
      host.submit(RenderRequest.Render(payload = slotZeroPayload))

      host.swapUserClassLoaders()

      assertEquals("the in-process holder should observe exactly one swap", 1, swaps.get())
      assertNotNull(
        "the worker must survive the swap broadcast and stay in the pool",
        host.workerPidsForTest().single(),
      )
      // And it must still serve renders afterwards.
      val after = (1..6).map { i -> host.submit(RenderRequest.Render(payload = "render-$i")) }
      assertEquals(6, after.size)
    } finally {
      host.shutdown()
    }
  }

  private fun previewIdHashingTo(probe: RobolectricHost, slot: Int, tag: String): String =
    (0 until 128)
      .map { i -> "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare.$tag.$i" }
      .first { previewId ->
        probe.chooseSlotIndexForTest(
          payload = renderPayload(previewId, outputBaseName = "probe"),
          id = 1L,
        ) == slot
      }

  private fun <T : Throwable> assertThrows(expected: Class<T>, block: () -> Unit): T {
    try {
      block()
    } catch (t: Throwable) {
      if (expected.isInstance(t)) {
        @Suppress("UNCHECKED_CAST")
        return t as T
      }
      throw AssertionError("expected ${expected.name}, got ${t.javaClass.name}: ${t.message}", t)
    }
    throw AssertionError("expected ${expected.name} to be thrown")
  }

  private fun renderPayload(previewId: String, outputBaseName: String): String =
    "previewId=$previewId;" +
      "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
      "functionName=RedSquare;" +
      "widthPx=64;heightPx=64;density=1.0;" +
      "showBackground=true;" +
      "outputBaseName=$outputBaseName"

  private fun stageFixtureClassesDir(): File {
    val tempDir = Files.createTempDirectory("pool-userClasses").toFile()
    val resourceName = "ee/schimke/composeai/daemon/RedFixturePreviewsKt.class"
    val url =
      (Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()).getResource(
        resourceName
      ) ?: error("Can't locate testFixtures class on the test classpath: $resourceName")
    val urlString = url.toString()
    if (urlString.startsWith("file:")) {
      val classFile = File(url.toURI())
      val pkgDepth = "ee/schimke/composeai/daemon".count { it == '/' } + 1
      var root: File = classFile.parentFile ?: error("classFile has no parent: $classFile")
      repeat(pkgDepth) {
        root =
          root.parentFile ?: error("ran off the top of the classes-dir walking up from $classFile")
      }
      root.copyRecursively(tempDir, overwrite = true)
      return tempDir
    }
    if (urlString.startsWith("jar:file:")) {
      val jarPath = urlString.removePrefix("jar:file:").substringBefore("!").let { File(it) }
      java.util.zip.ZipFile(jarPath).use { jar ->
        for (entry in jar.entries()) {
          if (!entry.name.startsWith("ee/schimke/composeai/daemon/") || entry.isDirectory) continue
          val out = File(tempDir, entry.name)
          out.parentFile?.mkdirs()
          jar.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
      }
      return tempDir
    }
    error("Unsupported testFixtures URL shape: $urlString")
  }
}
