package ee.schimke.composeai.cli

import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.StartEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Collections

/**
 * Handle for a subproject that applies `ee.schimke.composeai.preview`.
 *
 * [gradlePath] is the colon-separated project path **without** its leading
 * colon (e.g. `"app"`, `"auth:composables"`) — used to address Gradle tasks
 * like `":$gradlePath:renderAllPreviews"` and to identify the module in CLI
 * output / persisted state. [projectDir] is the actual filesystem directory
 * of that subproject, resolved via Gradle's Tooling API. Using it instead of
 * `projectRoot/$gradlePath` is what makes nested subprojects (`:foo:bar`) and
 * any custom `project.projectDir` override work correctly — see issue #157.
 */
data class PreviewModule(val gradlePath: String, val projectDir: File)

class GradleConnection(
    private val projectDir: File,
    private val verbose: Boolean,
    private val progress: Boolean = false,
) : AutoCloseable {
    private val connector = GradleConnector.newConnector()
        .forProjectDirectory(projectDir)
    private val connection = connector.connect()

    fun runTasks(vararg tasks: String, timeoutSeconds: Long = 300): Boolean {
        val tokenSource: CancellationTokenSource = GradleConnector.newCancellationTokenSource()
        val startTime = System.currentTimeMillis()
        val runningTasks = Collections.synchronizedSet(linkedSetOf<String>())

        // Ctrl+C otherwise kills the CLI without going through the cancellation
        // token — leaving the Gradle daemon still executing and any forked Test
        // worker (Robolectric, etc.) orphaned. Hook ensures clean cancellation.
        val shutdownHook = Thread {
            System.err.println("\nInterrupted — cancelling Gradle build...")
            tokenSource.cancel()
            try { connection.close() } catch (_: Exception) {}
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        val timer = java.util.Timer(true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    System.err.println("Build timed out after ${timeoutSeconds}s, cancelling...")
                    TerminalProgress.error()
                    tokenSource.cancel()
                }
            }, timeoutSeconds * 1000)

            // Heartbeat so the user can see what is still running (Robolectric
            // can take minutes on a cold start with no output). Opt-in via
            // --progress / --verbose so default CLI output stays quiet.
            if (progress) {
                val heartbeatMs = 15_000L
                schedule(object : java.util.TimerTask() {
                    override fun run() {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val running = synchronized(runningTasks) { runningTasks.toList() }
                        if (running.isNotEmpty()) {
                            System.err.println("  [${elapsed}s] running: ${running.joinToString(", ")}")
                        }
                    }
                }, heartbeatMs, heartbeatMs)
            }
        }

        TerminalProgress.indeterminate()
        var taskCount = 0
        var tasksFinished = 0

        // Capture stderr for error reporting when not in verbose mode
        val errorCapture = ByteArrayOutputStream()

        return try {
            val launcher = connection.newBuild()
                .forTasks(*tasks)
                .withCancellationToken(tokenSource.token())

            if (verbose) {
                launcher.setStandardOutput(System.err)
                launcher.setStandardError(System.err)
            } else {
                launcher.setStandardOutput(NullOutputStream)
                launcher.setStandardError(errorCapture)
            }

            val listenerTypes = if (verbose) {
                setOf(OperationType.TASK, OperationType.TEST)
            } else {
                setOf(OperationType.TASK)
            }

            launcher.addProgressListener({ event: ProgressEvent ->
                val desc = event.descriptor.name
                when (event) {
                    is StartEvent -> {
                        taskCount++
                        runningTasks.add(desc)
                    }
                    is FinishEvent -> {
                        runningTasks.remove(desc)
                        tasksFinished++
                        if (taskCount > 0) {
                            TerminalProgress.show((tasksFinished * 100) / taskCount)
                        }
                        if (progress && !verbose && (desc.contains("discoverPreviews") ||
                                desc.contains("renderPreviews") || desc.contains("renderAllPreviews"))
                        ) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000
                            System.err.println("  [${elapsed}s] $desc")
                        }
                    }
                    else -> {}
                }
            }, listenerTypes)

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
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) {}
        }
    }

    private fun printBuildFailure(e: org.gradle.tooling.BuildException, errorCapture: ByteArrayOutputStream) {
        // Extract the root cause message
        var cause: Throwable? = e
        val messages = mutableListOf<String>()
        while (cause != null) {
            cause.message?.let { msg ->
                if (msg.isNotBlank() && msg !in messages) messages.add(msg)
            }
            cause = cause.cause
        }

        // Show the captured stderr (Gradle's error output)
        val captured = errorCapture.toString().trim()
        if (captured.isNotEmpty()) {
            // Extract actionable lines from Gradle output
            val lines = captured.lines()
            val actionable = lines.filter { line ->
                line.contains("error:", ignoreCase = true) ||
                    line.contains("FAILURE:") ||
                    line.contains("What went wrong") ||
                    line.contains("not found") ||
                    line.startsWith("e: ") ||
                    line.startsWith("> ") ||
                    line.startsWith("* ")
            }
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

    /**
     * Fetch a Tooling API model registered by the applied plugin. Returns
     * `null` if the model isn't registered (plugin not applied, or version
     * predates the model) or if the Gradle connection fails — callers fold
     * both into "skip project-scope checks" rather than erroring.
     *
     * The plugin-side model FQN and the [modelClass] passed here must match;
     * see `ComposePreviewModel.kt` on both sides for the contract.
     */
    fun <R> runBuildAction(action: org.gradle.tooling.BuildAction<R>, timeoutSeconds: Long = 60): R? {
        val tokenSource: CancellationTokenSource = GradleConnector.newCancellationTokenSource()
        val timer = java.util.Timer(true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { tokenSource.cancel() }
            }, timeoutSeconds * 1000)
        }
        return try {
            connection.action(action)
                .withCancellationToken(tokenSource.token())
                .apply {
                    if (verbose) {
                        setStandardOutput(System.err)
                        setStandardError(System.err)
                    } else {
                        setStandardOutput(NullOutputStream)
                        setStandardError(NullOutputStream)
                    }
                }
                .run()
        } catch (e: org.gradle.tooling.GradleConnectionException) {
            if (verbose) System.err.println("Gradle connection failed: ${e.message}")
            null
        } catch (e: org.gradle.tooling.BuildException) {
            if (verbose) System.err.println("Build action failed: ${e.message}")
            null
        } finally {
            timer.cancel()
            tokenSource.cancel()
        }
    }

    /**
     * Fetch Gradle's `BuildEnvironment` model — exposes the daemon's Gradle
     * version and its `javaHome`. Doctor uses both to triage bug reports
     * where the forked test worker's JVM differs from the daemon's (#142).
     * Returns `null` on any tooling-API failure; callers fold into "skip".
     */
    fun buildEnvironment(): org.gradle.tooling.model.build.BuildEnvironment? {
        return try {
            connection.model(org.gradle.tooling.model.build.BuildEnvironment::class.java).get()
        } catch (e: Exception) {
            if (verbose) System.err.println("Could not query BuildEnvironment: ${e.message}")
            null
        }
    }

    /**
     * Find all subprojects that have a `discoverPreviews` task — these have the
     * compose-ai-tools plugin applied.
     *
     * Each entry carries both the Gradle path (used to build task specs like
     * `:foo:bar:renderAllPreviews`) and the resolved filesystem `projectDir`.
     * Nested subprojects (`:foo:bar`) have directory layouts like `foo/bar/`,
     * so substituting `:` for `/` doesn't always work — and even for standard
     * layouts a user can point `project.projectDir` anywhere. Reading it from
     * the Tooling API's [GradleProject.projectDirectory] is the only reliable
     * way to resolve manifests / PNGs on disk without replicating Gradle's
     * own project-layout logic.
     */
    fun findPreviewModules(): List<PreviewModule> {
        return try {
            val model = connection.model(org.gradle.tooling.model.GradleProject::class.java).get()
            val modules = mutableListOf<PreviewModule>()
            fun visit(project: org.gradle.tooling.model.GradleProject) {
                val hasPreviewTask = project.tasks.any { it.name == "discoverPreviews" }
                if (hasPreviewTask) {
                    val path = project.path.removePrefix(":")
                    if (path.isNotEmpty()) {
                        modules.add(PreviewModule(gradlePath = path, projectDir = project.projectDirectory))
                    }
                }
                for (child in project.children) visit(child)
            }
            visit(model)
            modules
        } catch (e: Exception) {
            if (verbose) System.err.println("Could not query project model: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolve a single module by its Gradle path (colon-separated, with or
     * without the leading `:`). Returns `null` when no project with that path
     * applies the plugin — callers fall back to a user-visible error rather
     * than silently building an empty task list against a dir that doesn't
     * exist.
     */
    fun findPreviewModule(gradlePath: String): PreviewModule? {
        val normalized = gradlePath.removePrefix(":")
        return findPreviewModules().firstOrNull { it.gradlePath == normalized }
    }

    override fun close() {
        connection.close()
    }
}

private object NullOutputStream : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
