package ee.schimke.composeai.previewdriver

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.Collections
import java.util.Properties
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.LongRunningOperation
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.StartEvent
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestOperationDescriptor

data class GradleAccessFailure(
  val operation: String,
  val message: String,
  val detail: String? = null,
)

enum class GradleTaskDisposition {
  SUCCESS,
  UP_TO_DATE,
  FROM_CACHE,
  FAILED,
  SKIPPED,
}

data class GradleTaskOutcome(val taskPath: String, val disposition: GradleTaskDisposition) {
  val canReadOutputs: Boolean
    get() = disposition != GradleTaskDisposition.SKIPPED
}

class GradleConnection(
  private val projectDir: File,
  private val verbose: Boolean,
  private val progress: Boolean = false,
  /**
   * Arguments prepended to every Tooling-API invocation, e.g. the CLI's `--init-script <path>` that
   * auto-applies the plugin ([autoInjectInitScriptArgs]).
   */
  private val extraArguments: List<String> = emptyList(),
) : AutoCloseable {
  /**
   * Optional advice on a build failure: given Gradle's captured stderr plus the exception chain,
   * returns one message to print after the report, or null (e.g. the CLI's explanation of the
   * plugin marker publication race). A property to keep the published constructor's signature
   * stable.
   */
  var failureAdvice: ((String) -> String?)? = null

  companion object {
    /**
     * Wall-clock budget before a Gradle invocation is cancelled: headroom for a genuinely cold
     * daemon (a cold single-preview render measured 309s against the old 300s budget).
     */
    const val DEFAULT_TIMEOUT_SECONDS: Long = 600
  }

  private val connector =
    GradleConnector.newConnector().forProjectDirectory(projectDir).apply {
      // `forProjectDirectory` only reads that directory's own wrapper; a nested build borrowing its
      // parent's (issue #5031) should use the distribution `../gradlew` would.
      inheritedWrapperDistribution(projectDir)?.let { useDistribution(it) }
    }
  private val connection = connector.connect()
  private var modelAccessFailure: GradleAccessFailure? = null

  val lastModelAccessFailure: GradleAccessFailure?
    get() = modelAccessFailure

  private var discoveryFailures: List<ProjectDiscoveryFailure> = emptyList()

  /**
   * Projects whose `ComposePreviewModel` failed to build during the last [findPreviewModules], so
   * an empty discovery can be explained (issue #3).
   */
  val lastDiscoveryFailures: List<ProjectDiscoveryFailure>
    get() = discoveryFailures

  private val capturedTestFailures =
    Collections.synchronizedList(mutableListOf<CapturedTestFailure>())
  private val capturedTaskOutcomes =
    Collections.synchronizedMap(linkedMapOf<String, GradleTaskOutcome>())

  /** Test failures captured live from progress events during the last [runTasks]. */
  fun lastTestFailures(): List<CapturedTestFailure> =
    synchronized(capturedTestFailures) { capturedTestFailures.toList() }

  fun lastTaskOutcomes(): Map<String, GradleTaskOutcome> =
    synchronized(capturedTaskOutcomes) { capturedTaskOutcomes.toMap() }

  fun runTasks(
    vararg tasks: String,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    arguments: List<String> = emptyList(),
  ): Boolean {
    val tokenSource: CancellationTokenSource = GradleConnector.newCancellationTokenSource()
    val startTime = System.currentTimeMillis()
    val runningTasks = Collections.synchronizedSet(linkedSetOf<String>())
    capturedTestFailures.clear()
    capturedTaskOutcomes.clear()

    // Cancel on Ctrl+C so the Gradle daemon and forked test workers aren't left running.
    val shutdownHook = Thread {
      System.err.println("\nInterrupted — cancelling Gradle build...")
      tokenSource.cancel()
      try {
        connection.close()
      } catch (_: Exception) {}
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    val timer =
      java.util.Timer(true).apply {
        schedule(
          object : java.util.TimerTask() {
            override fun run() {
              // Name the remedy; "cancelling" alone reads as a hung build.
              System.err.println(
                "Build timed out after ${timeoutSeconds}s, cancelling. " +
                  "If the build was still making progress, rerun with a longer budget: " +
                  "--timeout ${timeoutSeconds * 2}"
              )
              TerminalProgress.error()
              tokenSource.cancel()
            }
          },
          timeoutSeconds * 1000,
        )

        // Opt-in heartbeat naming running tasks (cold Robolectric starts are silent for minutes);
        // slower on CI to avoid flooding long logs.
        if (progress) {
          val heartbeatMs = if (System.getenv("CI") == "true") 60_000L else 15_000L
          schedule(
            object : java.util.TimerTask() {
              override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val running = synchronized(runningTasks) { runningTasks.toList() }
                if (running.isNotEmpty()) {
                  System.err.println("  [${elapsed}s] running: ${running.joinToString(", ")}")
                }
              }
            },
            heartbeatMs,
            heartbeatMs,
          )
        }
      }

    TerminalProgress.indeterminate()
    var taskCount = 0
    var tasksFinished = 0

    val errorCapture = ByteArrayOutputStream()

    return try {
      val launcher =
        connection.newBuild().forTasks(*tasks).withCancellationToken(tokenSource.token())
      val combinedArguments = extraArguments + arguments
      if (combinedArguments.isNotEmpty()) {
        launcher.withArguments(combinedArguments)
      }
      launcher.withoutWithheldEnvironment()

      if (verbose) {
        launcher.setStandardOutput(System.err)
        launcher.setStandardError(System.err)
      } else {
        launcher.setStandardOutput(NullOutputStream)
        launcher.setStandardError(errorCapture)
      }

      // TEST events capture failing-test details; they are kept out of the task counters.
      val listenerTypes = setOf(OperationType.TASK, OperationType.TEST)

      launcher.addProgressListener(
        { event: ProgressEvent ->
          val descriptor = event.descriptor
          when {
            descriptor is TaskOperationDescriptor -> {
              val desc = descriptor.name
              when (event) {
                is StartEvent -> {
                  taskCount++
                  runningTasks.add(desc)
                }
                is FinishEvent -> {
                  runningTasks.remove(desc)
                  tasksFinished++
                  val taskPath = descriptor.taskPath
                  capturedTaskOutcomes[taskPath] =
                    GradleTaskOutcome(taskPath, event.result.toTaskDisposition())
                  if (taskCount > 0) {
                    TerminalProgress.show((tasksFinished * 100) / taskCount)
                  }
                  if (
                    progress &&
                      !verbose &&
                      (desc.contains("composePreviewDiscover") ||
                        desc.contains("composePreviewRender") ||
                        desc.contains("composePreviewRenderAll"))
                  ) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    System.err.println("  [${elapsed}s] $desc")
                  }
                }
                else -> {}
              }
            }
            descriptor is TestOperationDescriptor && event is FinishEvent -> {
              val result = event.result
              if (result is FailureResult) collectTestFailures(descriptor, result.failures)
            }
          }
        },
        listenerTypes,
      )

      launcher.run()
      TerminalProgress.show(100)
      true
    } catch (e: org.gradle.tooling.BuildCancelledException) {
      TerminalProgress.error()
      System.err.println("Build cancelled.")
      false
    } catch (e: org.gradle.tooling.BuildException) {
      TerminalProgress.error()
      printBuildFailure(e, errorCapture)
      false
    } catch (e: org.gradle.tooling.GradleConnectionException) {
      TerminalProgress.error()
      System.err.println("Gradle connection failed: ${e.message}")
      false
    } finally {
      timer.cancel()
      tokenSource.cancel()
      TerminalProgress.hide()
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
      } catch (_: IllegalStateException) {}
    }
  }

  private fun org.gradle.tooling.events.OperationResult.toTaskDisposition(): GradleTaskDisposition =
    when (this) {
      is TaskSuccessResult ->
        when {
          isFromCache -> GradleTaskDisposition.FROM_CACHE
          isUpToDate -> GradleTaskDisposition.UP_TO_DATE
          else -> GradleTaskDisposition.SUCCESS
        }
      is TaskFailureResult -> GradleTaskDisposition.FAILED
      is TaskSkippedResult -> GradleTaskDisposition.SKIPPED
      else -> GradleTaskDisposition.SUCCESS
    }

  private fun printBuildFailure(
    e: org.gradle.tooling.BuildException,
    errorCapture: ByteArrayOutputStream,
  ) {
    val messages = e.causeMessages()
    val captured = errorCapture.toString().trim()
    if (captured.isNotEmpty()) {
      val actionable = actionableFailureLines(captured)
      if (actionable.isNotEmpty()) {
        for (line in actionable) {
          System.err.println(line)
        }
      } else if (verbose) {
        System.err.println(captured)
      }
    }

    // Fall back to the exception chain when Gradle's own report isn't there.
    if (captured.isEmpty() || !captured.contains("What went wrong")) {
      System.err.println("Build failed: ${messages.firstOrNull() ?: "unknown error"}")
      if (messages.size > 1) {
        System.err.println("Caused by: ${messages.drop(1).joinToString(" → ")}")
      }
    }

    failureAdvice?.invoke((captured + "\n" + messages.joinToString("\n")).trim())?.let {
      System.err.println()
      System.err.println(it)
    }

    System.err.println()
    System.err.println("Run with --verbose for full build output.")
  }

  private fun collectTestFailures(
    descriptor: TestOperationDescriptor,
    failures: List<org.gradle.tooling.Failure>,
  ) {
    val taskPath = findTaskPath(descriptor) ?: "(unknown task)"
    val (className, methodName) =
      when (descriptor) {
        is JvmTestOperationDescriptor -> descriptor.className to descriptor.methodName
        else -> null to null
      }
    val displayName = descriptor.displayName
    for (failure in failures) {
      capturedTestFailures +=
        CapturedTestFailure(
          taskPath = taskPath,
          className = className,
          methodName = methodName,
          displayName = displayName,
          message = failure.message,
          description = failure.description,
        )
    }
  }

  private fun findTaskPath(descriptor: OperationDescriptor): String? {
    var d: OperationDescriptor? = descriptor
    while (d != null) {
      if (d is TaskOperationDescriptor) return d.taskPath
      d = d.parent
    }
    return null
  }

  /**
   * Run [action] with a timeout. Returns `null` on any Tooling API failure, recorded in
   * [lastModelAccessFailure]; callers fold that into "skip".
   */
  fun <R> runBuildAction(action: org.gradle.tooling.BuildAction<R>, timeoutSeconds: Long = 60): R? {
    val tokenSource: CancellationTokenSource = GradleConnector.newCancellationTokenSource()
    val timer =
      java.util.Timer(true).apply {
        schedule(
          object : java.util.TimerTask() {
            override fun run() {
              tokenSource.cancel()
            }
          },
          timeoutSeconds * 1000,
        )
      }
    return try {
      connection
        .action(action)
        .withCancellationToken(tokenSource.token())
        .apply {
          if (extraArguments.isNotEmpty()) withArguments(extraArguments)
          withoutWithheldEnvironment()
          if (verbose) {
            setStandardOutput(System.err)
            setStandardError(System.err)
          } else {
            setStandardOutput(NullOutputStream)
            setStandardError(NullOutputStream)
          }
        }
        .run()
        .also { modelAccessFailure = null }
    } catch (e: org.gradle.tooling.GradleConnectionException) {
      recordModelAccessFailure("BuildAction", e)
      if (verbose) System.err.println("Gradle connection failed: ${e.message}")
      null
    } catch (e: org.gradle.tooling.BuildException) {
      recordModelAccessFailure("BuildAction", e)
      if (verbose) System.err.println("Build action failed: ${e.message}")
      null
    } finally {
      timer.cancel()
      tokenSource.cancel()
    }
  }

  /**
   * Gradle's `BuildEnvironment` model (Gradle version and daemon `javaHome`), used by doctor to
   * spot a test-worker JVM that differs from the daemon's (#142). Null on any failure.
   */
  fun buildEnvironment(): org.gradle.tooling.model.build.BuildEnvironment? {
    return try {
      connection
        .model(org.gradle.tooling.model.build.BuildEnvironment::class.java)
        .apply {
          if (extraArguments.isNotEmpty()) withArguments(extraArguments)
          withoutWithheldEnvironment()
        }
        .get()
        .also { modelAccessFailure = null }
    } catch (e: Exception) {
      recordModelAccessFailure("BuildEnvironment", e)
      if (verbose) System.err.println("Could not query BuildEnvironment: ${e.message}")
      null
    }
  }

  /**
   * Every project that applies the plugin, with its Gradle path and configured `projectDir` (read
   * from the Tooling API, since it can be anywhere). Uses [DiscoverPreviewModulesAction] to avoid
   * realizing every task. A Tooling API failure yields an empty list with [lastModelAccessFailure] set;
   * per-project failures land in [lastDiscoveryFailures].
   */
  // Published artifact: @JvmOverloads keeps the no-arg signature existing consumers link against.
  @JvmOverloads
  fun findPreviewModules(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): List<PreviewModule> {
    // The caller's budget: cold-daemon configuration is slow, and a fixed number is wrong in both
    // directions.
    val result = runBuildAction(DiscoverPreviewModulesAction(), timeoutSeconds = timeoutSeconds)
    discoveryFailures = result?.failures ?: emptyList()
    return result?.modules ?: emptyList()
  }

  /** Resolve every Gradle project path to its configured directory without realizing tasks. */
  @JvmOverloads
  fun findGradleProjects(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): List<PreviewModule> =
    runBuildAction(DiscoverGradleProjectsAction(), timeoutSeconds = timeoutSeconds) ?: emptyList()

  /**
   * The plugin-applying module at [gradlePath] (leading `:` optional), or null when there is none.
   */
  @JvmOverloads
  fun findPreviewModule(
    gradlePath: String,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
  ): PreviewModule? {
    val normalized = gradlePath.removePrefix(":")
    return findPreviewModules(timeoutSeconds).firstOrNull { it.gradlePath == normalized }
  }

  override fun close() {
    connection.close()
  }

  private fun recordModelAccessFailure(operation: String, error: Throwable) {
    val messages = error.causeMessages()
    modelAccessFailure =
      GradleAccessFailure(
        operation = operation,
        message = messages.firstOrNull() ?: error::class.java.simpleName,
        detail = messages.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" -> "),
      )
  }
}

