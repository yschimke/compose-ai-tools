package ee.schimke.composeai.cli

import java.io.PrintStream

/**
 * One test failure captured live by [GradleConnection]'s Tooling API listener. Carries everything
 * we'd otherwise have to extract from a per-task JUnit XML report.
 * - [taskPath] — Gradle path of the owning `Test` task (e.g. `:app:renderPreviews`), so output can
 *   be grouped per task and the agent reading the log knows which subproject to look at.
 * - [className]/[methodName] — populated for JVM tests (`JvmTestOperationDescriptor`); both null
 *   for non-JVM frameworks, in which case [displayName] is the fallback identifier.
 * - [message]/[description] — straight from `org.gradle.tooling.Failure`. `description` is the
 *   stack trace.
 */
data class CapturedTestFailure(
  val taskPath: String,
  val className: String?,
  val methodName: String?,
  val displayName: String,
  val message: String?,
  val description: String?,
)

/**
 * Prints captured test failures to stderr, grouped by Gradle task path. Replaces the post-build
 * walk over JUnit XML reports — the same data is now captured live during the build via
 * [GradleConnection.lastTestFailures].
 *
 * Default [stackLines] keeps the per-failure block short; agents can still rerun with `--verbose`
 * for the full Gradle output.
 */
internal fun printCapturedTestFailures(
  failures: List<CapturedTestFailure>,
  err: PrintStream = System.err,
  stackLines: Int = 6,
) {
  if (failures.isEmpty()) return
  val byTask = failures.groupBy { it.taskPath }
  for ((taskPath, entries) in byTask) {
    err.println()
    err.println("Failing tests in $taskPath (${entries.size}):")
    for (failure in entries) {
      err.println("  ${formatTestId(failure)}")
      val msg = failure.message?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
      if (!msg.isNullOrEmpty()) err.println("    $msg")
      val trimmed =
        failure.description
          ?.lines()
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.take(stackLines) ?: emptyList()
      for (line in trimmed) err.println("    $line")
    }
  }
}

private fun formatTestId(failure: CapturedTestFailure): String {
  val cls = failure.className
  val method = failure.methodName
  return when {
    cls != null && method != null -> "$cls.$method"
    cls != null -> cls
    else -> failure.displayName
  }
}
