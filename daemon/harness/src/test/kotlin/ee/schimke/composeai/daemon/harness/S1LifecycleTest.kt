package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S1 — Lifecycle happy path** from
 * [TEST-HARNESS § 3](../../../../docs/daemon/TEST-HARNESS.md#s1--lifecycle-happy-path) and
 * [§ 9 — v0 scope](../../../../docs/daemon/TEST-HARNESS.md#9-phasing).
 *
 * Drives one full daemon lifecycle over a real `ProcessBuilder`-spawned JVM running
 * [FakeDaemonMain] against a deterministic-test-pattern fixture:
 *
 * 1. Generate a single PNG via `TestPatterns.solidColour(...)` into the fixture dir.
 * 2. Write `previews.json` listing one preview id.
 * 3. `initialize` → assert `protocolVersion == 2`.
 * 4. `initialized` notification.
 * 5. `renderNow(["preview-1"], tier=fast)` → assert `queued == ["preview-1"]`, no rejections.
 * 6. Poll `renderStarted` → id = `preview-1`.
 * 7. Poll `renderFinished` → carries the PNG path.
 * 8. Pixel-diff the bytes the daemon reports against the fixture PNG → ok.
 * 9. `shutdown` → `exit` → process exit code 0.
 *
 * On failure, [PixelDiff.writeDiffArtefacts] writes `actual.png` / `expected.png` / `diff.png`
 * under `build/reports/daemon-harness/s1/` for review.
 *
 * **Manual verify-the-diff-fires sanity check.** To prove the failure path actually surfaces a bad
 * render, replace the contents of the fixture PNG between [setUp] and the daemon's read by
 * uncommenting the `corruptFixtureForSanityCheck` block — the test should fail with the three
 * artefacts written under `build/reports/daemon-harness/s1/`. This is documented per the v0 task's
 * verification step; do not commit it set to `true`.
 */
class S1LifecycleTest {

  @Test
  fun s1_lifecycle_happy_path() {
    val moduleBuildDir = File("build")
    val fixtureDir = File(moduleBuildDir, "daemon-harness/fixtures/s1")
    val reportsDir = File(moduleBuildDir, "reports/daemon-harness/s1")
    fixtureDir.deleteRecursively()
    fixtureDir.mkdirs()
    reportsDir.deleteRecursively()
    reportsDir.mkdirs()

    // 1. Test pattern PNG. Mid-grey solid is enough to exercise the pipeline; the diff machinery
    //    is exercised by the deliberate-corruption manual check (see KDoc).
    val previewId = "preview-1"
    val pngBytes = TestPatterns.solidColour(64, 64, 0xFF808080.toInt())
    val pngFile = File(fixtureDir, "$previewId.png")
    pngFile.writeBytes(pngBytes)

    // 2. previews.json.
    val previewsJson = File(fixtureDir, "previews.json")
    previewsJson.writeText(
      """[{"id":"$previewId","className":"fake.Preview1","functionName":"Preview1"}]"""
    )

    // (Sanity check — leave commented out. Uncomment to verify the failure path writes diff
    //  artefacts and fails the test loudly.)
    // corruptFixtureForSanityCheck(pngFile)

    // 3. Spawn the harness client subprocess with this test JVM's runtime classpath. Gradle wires
    //    `java.class.path` to the test runtime classpath, which contains both the harness's main
    //    artefacts (FakeDaemonMain etc.) and `:daemon:core`'s JsonRpcServer.
    val classpath = parseClasspath(System.getProperty("java.class.path"))
    val client = HarnessClient.start(fixtureDir = fixtureDir, classpath = classpath)
    try {
      // 4. initialize.
      val initResult = client.initialize()
      assertEquals(2, initResult.protocolVersion)
      // The daemon's manifest is reported as a placeholder until B2.2 ships incremental discovery;
      // the field exists and previewCount is 0 in the B1.5 stub. Assert the field shape rather
      // than a specific count so the harness keeps passing once B2.2 plumbs a real value through.
      assertNotNull("initialize.manifest must be present", initResult.manifest)

      // 5. initialized.
      client.sendInitialized()

      // 6. renderNow.
      val renderNowResult = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), renderNowResult.queued)
      assertTrue(
        "renderNow.rejected must be empty: ${renderNowResult.rejected}",
        renderNowResult.rejected.isEmpty(),
      )

      // 7. renderStarted.
      val started = client.pollNotification("renderStarted", 5.seconds)
      val startedParams = started["params"]?.jsonObject
      assertNotNull("renderStarted must carry params", startedParams)
      assertEquals(previewId, startedParams!!["id"]?.jsonPrimitive?.contentOrNull)

      // 8. renderFinished — pull the pngPath and pixel-diff it against the fixture.
      val finished = client.pollNotification("renderFinished", 10.seconds)
      val finishedParams = finished["params"]?.jsonObject
      assertNotNull("renderFinished must carry params", finishedParams)
      val reportedPath = finishedParams!!["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", reportedPath)
      val daemonReportedPng = File(reportedPath!!)
      assertTrue(
        "renderFinished.pngPath must reference an existing file: $reportedPath",
        daemonReportedPng.exists(),
      )
      val actualBytes = daemonReportedPng.readBytes()
      val diff = PixelDiff.compare(actual = actualBytes, expected = pngBytes)
      if (!diff.ok) {
        PixelDiff.writeDiffArtefacts(actual = actualBytes, expected = pngBytes, outDir = reportsDir)
        throw AssertionError(
          "S1 pixel diff failed: ${diff.message} " +
            "(maxDelta=${diff.maxDelta}, offending=${diff.offendingPixelCount}). " +
            "Artefacts under ${reportsDir.absolutePath}. " +
            "Stderr=\n${client.dumpStderr()}"
        )
      }

      // 9. shutdown + exit.
      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      // Surface stderr even when the failure isn't a pixel-diff one — the harness's value is
      // useless if a daemon crash dumps no diagnostics.
      System.err.println("S1 failed; stderr from daemon:\n" + client.dumpStderr())
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  /**
   * Sanity-check helper for the v0 verification step. Replaces the fixture PNG with a different
   * (but still valid) PNG of the same dimensions, so the daemon serves bytes that decode cleanly
   * but pixel-diff against a known mismatch — exercising the full failure path including `diff.png`
   * generation. Not invoked from the green path — uncomment the call site in the test to run the
   * manual check.
   */
  @Suppress("unused")
  private fun corruptFixtureForSanityCheck(pngFile: File) {
    // Magenta vs grey — wildly outside the per-pixel tolerance, well beyond the absolute cap.
    pngFile.writeBytes(TestPatterns.solidColour(64, 64, 0xFFFF00FF.toInt()))
  }

  private fun parseClasspath(raw: String): List<File> =
    raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }

  // Imports kept for the json shape probes inside the test body; Json instance unused here but
  // kept as a hint for v1 scenarios that decode params into typed message classes.
  @Suppress("unused") private val json = Json { ignoreUnknownKeys = true }
}