/**
 * Environment variables a build must never see. The Tooling API hands the client's whole
 * environment to the Gradle daemon, and through it to every build script, plugin and forked worker
 * of the project being built, which may be a third party's. A credential the CLI holds for its own
 * use (the design-guidelines OpenRouter key) stays in the CLI.
 */
internal val WITHHELD_BUILD_ENVIRONMENT: Set<String> = setOf("COMPOSE_PREVIEW_OPENROUTER_KEY")

/**
 * The environment to hand a build: [parent] without [WITHHELD_BUILD_ENVIRONMENT], or null when
 * [parent] holds none of them, so an ordinary run keeps the Tooling API's default (inherit).
 */
internal fun buildEnvironmentWithout(
  parent: Map<String, String>,
  withheld: Set<String> = WITHHELD_BUILD_ENVIRONMENT,
): Map<String, String>? {
  if (parent.keys.none { it in withheld }) return null
  return parent.filterKeys { it !in withheld }
}

private fun LongRunningOperation.withoutWithheldEnvironment() {
  buildEnvironmentWithout(System.getenv())?.let { setEnvironmentVariables(it) }
}

/**
 * The `distributionUrl` of the nearest ancestor wrapper of [projectDir], or null when [projectDir]
 * has its own wrapper (the Tooling API reads that itself), no ancestor has one, or the URL is
 * unusable.
 */
