package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Collections
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
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

/**
 * Handle for a subproject that applies `ee.schimke.composeai.preview`.
 *
 * [gradlePath] is the colon-separated project path **without** its leading colon (e.g. `"app"`,
 * `"auth:composables"`) — used to address Gradle tasks like
 * `":$gradlePath:composePreviewRenderAll"` and to identify the module in CLI output / persisted
 * state. [projectDir] is the actual filesystem directory of that subproject, resolved via Gradle's
 * Tooling API. Using it instead of `projectRoot/$gradlePath` is what makes nested subprojects
 * (`:foo:bar`) and any custom `project.projectDir` override work correctly — see issue #157.
 */
data class PreviewModule(val gradlePath: String, val projectDir: File) : java.io.Serializable

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
   * Arguments prepended to every Tooling-API invocation this connection makes — `withArguments` on
   * `BuildLauncher`, `ModelBuilder`, and `BuildActionExecuter`. Today the CLI seeds this with
   * `--init-script <path>` so the compose-preview plugin is auto-applied to projects that haven't
   * manually wired it in their `build.gradle.kts`. See [autoInjectInitScriptArgs] for the source.
   */
  private val extraArguments: List<String> = emptyList(),
) : AutoCloseable {
  companion object {
    /**
     * Wall-clock budget a Gradle invocation gets before it is cancelled.
     *
     * Was 300s, which a real cold render did not fit in: a single-preview render on a cold daemon
     * measured 309s, and two of two runs at the old default timed out at ~320s while the same
     * render with a longer budget finished. A timeout that the documented default cannot survive
     * teaches callers to distrust the tool rather than to pass `--timeout`.
     *
     * Not a diagnosis of *why* a warm single-preview render can take five minutes — that is worth
     * its own investigation, and a bigger constant is not the answer to it.
     */
    const val DEFAULT_TIMEOUT_SECONDS: Long = 600
  }

  private val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
  private val connection = connector.connect()
  private var modelAccessFailure: GradleAccessFailure? = null

  val lastModelAccessFailure: GradleAccessFailure?
    get() = modelAccessFailure

  private var discoveryFailures: List<ProjectDiscoveryFailure> = emptyList()

  /**
   * Per-project configuration failures recorded during the most recent [findPreviewModules] call —
   * projects whose `ComposePreviewModel` couldn't be built and were therefore skipped. Lets callers
   * explain an empty discovery ("3 modules failed to configure: …") instead of the bare "No preview
   * modules discovered" that hid the real cause (issue #3). Empty when discovery succeeded for
   * every project or hasn't run yet.
   */
  val lastDiscoveryFailures: List<ProjectDiscoveryFailure>
    get() = discoveryFailures

  private val capturedTestFailures =
    Collections.synchronizedList(mutableListOf<CapturedTestFailure>())
  private val capturedTaskOutcomes =
    Collections.synchronizedMap(linkedMapOf<String, GradleTaskOutcome>())

  /**
   * Test failures captured during the most recent [runTasks] call. Populated live from the Tooling
   * API's progress events — no need to walk JUnit XML reports after the build. Empty until the
   * first failing test finishes; cleared at the start of each [runTasks].
   */
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

    // Ctrl+C otherwise kills the CLI without going through the cancellation
    // token — leaving the Gradle daemon still executing and any forked Test
    // worker (Robolectric, etc.) orphaned. Hook ensures clean cancellation.
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
              // Names the flag: this reads as a hung build otherwise, and the fix — "ask for more
              // time" — is not something a caller can guess from "cancelling...".
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

        // Heartbeat so the user can see what is still running (Robolectric
        // can take minutes on a cold start with no output). Opt-in via
        // --progress / --verbose so default CLI output stays quiet. CI gets
        // a slower cadence: one useful heartbeat per minute without flooding
        // a long design-catalog render with hundreds of near-identical lines.
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

    // Capture stderr for error reporting when not in verbose mode
    val errorCapture = ByteArrayOutputStream()

    return try {
      val launcher =
        connection.newBuild().forTasks(*tasks).withCancellationToken(tokenSource.token())
      val combinedArguments = extraArguments + arguments
      if (combinedArguments.isNotEmpty()) {
        launcher.withArguments(combinedArguments)
      }

      if (verbose) {
        launcher.setStandardOutput(System.err)
        launcher.setStandardError(System.err)
      } else {
        launcher.setStandardOutput(NullOutputStream)
        launcher.setStandardError(errorCapture)
      }

      // TEST events are always on so we can capture failing-test details
      // for `printBuildFailure`. Discriminate by descriptor type in the
      // listener so test events don't pollute the task-progress counters
      // or the heartbeat's "running:" list (a single render run can fire
      // hundreds of test events).
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
    // Extract the root cause message
    var cause: Throwable? = e
    val messages = mutableListOf<String>()
    while (cause != null) {
      cause.message?.let { msg -> if (msg.isNotBlank() && msg !in messages) messages.add(msg) }
      cause = cause.cause
    }

    // Show the captured stderr (Gradle's error output)
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

    // If no captured output was useful, show exception chain
    if (captured.isEmpty() || !captured.contains("What went wrong")) {
      System.err.println("Build failed: ${messages.firstOrNull() ?: "unknown error"}")
      if (messages.size > 1) {
        System.err.println("Caused by: ${messages.drop(1).joinToString(" → ")}")
      }
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
   * Fetch a Tooling API model registered by the applied plugin. Returns `null` if the model isn't
   * registered (plugin not applied, or version predates the model) or if the Gradle connection
   * fails — callers fold both into "skip project-scope checks" rather than erroring.
   *
   * The plugin-side model FQN and the [modelClass] passed here must match; see
   * `ComposePreviewModel.kt` on both sides for the contract.
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
   * Fetch Gradle's `BuildEnvironment` model — exposes the daemon's Gradle version and its
   * `javaHome`. Doctor uses both to triage bug reports where the forked test worker's JVM differs
   * from the daemon's (#142). Returns `null` on any tooling-API failure; callers fold into "skip".
   */
  fun buildEnvironment(): org.gradle.tooling.model.build.BuildEnvironment? {
    return try {
      connection
        .model(org.gradle.tooling.model.build.BuildEnvironment::class.java)
        .apply { if (extraArguments.isNotEmpty()) withArguments(extraArguments) }
        .get()
        .also { modelAccessFailure = null }
    } catch (e: Exception) {
      recordModelAccessFailure("BuildEnvironment", e)
      if (verbose) System.err.println("Could not query BuildEnvironment: ${e.message}")
      null
    }
  }

  /**
   * Find all subprojects that apply the compose-ai-tools plugin (detected by the presence of a
   * `composePreviewDiscover` task).
   *
   * Each entry carries both the Gradle path (used to build task specs like
   * `:foo:bar:composePreviewRenderAll`) and the resolved filesystem `projectDir`. Nested
   * subprojects (`:foo:bar`) have directory layouts like `foo/bar/`, so substituting `:` for `/`
   * doesn't always work — and even for standard layouts a user can point `project.projectDir`
   * anywhere. Reading it from the Tooling API's `BasicGradleProject.projectDirectory` is the only
   * reliable way to resolve manifests / PNGs on disk without replicating Gradle's own
   * project-layout logic.
   *
   * Implemented via [DiscoverPreviewModulesAction] rather than the `GradleProject` model: fetching
   * `GradleProject` realizes every task in every module, which runs unrelated modules'
   * configuration-time side effects (e.g. a `nativeCompile` task provisioning a Java toolchain)
   * during mere discovery (issue #1620). The build action queries the lightweight `GradleBuild` +
   * per-project `ComposePreviewModel` instead, which never realizes the full task graph.
   *
   * On a tooling-API failure the action returns `null`; we fold that into an empty list and leave
   * [lastModelAccessFailure] populated so callers can tell "no preview modules" from "couldn't talk
   * to Gradle." A generous timeout matches the old un-timed model query — discovery configures each
   * project, which can be slow on a cold daemon.
   *
   * Per-project configuration failures (a module the plugin applied to but that threw while its
   * model was built) are recorded in [lastDiscoveryFailures] rather than silently dropped, so an
   * empty result can be explained (issue #3).
   */
  // @JvmOverloads because this is a published artifact: a Kotlin default parameter compiles to a
  // single method taking the parameter plus a synthetic bridge, so the no-arg entry point an
  // already-compiled consumer links against simply vanishes — NoSuchMethodError on upgrade, from a
  // change that reads as purely additive in source. The generated overloads keep the old signatures
  // and spare Java callers a mandatory argument.
  @JvmOverloads
  fun findPreviewModules(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): List<PreviewModule> {
    // The *caller's* budget, not a constant of its own. Configuring every project on a cold daemon
    // is exactly where this overruns — the friction log's "doctor reports the project unusable when
    // it is usable" was this firing, cancelling the model query for a project whose `list` and
    // `render` then both worked — but a hardcoded number is the wrong fix in both directions:
    // `--timeout 60` could not bound a hung discovery, and `--timeout 1800` could not rescue a
    // legitimately slow one.
    val result = runBuildAction(DiscoverPreviewModulesAction(), timeoutSeconds = timeoutSeconds)
    discoveryFailures = result?.failures ?: emptyList()
    return result?.modules ?: emptyList()
  }

  /**
   * Resolve a single module by its Gradle path (colon-separated, with or without the leading `:`).
   * Returns `null` when no project with that path applies the plugin — callers fall back to a
   * user-visible error rather than silently building an empty task list against a dir that doesn't
   * exist.
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
 * The lines worth showing from Gradle's captured stderr when a build fails, without `--verbose`.
 *
 * Everything between `* What went wrong:` and the next `* <section>:` header is kept **verbatim**,
 * whatever it looks like. The per-line patterns only recognise Gradle's decorated cause lines (`>
 * …`) and compiler diagnostics, so a reason written as plain prose — `Execution failed for task
 * ':a:b'.`, or a task's own multi-line `GradleException` text — used to match nothing and be
 * dropped, printing `* What went wrong:` followed immediately by `* Try:` and no reason at all.
 * That empty block is worse than noise: `printBuildFailure`'s `captured.contains("What went
 * wrong")` check then suppresses the exception-chain fallback too, so the run reports a failure
 * with no cause anywhere in its output and the only way to learn what broke is to re-run the whole
 * render with `--verbose` — which, in CI, means nobody can.
 *
 * Blank lines inside the block are dropped so the section stays tight (Gradle puts one before the
 * next header).
 *
 * The block ends at the next entry of [GRADLE_FAILURE_SECTIONS], not at the next line that merely
 * starts with `* `: a task's `GradleException` message is free to contain its own unindented `* `
 * bullets, and treating one of those as a section header would drop the rest of the reason —
 * exactly the truncation this function exists to prevent (Codex review on #3003). Such a bullet is
 * still printed, it just doesn't close the block.
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
 * The section headers Gradle's console failure report emits, each at column zero. Used to decide
 * where a `* What went wrong:` block ends — see [actionableFailureLines]. `* Get more help at …` is
 * the one that doesn't end in a colon, so these are matched as prefixes rather than by shape.
 */
private val GRADLE_FAILURE_SECTIONS =
  listOf("* Where:", "* What went wrong:", "* Try:", "* Exception is:", "* Get more help at")
