package ee.schimke.composeai.plugin

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import org.gradle.api.file.Directory
import org.gradle.api.tasks.testing.GroupTestEventReporter
import org.gradle.api.tasks.testing.TestEventReporterFactory

/**
 * Streams per-preview render results from [RenderPreviewsTask] to Gradle's native
 * [TestEventReporterFactory], generating binary test results and an HTML report and lighting up
 * IntelliJ / Android Studio's Test Results tree for non-`Test` preview render tasks.
 */
internal class PreviewTestEventSession
private constructor(
  private val rootReporter: GroupTestEventReporter,
  private val closeThrowsOnFailure: Boolean,
) : AutoCloseable {

  private val classGroups = LinkedHashMap<String, ClassGroupState>()
  private var closed = false
  private var rootOutcomeRecorded = false

  private class ClassGroupState(
    val reporter: GroupTestEventReporter,
    var anyFailed: Boolean = false,
  )

  internal fun recordCapture(
    className: String,
    testName: String,
    displayName: String,
    startTime: Instant,
    endTime: Instant,
    outcome: CaptureOutcome,
  ) {
    val group =
      classGroups.getOrPut(className) {
        val groupReporter = rootReporter.reportTestGroup(className)
        groupReporter.started(startTime)
        ClassGroupState(groupReporter)
      }
    val test = group.reporter.reportTest(testName, displayName)
    test.started(startTime)
    when (outcome) {
      is CaptureOutcome.Succeeded -> test.succeeded(endTime)
      is CaptureOutcome.Failed -> {
        group.anyFailed = true
        if (outcome.stackTrace.isNullOrBlank()) {
          test.failed(endTime, outcome.message)
        } else {
          test.failed(endTime, outcome.message, outcome.stackTrace)
        }
      }
    }
    test.close()
  }

  internal fun finishRoot(failedMessage: String? = null) {
    if (rootOutcomeRecorded) return
    rootOutcomeRecorded = true
    val now = Instant.now()
    for (group in classGroups.values) {
      if (group.anyFailed) {
        group.reporter.failed(now)
      } else {
        group.reporter.succeeded(now)
      }
      group.reporter.close()
    }
    classGroups.clear()
    // When running on Gradle 8.13 (3-arg factory where close() throws on root failure), keep root
    // succeeded so RenderPreviewsTask's own GradleException propagates without being masked.
    if (failedMessage != null && !closeThrowsOnFailure) {
      rootReporter.failed(now, failedMessage)
    } else {
      rootReporter.succeeded(now)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    if (!rootOutcomeRecorded) {
      finishRoot(failedMessage = null)
    }
    rootReporter.close()
  }

  internal sealed interface CaptureOutcome {
    data object Succeeded : CaptureOutcome

    data class Failed(val message: String, val stackTrace: String? = null) : CaptureOutcome
  }

  internal companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    internal fun open(
      factory: TestEventReporterFactory,
      rootName: String,
      binaryResultsDir: Directory?,
      htmlReportDir: Directory?,
    ): PreviewTestEventSession? {
      if (binaryResultsDir == null || htmlReportDir == null) return null
      binaryResultsDir.asFile.mkdirs()
      htmlReportDir.asFile.mkdirs()
      var closeThrows = false
      val reporter =
        try {
          factory.createTestEventReporter(rootName, binaryResultsDir, htmlReportDir, false)
        } catch (_: NoSuchMethodError) {
          closeThrows = true
          factory.createTestEventReporter(rootName, binaryResultsDir, htmlReportDir)
        }
      reporter.started(Instant.now())
      return PreviewTestEventSession(reporter, closeThrows)
    }

    /**
     * Evaluates one rendered capture's outcome from its expected [outputFile] and optional
     * `<outputFile>.error.json` sidecar (or an execution exception).
     */
    internal fun evaluateCapture(
      outputFile: File,
      thrown: Throwable? = null,
    ): CaptureOutcome {
      if (thrown != null) {
        return CaptureOutcome.Failed(
          message = thrown.message ?: thrown::class.java.simpleName,
          stackTrace = thrown.stackTraceToString(),
        )
      }
      val errorSidecar = File("${outputFile.absolutePath}.error.json")
      if (errorSidecar.isFile) {
        val parsed = runCatching {
          JSON.decodeFromString(PreviewRenderError.serializer(), errorSidecar.readText())
        }
          .getOrNull()
        if (parsed != null) {
          val summary = buildString {
            if (!parsed.diagnosis.isNullOrBlank()) {
              append(parsed.diagnosis)
            } else {
              append(parsed.exception)
              if (parsed.message.isNotBlank()) append(": ").append(parsed.message)
            }
          }
          return CaptureOutcome.Failed(
            message = summary.ifBlank { "Render failed for ${outputFile.name}" },
            stackTrace = parsed.stackTrace.takeIf { it.isNotBlank() },
          )
        }
        return CaptureOutcome.Failed(
          message = "Render failed (see ${errorSidecar.name})",
        )
      }
      if (outputFile.isFile && outputFile.length() > 0L) {
        return CaptureOutcome.Succeeded
      }
      if (hasSuffixedSibling(outputFile)) {
        return CaptureOutcome.Succeeded
      }
      return CaptureOutcome.Failed(
        message = "No rendered output written to ${outputFile.name}",
      )
    }

    private fun hasSuffixedSibling(candidate: File): Boolean {
      val stem = candidate.nameWithoutExtension
      val extension = candidate.extension
      val siblings = candidate.parentFile?.listFiles() ?: return false
      return siblings.any {
        it.isFile &&
          it.length() > 0L &&
          it.extension == extension &&
          it.nameWithoutExtension.startsWith("${stem}_")
      }
    }
  }
}