internal fun inheritedWrapperDistribution(
  projectDir: File,
  warn: (String) -> Unit = System.err::println,
  gradleUserHome: File? = defaultGradleUserHome(),
): URI? {
  if (wrapperProperties(projectDir) != null) return null
  var dir: File? = projectDir.parentFile
  while (dir != null) {
    wrapperProperties(dir)?.let { props ->
      return runCatching {
        val loaded = Properties().apply { props.inputStream().use { load(it) } }
        val url = loaded.getProperty("distributionUrl")?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = url?.let { wrapperDistributionUri(it, props) } ?: return@runCatching null
        val pinned = loaded.getProperty("distributionSha256Sum")?.trim()?.takeIf { it.isNotEmpty() }
        if (pinned != null && !distributionAlreadyInstalled(resolved, gradleUserHome)) {
          // `useDistribution(URI)` can't carry `distributionSha256Sum`, so an uncached pinned
          // distribution would be downloaded unverified. Refuse instead.
          warn(
            "compose-preview: not inheriting the Gradle distribution from ${props.path} — it " +
              "pins distributionSha256Sum, the Tooling API cannot be given a checksum, and " +
              "${redactedDistribution(resolved)} is not in the wrapper cache yet. Run " +
              "./gradlew once from ${props.parentFile?.parentFile?.path} (which verifies and " +
              "caches it), or give this build its own gradle/wrapper/gradle-wrapper.properties."
          )
          return@runCatching null
        }
        resolved
      }
        .getOrNull()
    }
    dir = dir.parentFile
  }
  return null
}

