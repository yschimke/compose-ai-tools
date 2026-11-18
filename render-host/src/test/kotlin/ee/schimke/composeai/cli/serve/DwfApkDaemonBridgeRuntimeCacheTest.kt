package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DwfApkDaemonBridgeRuntimeCacheTest {
  @Test
  fun `a runtime converted before the clock rewrite is not reused`() {
    // v3 jars were converted without ZonedDateTime.now() -> SimulatorClock; reusing one keeps a
    // pinned time out of the live draw.
    assertNotEquals("runtime-v3-abc.jar", DwfApkDaemonBridge.runtimeClassesFileName("abc"))
    assertEquals("runtime-v4-abc.jar", DwfApkDaemonBridge.runtimeClassesFileName("abc"))
  }

  private fun env(apk: java.io.File) =
    DwfApkDaemonBridge.Environment(
      runtimeApk = apk,
      simulatorJars = emptyList(),
      androidDaemonJars = emptyList(),
      dex2jarJars = emptyList(),
      androidJar = apk,
      aapt2 = null,
    )

  @Test
  fun `concurrent callers convert one runtime once and never see a partial jar`() {
    val apk =
      java.io.File.createTempFile("dwf-runtime-", ".apk").apply {
        writeText("unique-${System.nanoTime()}")
        deleteOnExit()
      }
    val env = env(apk)
    val conversions = java.util.concurrent.atomic.AtomicInteger()
    val convert = { _: DwfApkDaemonBridge.Environment, out: java.io.File ->
      conversions.incrementAndGet()
      // Written in two steps with a pause between them, so an unguarded second converter or a
      // premature read would observe the half-written file.
      out.writeText("half")
      Thread.sleep(150)
      out.appendText("-done")
    }
    val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
    try {
      val results =
        (1..6).map {
          pool.submit<String> { DwfApkDaemonBridge.ensureRuntimeClasses(env, convert).readText() }
        }
      results.forEach {
        assertEquals("half-done", it.get(30, java.util.concurrent.TimeUnit.SECONDS))
      }
    } finally {
      pool.shutdownNow()
    }
    assertEquals(1, conversions.get())
    val jar = DwfApkDaemonBridge.ensureRuntimeClasses(env, convert)
    assertEquals(
      emptyList<String>(),
      jar.parentFile
        .listFiles { f ->
          f.name.endsWith(".jar.tmp") &&
            "-${jar.name.removePrefix("runtime-v4-").removeSuffix(".jar")}-" in f.name
        }
        .orEmpty()
        .map { it.name },
    )
    jar.delete()
  }

  @Test
  fun `a failed conversion leaves no jar and no temp file, and the next call retries`() {
    val apk =
      java.io.File.createTempFile("dwf-runtime-", ".apk").apply {
        writeText("fail-${System.nanoTime()}")
        deleteOnExit()
      }
    val env = env(apk)
    var fail = true
    val convert = { _: DwfApkDaemonBridge.Environment, out: java.io.File ->
      out.writeText("partial")
      if (fail) error("boom")
    }
    kotlin.test.assertFailsWith<IllegalStateException> {
      DwfApkDaemonBridge.ensureRuntimeClasses(env, convert)
    }
    fail = false
    val jar = DwfApkDaemonBridge.ensureRuntimeClasses(env, convert)
    assertEquals("partial", jar.readText())
    jar.delete()
  }
}
