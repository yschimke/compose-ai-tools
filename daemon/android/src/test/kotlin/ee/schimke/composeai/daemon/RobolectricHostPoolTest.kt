package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SANDBOX-POOL.md Layer 2 — boots [RobolectricHost] with `sandboxCount = 2`, submits a spread of
 * stub renders, and asserts that:
 *
 * 1. Both slots accept renders (each handles at least one).
 * 2. The two slots have **distinct** sandbox classloaders — proving Robolectric's sandbox cache
 *    didn't collapse the pool to a single shared sandbox via the
 *    [SandboxHoldingRunner]/[SandboxHoldingHints] discriminator + constructor-snapshot path.
 * 3. Renders dispatched to the same slot consistently see that slot's classloader (i.e. the slot
 *    dispatch in [RobolectricHost.submit] is stable — `Math.floorMod(id, sandboxCount)` is keyed
 *    on the request id which is monotonic per process).
 *
 * **Why ids are bucketed by `id and 1`**: when the payload doesn't carry a `previewId=` key
 * (legacy stub payloads like `render-N`), [RobolectricHost.submit] hashes the request id instead.
 * For small positive Long ids `Long.hashCode()` is the low 32 bits as a signed int, so its parity
 * matches `id and 1L`; bucketing by that aligns with the actual dispatch path.
 *
 * Affinity-aware dispatch (`previewId=<id>` in the payload) is covered by
 * [samePreviewIdAlwaysLandsOnSameSlot] below.
 *
 * **Why `legacyStubPayload` and not real RenderSpecs**: [RobolectricHostTest] already submits the
 * `payload="render-N"` shape that the B1.3-era stub path was built for. Reusing that path keeps
 * the assertion focused on slot dispatch and sandbox identity rather than the heavier render-
 * engine work (Roborazzi capture, bitmap save, etc.).
 */
class RobolectricHostPoolTest {

