package ee.schimke.composeai.plugin

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.problems.Severity
import org.gradle.api.tasks.testing.TestEventReporterFactory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradleToolingApisTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `SimulatorConcurrencyService default and property parsing`() {
    assertEquals(1, SimulatorConcurrencyService.defaultMaxParallel(1))
    assertEquals(1, SimulatorConcurrencyService.defaultMaxParallel(2))
    assertEquals(2, SimulatorConcurrencyService.defaultMaxParallel(4))
    assertEquals(4, SimulatorConcurrencyService.defaultMaxParallel(8))
    assertEquals(4, SimulatorConcurrencyService.defaultMaxParallel(32))

    assertEquals(3, SimulatorConcurrencyService.resolveMaxParallel("3", availableProcessors = 8))
    assertEquals(1, SimulatorConcurrencyService.resolveMaxParallel("0", availableProcessors = 8))
    assertEquals(1, SimulatorConcurrencyService.resolveMaxParallel("-5", availableProcessors = 8))
    assertEquals(
      4,
      SimulatorConcurrencyService.resolveMaxParallel("not-a-number", availableProcessors = 8),
    )
    assertEquals(2, SimulatorConcurrencyService.resolveMaxParallel(null, availableProcessors = 4))
  }

  @Test
  fun `SimulatorConcurrencyService matches managed device AndroidTest task names`() {
    assertTrue(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName(
        "simulatorDebugAndroidTest",
        "simulator",
      ),
    )
    assertTrue(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName(
        "simulatorWearReleaseAndroidTest",
        "simulatorWear",
      ),
    )
    assertFalse(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName("simulatorCheck", "simulator"),
    )
    assertFalse(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName("simulatorSetup", "simulator"),
    )
    assertFalse(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName("simulatorAndroidTest", "simulator"),
    )
    assertFalse(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName(
        "connectedDebugAndroidTest",
        "simulator",
      ),
    )
    assertFalse(
      SimulatorConcurrencyService.isManagedDeviceTestTaskName(
        "simulatortestDebugAndroidTest",
        "simulator",
      ),
    )
  }

  @Test
  fun `SimulatorConcurrencyService registers build-scoped service`() {
    val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("proj")).build()
    val serviceProvider = SimulatorConcurrencyService.registerIfAbsent(project)
    assertNotNull(serviceProvider.get())
    val registration =
      project.gradle.sharedServices.registrations.findByName(
        SimulatorConcurrencyService.SERVICE_NAME,
      )
    assertNotNull(registration)
    assertTrue((registration!!.maxParallelUsages.orNull ?: 0) >= 1)
  }

  @Test
  @Suppress("DEPRECATION")
  fun `ComposePreviewProblems sanitizes slugs and maps severities`() {
    assertEquals("agp-version-too-old", ComposePreviewProblems.sanitizeSlug("AGP_VERSION_TOO_OLD"))
    assertEquals("compose-1-8", ComposePreviewProblems.sanitizeSlug("  compose.1.8  "))
    assertEquals("finding", ComposePreviewProblems.sanitizeSlug("___"))

    assertEquals(Severity.ERROR, ComposePreviewProblems.mapSeverity("error"))
    assertEquals(Severity.WARNING, ComposePreviewProblems.mapSeverity("WARNING"))
    assertEquals(Severity.ADVICE, ComposePreviewProblems.mapSeverity("info"))
  }

  @Test
  fun `PreviewTestEventSession evaluates PNG, suffixed sibling, error sidecar, and thrown exceptions`() {
    val dir = tempFolder.newFolder("renders")

    val okPng = File(dir, "Home.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    assertEquals(
      PreviewTestEventSession.CaptureOutcome.Succeeded,
      PreviewTestEventSession.evaluateCapture(okPng),
    )

    val fanoutExpected = File(dir, "Card.png")
    File(dir, "Card_PARAM_0.png").writeBytes(byteArrayOf(4, 5, 6))
    assertEquals(
      PreviewTestEventSession.CaptureOutcome.Succeeded,
      PreviewTestEventSession.evaluateCapture(fanoutExpected),
    )

    val brokenPng = File(dir, "Broken.png")
    val errorJson =
      PreviewRenderError(
        exception = "java.lang.IllegalStateException",
        message = "Missing CompositionLocal",
        stackTrace = "java.lang.IllegalStateException: Missing CompositionLocal\n\tat App.kt:42",
      )
    File("${brokenPng.absolutePath}.error.json")
      .writeText(Json.encodeToString(PreviewRenderError.serializer(), errorJson))
    val sidecarOutcome =
      PreviewTestEventSession.evaluateCapture(brokenPng)
        as PreviewTestEventSession.CaptureOutcome.Failed
    assertTrue(sidecarOutcome.message.contains("Missing CompositionLocal"))
    assertTrue(sidecarOutcome.stackTrace!!.contains("App.kt:42"))

    val missingPng = File(dir, "Missing.png")
    val missingOutcome =
      PreviewTestEventSession.evaluateCapture(missingPng)
        as PreviewTestEventSession.CaptureOutcome.Failed
    assertTrue(missingOutcome.message.contains("Missing.png"))

    val thrownOutcome =
      PreviewTestEventSession.evaluateCapture(okPng, IllegalArgumentException("boom"))
        as PreviewTestEventSession.CaptureOutcome.Failed
    assertEquals("boom", thrownOutcome.message)
  }

  @Test
  fun `PreviewTestEventSession writes Gradle binary and HTML test reports`() {
    val project =
      ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("report-proj")).build()
    val task = project.tasks.register("composePreviewRender", RenderPreviewsTask::class.java).get()
    val binaryDirProp: DirectoryProperty = project.objects.directoryProperty()
    val htmlDirProp: DirectoryProperty = project.objects.directoryProperty()
    binaryDirProp.set(tempFolder.newFolder("binary-results"))
    htmlDirProp.set(tempFolder.newFolder("html-report"))

    val factory: TestEventReporterFactory = task.testEventReporterFactory
    val session =
      PreviewTestEventSession.open(
        factory = factory,
        rootName = "composePreviewRender",
        binaryResultsDir = binaryDirProp.get(),
        htmlReportDir = htmlDirProp.get(),
      )
    assertNotNull(session)
    val t0 = Instant.now()
    val t1 = t0.plusMillis(25)
    session!!.use {
      it.recordCapture(
        className = "com.example.HomePreviewsKt",
        testName = "com.example.HomePreviewsKt.HomePreview",
        displayName = "HomePreview",
        startTime = t0,
        endTime = t1,
        outcome = PreviewTestEventSession.CaptureOutcome.Succeeded,
      )
      it.recordCapture(
        className = "com.example.HomePreviewsKt",
        testName = "com.example.HomePreviewsKt.BrokenPreview",
        displayName = "BrokenPreview",
        startTime = t0,
        endTime = t1,
        outcome = PreviewTestEventSession.CaptureOutcome.Failed("Boom", "at Broken.kt:12"),
      )
      it.finishRoot(failedMessage = null)
    }

    assertTrue(
      "Expected HTML report index.html",
      File(htmlDirProp.get().asFile, "index.html").isFile,
    )
    assertTrue(
      "Expected binary test results to be non-empty",
      (binaryDirProp.get().asFile.listFiles()?.size ?: 0) > 0,
    )
  }

  @Test
  fun `PreviewTestEventSession handles blank className and root failure cleanly`() {
    val project =
      ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("lottie-proj")).build()
    val task =
      project.tasks.register("composePreviewRenderLottie", RenderPreviewsTask::class.java).get()
    val binaryDirProp: DirectoryProperty = project.objects.directoryProperty()
    val htmlDirProp: DirectoryProperty = project.objects.directoryProperty()
    binaryDirProp.set(tempFolder.newFolder("lottie-binary"))
    htmlDirProp.set(tempFolder.newFolder("lottie-html"))

    val session =
      PreviewTestEventSession.open(
        factory = task.testEventReporterFactory,
        rootName = "composePreviewRenderLottie",
        binaryResultsDir = binaryDirProp.get(),
        htmlReportDir = htmlDirProp.get(),
      )!!
    val t0 = Instant.now()
    val t1 = t0.plusMillis(10)
    try {
      session.recordCapture(
        className = "",
        testName = "lottie__spin[0]",
        displayName = "lottie/spin.json (lottie__spin)",
        startTime = t0,
        endTime = t1,
        outcome = PreviewTestEventSession.CaptureOutcome.Failed("NoSuchMethodError", "trace"),
      )
      session.finishRoot(failedMessage = "no output produced")
    } finally {
      session.close()
    }
    assertTrue(File(htmlDirProp.get().asFile, "index.html").isFile)
  }
}
