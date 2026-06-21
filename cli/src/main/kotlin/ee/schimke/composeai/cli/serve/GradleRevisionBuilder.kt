package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.cli.PreviewResultBuilder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Production [RevisionBuilder]: runs the checkout's own Gradle wrapper to discover previews and
 * start the daemon for one module, then reads the resulting `daemon-launch.json` + `previews.json`.
 *
 * Each worktree is a full checkout, so its `./gradlew` builds that revision in isolation; the
 * daemon renders on demand from the descriptor, so we only need discovery (for the preview menu) +
 * daemon start (for the descriptor) here, not a full render.
 */
class GradleRevisionBuilder(
  private val extraArgs: List<String> = emptyList(),
  private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
  private val onLog: (String) -> Unit = {},
) : RevisionBuilder {

  override fun build(worktreeDir: File, module: ServeModuleRef): BuiltRevision? {
    // SECURITY (RCE): this runs the *checked-out revision's* own `./gradlew` (and its build
    // scripts), so a client that can name a resolvable revision via `?session=<rev>` in project
    // mode
    // gets arbitrary code execution as the server user. Fail closed until project mode gates which
    // revisions may be built (e.g. an allowlist of trusted refs) and isolates the build.
    TODO("secure this")
    val moduleDir = File(worktreeDir, module.relativePath)
    val gradlew = File(worktreeDir, "gradlew")
    if (!gradlew.canExecute()) {
      onLog("serve: no executable gradlew in ${worktreeDir.absolutePath}")
      return null
    }
    val tasks =
      listOf(
        ":${module.gradlePath}:composePreviewDiscover",
        ":${module.gradlePath}:composePreviewDaemonStart",
      )
    if (!runGradle(worktreeDir, gradlew, tasks + extraArgs)) return null

    val descriptor = File(moduleDir, "build/compose-previews/daemon-launch.json")
    if (!descriptor.isFile) {
      onLog("serve: missing daemon-launch.json at ${descriptor.absolutePath}")
      return null
    }
    val manifest = PreviewResultBuilder.readManifest(PreviewModule(module.gradlePath, moduleDir))
    val previews =
      manifest?.previews?.map {
        ServePreview(id = it.id, label = it.functionName.ifBlank { it.id })
      } ?: emptyList()
    if (previews.isEmpty()) {
      onLog("serve: no previews discovered for ${module.gradlePath}")
      return null
    }
    return BuiltRevision(moduleDir = moduleDir, descriptor = descriptor, previews = previews)
  }

  private fun runGradle(worktreeDir: File, gradlew: File, args: List<String>): Boolean {
    return try {
      val process =
        ProcessBuilder(listOf(gradlew.absolutePath) + args)
          .directory(worktreeDir)
          .redirectErrorStream(true)
          .start()
      // Drain output on a daemon thread: a stalled build that never closes stdout (dependency
      // resolution, a held lock) would otherwise block here forever and the waitFor timeout would
      // never be reached — hanging the request while it holds the registry build lock.
      // destroyForcibly() on timeout closes the stream, ending this thread.
      val drain =
        Thread { process.inputStream.bufferedReader().forEachLine { onLog("[gradle] $it") } }
          .apply {
            isDaemon = true
            start()
          }
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        onLog("serve: gradle build timed out after ${timeoutSeconds}s")
        return false
      }
      drain.join(DRAIN_FLUSH_MILLIS) // let any buffered tail flush to the log
      process.exitValue() == 0
    } catch (e: Exception) {
      onLog("serve: gradle build failed to launch (${e.message})")
      false
    }
  }

  private companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 600L
    const val DRAIN_FLUSH_MILLIS = 2000L
  }
}