  @Test
  fun twoSandboxesServeDistinctClassloaders() {
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

      // Both buckets must consistently see *one* sandbox classloader each — slot dispatch is
      // stable (id-keyed, `Math.floorMod`).
      val bucket0Hashes = byBucket.getValue(0).map { it.classLoaderHashCode }.toSet()
      val bucket1Hashes = byBucket.getValue(1).map { it.classLoaderHashCode }.toSet()
      assertEquals(
        "bucket 0 should see exactly one classloader, saw $bucket0Hashes",
        1,
        bucket0Hashes.size,
      )
      assertEquals(
        "bucket 1 should see exactly one classloader, saw $bucket1Hashes",
        1,
        bucket1Hashes.size,
      )

      // The load-bearing assertion: the two buckets see *different* classloaders. If the
      // discriminator failed to break Robolectric's sandbox cache, both buckets would land on the
      // same cached sandbox and these would match — proving the pool collapsed to a single
      // sandbox.
      assertNotEquals(
        "expected distinct sandbox classloaders across slots — see SANDBOX-POOL.md if this " +
          "regresses (discriminator may be back to colliding on the cache key)",
        bucket0Hashes.single(),
        bucket1Hashes.single(),
      )

      // Sanity probe: classloader names look like Robolectric's instrumenting loader on both
      // sides, so we know we're inside two real sandboxes (not on the test JVM's app classloader).
      val sampleNames =
        setOf(
          byBucket.getValue(0).first().classLoaderName,
          byBucket.getValue(1).first().classLoaderName,
        )
      for (cl in sampleNames) {
        assertTrue(
          "expected an instrumenting/sandbox classloader, got '$cl'",
          cl.contains("Instrument") || cl.contains("Sandbox") || cl.contains("Robolectric"),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun samePreviewIdAlwaysLandsOnSameSlot() {
    // SANDBOX-POOL-FOLLOWUPS.md (#3, affinity-aware dispatch). Same previewId across many
    // submits must hit the same sandbox slot every time so per-sandbox Compose snapshot caches +
    // Robolectric shadow caches accumulate as intended; without this, repeat renders of the same
    // preview never warm a single sandbox.
    val host = RobolectricHost(sandboxCount = 2)
    try {
      host.start()

      // Use many previewIds so at least one lands on each slot regardless of how the hash
      // distributes — the per-previewId stability assertion below is the load-bearing one,
      // independent of which slot any individual id lands on.
      val previewIds = (0 until 16).map { i -> "com.example.preview.Foo$i.method" }
      val rendersPerPreview = 4
      val byPreview =
        previewIds.associateWith { previewId ->
          (1..rendersPerPreview).map { _ ->
            host.submit(RenderRequest.Render(payload = "previewId=$previewId"))
          }
        }

      // Load-bearing: each previewId's renders all land on a single classloader (= same slot).
      for ((previewId, results) in byPreview) {
        val classloaderHashes = results.map { it.classLoaderHashCode }.toSet()
        assertEquals(
          "previewId='$previewId' should always land on one slot, saw $classloaderHashes",
          1,
          classloaderHashes.size,
        )
      }

      // Sanity: across 16 previewIds, at least both slots got something. If everything pinned to
      // slot 0 the test would silently pass the per-id stability assertion on a degenerate hash.
      val allClassloaderHashes =
        previewIds
          .flatMap { previewId -> byPreview.getValue(previewId).map { it.classLoaderHashCode } }
          .toSet()
      assertEquals(
        "16 previewIds should spread across both slots, saw $allClassloaderHashes",
        2,
        allClassloaderHashes.size,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun normalRendersAvoidInteractiveSlotWhenHeldSessionIsPinned() {
    val host = RobolectricHost(sandboxCount = 2)
    val slotOnePayload =
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
        payload = slotOnePayload,
        id = 100L,
        interactiveSlotPinned = false,
      ),
    )
    assertEquals(
      "when slot 1 is held by live interactive mode, normal renderNow dispatch must stay on slot 0",
      0,
      host.chooseSlotIndexForTest(
        payload = slotOnePayload,
        id = 100L,
        interactiveSlotPinned = true,
      ),
    )
  }

  @Test
  fun rejectsLegacyHolderPlusFactory() {
    // SANDBOX-POOL-FOLLOWUPS.md (#1) — the two constructor paths are mutually exclusive. Pre-#1
    // the constraint was "no holder when sandboxCount > 1"; now the constraint is "use either
    // form, not both."
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
  fun perSlotHoldersHaveDistinctChildLoadersParentedToTheirSandbox() {
    // SANDBOX-POOL-FOLLOWUPS.md (#1) — the load-bearing per-slot guarantee: each slot's holder is
    // parented to that slot's sandbox classloader, so a render that lands on slot N resolves user
    // classes against sandbox N's framework classes (no classloader-identity skew across slots).
    //
    // Implementation note: we record the sandbox classloader the factory was invoked with for
    // each slot, then assert the recorded set matches the sandbox classloaders the renders
    // actually saw. This proves the factory was invoked with the right parent — without trying to
    // build a real child URLClassLoader (which would require URL resources we don't have here).
    val recordedParents = java.util.concurrent.ConcurrentHashMap<Int, ClassLoader>()
    val factory: (ClassLoader) -> UserClassLoaderHolder = { sandboxClassLoader ->
      val slotIndex =
        // Each slot has a unique sandbox classloader; use its identity as the key. We don't know
        // the slot index from inside the factory but identityHash is unique-per-instance.
        System.identityHashCode(sandboxClassLoader)
      recordedParents[slotIndex] = sandboxClassLoader
      UserClassLoaderHolder(urls = emptyList(), parentSupplier = { sandboxClassLoader })
    }
    val host = RobolectricHost(sandboxCount = 2, userClassloaderHolderFactory = factory)
    try {
      host.start()
      // Drive enough renders that each slot is exercised at least once.
      val results =
        (1..8).map { i ->
          host.submit(RenderRequest.Render(payload = "previewId=com.example.Preview$i"))
        }
      val sandboxCls = results.map { it.classLoaderHashCode }.toSet()
      assertEquals(
        "two slots must produce two distinct sandbox classloaders, saw $sandboxCls",
        2,
        sandboxCls.size,
      )
      // The factory must have been invoked exactly once per slot (idempotent CAS in the host).
      assertEquals(
        "factory should be invoked once per slot, saw ${recordedParents.size}: ${recordedParents.keys}",
        2,
        recordedParents.size,
      )
      // Recorded parent classloaders match the classloaders the renders actually observed.
      assertEquals(
        "recorded parents must equal the rendered-against classloaders",
        sandboxCls,
        recordedParents.keys.toSet(),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun realRenderUsesTheCurrentSlotsChildLoader() {
    // Regression for the slot-1 NoSuchMethodException loop seen in VS Code after #536. Stub
    // renders only report the sandbox classloader and never enter RenderEngine, so they cannot
    // catch classloader-identity skew. This test drives real Compose reflection through a
    // UserClassLoaderHolder-backed child loader:
    //
    // 1. Render a real fixture on slot 0 so the legacy slot-0 currentChildLoader alias is populated.
    // 2. Render the same fixture on slot 1.
    //
    // The bug was that slot 1 read DaemonHostBridge.currentChildLoader() (slot 0's alias) instead
    // of slot.childLoaderRef. That loaded RedFixturePreviewsKt with slot 0's sandbox as parent while
    // executing inside slot 1's sandbox, so Compose's Composer parameter type did not match and
    // getDeclaredComposableMethod reported the valid @Composable function as missing.
    val userClassesDir = stageFixtureClassesDir()
    val outputDir = Files.createTempDirectory("pool-real-renders").toFile()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val probe = RobolectricHost(sandboxCount = 2)
    val slot0PreviewId =
      (0 until 128)
        .map { i -> "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare.slot0.$i" }
        .first { previewId ->
          probe.chooseSlotIndexForTest(
            payload = renderPayload(previewId, outputBaseName = "probe"),
            id = 1L,
          ) == 0
        }
    val slot1PreviewId =
      (0 until 128)
        .map { i -> "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare.slot1.$i" }
        .first { previewId ->
          probe.chooseSlotIndexForTest(
            payload = renderPayload(previewId, outputBaseName = "probe"),
            id = 2L,
          ) == 1
        }

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
      assertNotNull("slot 1 real render should produce a PNG", slot1.pngPath)
      assertTrue("slot 1 PNG should exist", File(slot1.pngPath!!).exists())
    } finally {
      host.shutdown()
      outputDir.deleteRecursively()
      userClassesDir.deleteRecursively()
    }
  }

  @Test
  fun swapUserClassLoadersBroadcastsToEverySlot() {
    // SANDBOX-POOL-FOLLOWUPS.md (#1) — `fileChanged({ kind: "source" })` calls
    // `host.swapUserClassLoaders()`, which must invalidate every slot's holder so the next render
    // to any slot allocates a fresh child loader. Pre-#1 the equivalent code only swapped one
    // shared holder.
    val swapCallsPerSlot = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    val factory: (ClassLoader) -> UserClassLoaderHolder = { sandboxClassLoader ->
      val key = System.identityHashCode(sandboxClassLoader)
      val counter = swapCallsPerSlot.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger() }
      UserClassLoaderHolder(
        urls = emptyList(),
        parentSupplier = { sandboxClassLoader },
        onSwap = { counter.incrementAndGet() },
      )
    }
    val host = RobolectricHost(sandboxCount = 2, userClassloaderHolderFactory = factory)
    try {
      host.start()
      // Warm both slots so both holders are allocated. Without this the swap is a no-op for slots
      // whose factory has never been invoked.
      (1..8).forEach { i -> host.submit(RenderRequest.Render(payload = "previewId=com.example.P$i")) }
      assertEquals("expected both slots warmed", 2, swapCallsPerSlot.size)
      // Trigger the broadcast.
      host.swapUserClassLoaders()
      // Each slot's holder must have observed exactly one swap.
      for ((key, counter) in swapCallsPerSlot) {
        assertEquals(
          "slot identityHash=$key should observe exactly one swap broadcast",
          1,
          counter.get(),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  private fun <T : Throwable> assertThrows(expected: Class<T>, block: () -> Unit): T {
    try {
      block()
    } catch (t: Throwable) {
      if (expected.isInstance(t)) {
        @Suppress("UNCHECKED_CAST")
        return t as T
      }
      throw AssertionError(
        "expected ${expected.name}, got ${t.javaClass.name}: ${t.message}",
        t,
      )
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
      (Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader())
        .getResource(resourceName)
        ?: error("Can't locate testFixtures class on the test classpath: $resourceName")
    val urlString = url.toString()
    if (urlString.startsWith("file:")) {
      val classFile = File(url.toURI())
      val pkgDepth = "ee/schimke/composeai/daemon".count { it == '/' } + 1
      var root: File = classFile.parentFile ?: error("classFile has no parent: $classFile")
      repeat(pkgDepth) {
        root = root.parentFile ?: error("ran off the top of the classes-dir walking up from $classFile")
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