/** A wrapper `distributionUrl` as a URI, resolving a relative value against [props]' directory. */
private fun wrapperDistributionUri(url: String, props: File): URI? = runCatching {
  val uri = URI(url)
  if (uri.isAbsolute) uri else props.parentFile.toURI().resolve(url)
}
  .getOrNull()

/**
 * True when [distribution] is already unpacked (and so verified) in the wrapper cache, matched by
 * the public `wrapper/dists/<name>/<hash>/` layout and its `.ok` marker rather than Gradle's
 * internal URL hash.
 */
private fun distributionAlreadyInstalled(distribution: URI, gradleUserHome: File?): Boolean {
  val home = gradleUserHome ?: return false
  val name = distribution.path?.substringAfterLast('/')?.removeSuffix(".zip") ?: return false
  val dists = File(home, "wrapper/dists/$name")
  val versions = dists.listFiles()?.filter { it.isDirectory } ?: return false
  return versions.any { dir -> dir.listFiles()?.any { it.name.endsWith(".ok") } == true }
}

internal fun defaultGradleUserHome(): File? =
  System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
    ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it, ".gradle") }

/** A distribution URL safe to print in CI logs: userinfo and query string removed. */
internal fun redactedDistribution(uri: URI): String = runCatching {
  URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString() +
    if (uri.rawQuery != null) "?…" else ""
}
  .getOrElse { "(distribution URL withheld)" }

