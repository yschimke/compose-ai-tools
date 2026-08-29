package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeBuildHost
import ee.schimke.composeai.cli.serve.ServeCommandOptions
import ee.schimke.composeai.cli.serve.ServeDiscovery
import ee.schimke.composeai.cli.serve.ServeRunner
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdriver.GradleConnection
import java.io.File

/** Thin `compose-preview serve` adapter: server argv plus the CLI-owned Gradle build operations. */
class ServeCommand(args: List<String>, browseProject: Boolean = false) :
  Command(args), ServeBuildHost {

  internal val options =
    ServeCommandOptions(
      args = args,
      browseProject = browseProject,
      defaultTimeoutSeconds = GradleConnection.DEFAULT_TIMEOUT_SECONDS,
      previewMatcher = { id, exactId, filter, previewRef, className, functionName ->
        previewIdMatchesRequest(
          id,
          exactId = exactId,
          filter = filter,
          previewRef = previewRef,
          className = className,
          functionName = functionName,
        )
      },
    )

  override fun run() {
    if (options.helpRequested) {
      options.printUsage()
      return
    }
    ServeRunner(options, this).run()
  }

  override fun autoInjectInitScriptArgs(projectRoot: File): List<String> =
    autoInjectInitScriptArgs(args, projectRoot = projectRoot)

  override fun gradleProjectRoot(): File? = findProjectRoot()

  override fun gradleVariantArgs(): List<String> = variantGradleArgs()

  override fun gradleBuildArgs(extra: List<String>): List<String> = gradleArgsWithForce(extra)

  override fun gradleProjects(): List<PreviewModule> {
    var found = emptyList<PreviewModule>()
    withGradle { gradle -> found = gradle.findGradleProjects(timeoutSeconds) }
    return found
  }

  override fun runGradleTasks(
    vararg tasks: String,
    arguments: List<String>,
    silenceStdout: Boolean,
  ): Boolean {
    var ok = false
    withGradle(silenceStdout = silenceStdout) { gradle ->
      ok = runGradle(gradle, *tasks, arguments = arguments)
    }
    return ok
  }

  override fun discoverAndBuild(silenceStdout: Boolean): ServeDiscovery {
    val outcome = renderAllModules(silenceStdout = silenceStdout)
    return ServeDiscovery(buildOk = outcome.buildOk, manifests = outcome.manifests)
  }
}
