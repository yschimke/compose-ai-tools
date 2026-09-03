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
   * Arguments prepended to every Tooling-API invocation this connection makes — `withArguments` on
   * `BuildLauncher`, `ModelBuilder`, and `BuildActionExecuter`. Today the CLI seeds this with
   * `--init-script <path>` so the compose-preview plugin is auto-applied to projects that haven't
   * manually wired it in their `build.gradle.kts`. See [autoInjectInitScriptArgs] for the source.
   */
  private val extraArguments: List<String> = emptyList(),
) : AutoCloseable {
  /**
   * Optional second opinion on a build failure: given everything this connection saw of it
   * (Gradle's captured stderr plus the exception chain), returns one message to print after the
   * failure report, or null when it recognises nothing.
   *
   * The driver deliberately knows nothing about *why* a particular string is worth explaining — the
   * CLI supplies the knowledge, because that is where it lives. Today the CLI uses it to name the
   * publication race behind an unresolvable plugin marker (issue #5034), which otherwise arrives as
   * a configuration failure in the consumer's own project and is diagnosed as one.
   *
   * A settable property rather than a constructor parameter **on purpose**: this module is a
   * published library, and adding a defaulted parameter would change the primary constructor's JVM
   * descriptor and its synthetic defaults constructor — every consumer compiled against the
   * previous release would get a `NoSuchMethodError` on `GradleConnection(…)`, whether or not they
   * ever wanted failure advice. Adding a property only adds methods.
   */
  var failureAdvice: ((String) -> String?)? = null

  companion object {
    /**
     * Wall-clock budget a Gradle invocation gets before it is cancelled.
     *
     * Was 300s, which a real cold render did not fit in: a single-preview render on a cold daemon
     * measured 309s, and two of two runs at the old default timed out at ~320s while the same
     * render with a longer budget finished. A timeout that the documented default cannot survive
     * teaches callers to distrust the tool rather than to pass `--timeout`.
     *
     * Those five minutes were never the *render*: the CLI drove `composePreviewRenderAll` at full
     * width and filtered the rows client-side, so asking for one preview rendered all 64 in the
     * module — 317s where 3s would do (issue #3730). The CLI now forwards the request as
     * `-PcomposePreview.idFilter` (see `PreviewRenderScope`), so this budget is back to being what
     * it was meant to be: headroom for a genuinely cold daemon, not cover for a 100× overshoot.
     */
    const val DEFAULT_TIMEOUT_SECONDS: Long = 600
  }

  private val connector =
    GradleConnector.newConnector().forProjectDirectory(projectDir).apply {
      // `forProjectDirectory` takes the distribution from *that* directory's
      // gradle/wrapper/gradle-wrapper.properties, and silently falls back to the Tooling API's own
      // default when there is none. A nested build that borrows its parent repository's wrapper
      // (issue #5031) has none of its own, so without this it would be driven by a Gradle the
      // repository never chose. Inherit the nearest ancestor's wrapper distribution instead, which
      // is exactly what `../gradlew` would have used.
      inheritedWrapperDistribution(projectDir)?.let { useDistribution(it) }
    }
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

  /** Resolve every Gradle project path to its configured directory without realizing tasks. */
  @JvmOverloads
  fun findGradleProjects(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): List<PreviewModule> =
    runBuildAction(DiscoverGradleProjectsAction(), timeoutSeconds = timeoutSeconds) ?: emptyList()

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

/**
 * The `distributionUrl` of the nearest **ancestor** wrapper of [projectDir], or null when
 * [projectDir] has a wrapper of its own (the Tooling API reads that one itself), when no ancestor
 * has one, or when the URL is unusable (missing, relative, unparseable).
 *
 * Only the ancestor case is interesting: a build root with its own wrapper is already handled by
 * `forProjectDirectory`, and overriding it here would change which Gradle drives it.
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
          // The Tooling API's `useDistribution(URI)` carries a URL and nothing else — there is
          // no way to hand it the `distributionSha256Sum` the wrapper would have verified. So
          // when the distribution is not already in the wrapper's cache, inheriting the URL
          // would mean downloading and executing it with the repository's integrity pin
          // dropped. Refuse instead: the Tooling API falls back to its own distribution, which
          // is at least not a checksum this build asked for and did not get.
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

/**
 * A wrapper `distributionUrl` as a URI, resolving a **relative** value against the directory
 * holding [props] the way the Gradle wrapper itself does (a locally vendored distribution is
 * normally written that way). Null when the value cannot be parsed.
 */
private fun wrapperDistributionUri(url: String, props: File): URI? = runCatching {
  val uri = URI(url)
  if (uri.isAbsolute) uri else props.parentFile.toURI().resolve(url)
}
  .getOrNull()

/**
 * True when [distribution] is already unpacked in the wrapper's own cache, which means the wrapper
 * downloaded it and — where a `distributionSha256Sum` was pinned — verified it. Reusing that copy
 * involves no download, so no unverified bytes reach this build.
 *
 * Matched by the cache's directory layout (`wrapper/dists/<name>/<hash>/…` with the `.ok` marker
 * Gradle writes after a successful unpack), not by recomputing Gradle's internal URL hash — the
 * layout is stable and public, the hash function is neither.
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

/**
 * A distribution URL safe to print: userinfo and query string removed.
 *
 * A private distribution can carry credentials in either — `https://user:token@host/…` or a signed
 * `?X-Amz-Signature=…` — and a warning goes to stderr, which in CI means the build log.
 */
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