private fun wrapperProperties(dir: File): File? =
  File(dir, "gradle/wrapper/gradle-wrapper.properties").takeIf { it.isFile }

private object NullOutputStream : OutputStream() {
  override fun write(b: Int) {}

  override fun write(b: ByteArray) {}

  override fun write(b: ByteArray, off: Int, len: Int) {}
}

private fun Throwable.causeMessages(): List<String> {
  val messages = mutableListOf<String>()
  var cause: Throwable? = this
  while (cause != null) {
    cause.message?.takeIf { it.isNotBlank() && it !in messages }?.let(messages::add)
    cause = cause.cause
  }
  return messages
}

/**
 * The lines worth showing from Gradle's captured stderr when a build fails without `--verbose`. The
 * whole `* What went wrong:` block is kept (minus blank lines), ending only at a real
 * [GRADLE_FAILURE_SECTIONS] header since exception messages may contain `* ` bullets.
 */
internal fun actionableFailureLines(captured: String): List<String> {
  var inWhatWentWrong = false
  return captured.lines().filter { line ->
    val header = GRADLE_FAILURE_SECTIONS.any { line.startsWith(it) }
    if (header) inWhatWentWrong = line.startsWith("* What went wrong:")
    header ||
      (inWhatWentWrong && line.isNotBlank()) ||
      line.contains("error:", ignoreCase = true) ||
      line.contains("FAILURE:") ||
      line.contains("not found") ||
      line.startsWith("e: ") ||
      line.startsWith("> ") ||
      line.startsWith("* ")
  }
}

/**
 * Gradle's failure-report section headers, matched as prefixes (`* Get more help at` has no colon).
 */
private val GRADLE_FAILURE_SECTIONS =
  listOf("* Where:", "* What went wrong:", "* Try:", "* Exception is:", "* Get more help at")
