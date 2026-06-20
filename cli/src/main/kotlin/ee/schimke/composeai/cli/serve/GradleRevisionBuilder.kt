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
      process.inputStream.bufferedReader().forEachLine { onLog("[gradle] $it") }
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        onLog("serve: gradle build timed out after ${timeoutSeconds}s")
        false
      } else {
        process.exitValue() == 0
      }
    } catch (e: Exception) {
      onLog("serve: gradle build failed to launch (${e.message})")
      false
    }
  }

  private companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 600L
  }
}
