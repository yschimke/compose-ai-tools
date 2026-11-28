@file:Suppress("DEPRECATION") // Keep Gradle 8.13 (agp8-min) compatibility alongside Gradle 9.x.

package ee.schimke.composeai.plugin

import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity

/**
 * Structured Gradle Problems API reporting for Compose Preview diagnostics and validation tasks.
 *
 * Surfaces actionable findings and build failures in Gradle's Problems report, Build Scans, and
 * IntelliJ / Android Studio's Build / Sync Problem view alongside the existing task output.
 */
internal object ComposePreviewProblems {
  internal val ROOT_GROUP: ProblemGroup = ProblemGroup.create("compose-preview", "Compose Preview")

  internal val DOCTOR_GROUP: ProblemGroup =
    ProblemGroup.create("doctor", "Compose Preview Doctor", ROOT_GROUP)

  internal val VALIDATION_GROUP: ProblemGroup =
    ProblemGroup.create("validation", "Compose Preview Validation", ROOT_GROUP)

  internal fun sanitizeSlug(raw: String): String =
    raw.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "finding" }

  internal fun mapSeverity(severity: String): Severity =
    when (severity.lowercase()) {
      "error" -> Severity.ERROR
      "warning" -> Severity.WARNING
      else -> Severity.ADVICE
    }

  internal fun reportDoctorFinding(
    problems: Problems,
    modulePath: String,
    variant: String,
    id: String,
    severity: String,
    message: String,
    detail: String? = null,
    remediationSummary: String? = null,
    remediationCommands: List<String> = emptyList(),
    docsUrl: String? = null,
  ) {
    val problemId = ProblemId.create(sanitizeSlug(id), message, DOCTOR_GROUP)
    problems.reporter.report(problemId) {
      contextualLabel("$modulePath ($variant): $message")
      severity(mapSeverity(severity))
      detail?.takeIf { it.isNotBlank() }?.let { details(it) }
      remediationSummary?.takeIf { it.isNotBlank() }?.let { solution(it) }
      for (cmd in remediationCommands) {
        if (cmd.isNotBlank()) solution("Run: $cmd")
      }
      docsUrl
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.let { documentedAt(it) }
    }
  }

  internal fun reportValidationFailure(
    problems: Problems,
    id: String,
    displayName: String,
    contextualLabel: String,
    details: String,
    solutions: List<String> = emptyList(),
    docsUrl: String? = null,
    exception: Throwable? = null,
  ) {
    val problemId = ProblemId.create(sanitizeSlug(id), displayName, VALIDATION_GROUP)
    problems.reporter.report(problemId) {
      contextualLabel(contextualLabel)
      severity(Severity.ERROR)
      if (details.isNotBlank()) details(details)
      for (s in solutions) {
        if (s.isNotBlank()) solution(s)
      }
      docsUrl
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.let { documentedAt(it) }
      if (exception != null) withException(exception)
    }
  }
}
