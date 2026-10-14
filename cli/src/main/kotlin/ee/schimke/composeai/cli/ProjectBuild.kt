package ee.schimke.composeai.cli

import java.io.File

internal enum class ProjectBuildKind {
  GRADLE,
  BAZEL,
}

internal data class ProjectBuild(val kind: ProjectBuildKind, val root: File)

/** Prefer Gradle within a workspace; don't escape a nested Bazel workspace into an outer build. */
internal fun detectProjectBuild(start: File): ProjectBuild? {
  var directory: File? = start.canonicalFile
  var buildFileRoot: File? = null
  var wrapperRoot: File? = null
  while (directory != null) {
    val dir = directory
    if (hasSettingsFile(dir)) return ProjectBuild(ProjectBuildKind.GRADLE, dir)
    if (wrapperRoot == null && dir.resolve("gradlew").isFile) wrapperRoot = dir
    if (
      listOf("MODULE.bazel", "WORKSPACE.bazel", "WORKSPACE", ".blazerc").any {
        dir.resolve(it).isFile
      }
    ) {
      return if (wrapperRoot == dir) ProjectBuild(ProjectBuildKind.GRADLE, dir)
      else ProjectBuild(ProjectBuildKind.BAZEL, dir)
    }
    if (buildFileRoot == null && listOf("BUILD.bazel", "BUILD").any { dir.resolve(it).isFile })
      buildFileRoot = dir
    if (dir.resolve(".git").exists() || dir.resolve(".hg").exists()) {
      if (buildFileRoot != null) buildFileRoot = dir
      break
    }
    directory = dir.parentFile
  }
  return wrapperRoot?.let { ProjectBuild(ProjectBuildKind.GRADLE, it) }
    ?: buildFileRoot?.let { ProjectBuild(ProjectBuildKind.BAZEL, it) }
}

/** Pure routing seam: auto-detection never invokes a build tool or edits a BUILD file. */
internal fun previewBuildRoute(args: List<String>, start: File = File(".")): ProjectBuild? {
  val directory = args.flagValue("--project")?.let(::File) ?: start
  return when (val requested = args.flagValue("--build-system") ?: "auto") {
    "auto" -> detectProjectBuild(directory)
    "gradle" ->
      findGradleProjectRoot(directory.canonicalFile)?.let {
        ProjectBuild(ProjectBuildKind.GRADLE, it)
      } ?: error("No Gradle build found at $directory")
    "bazel",
    "blaze" ->
      detectBazelRoot(directory)?.let { ProjectBuild(ProjectBuildKind.BAZEL, it) }
        ?: error("No Bazel/Blaze workspace or BUILD file found at $directory")
    else -> error("Unknown --build-system '$requested'; expected auto, gradle, bazel or blaze")
  }
}

internal fun detectBazelRoot(start: File): File? {
  var directory: File? = start.canonicalFile
  var buildFileRoot: File? = null
  while (directory != null) {
    val dir = directory
    if (
      listOf("MODULE.bazel", "WORKSPACE.bazel", "WORKSPACE", ".blazerc").any {
        dir.resolve(it).isFile
      }
    )
      return dir
    if (buildFileRoot == null && listOf("BUILD.bazel", "BUILD").any { dir.resolve(it).isFile })
      buildFileRoot = dir
    if (dir.resolve(".git").exists() || dir.resolve(".hg").exists()) {
      return if (buildFileRoot != null) dir else null
    }
    directory = dir.parentFile
  }
  return buildFileRoot
}

internal fun runProjectPreviewCommand(command: String, args: List<String>) {
  val route =
    try {
      previewBuildRoute(args)
    } catch (e: Exception) {
      System.err.println("compose-preview: ${e.message}")
      kotlin.system.exitProcess(2)
    }
  if (route?.kind == ProjectBuildKind.BAZEL) {
    val invocationDirectory = args.flagValue("--project")?.let(::File) ?: File(".")
    val routed = removeProjectFlag(args) + listOf("--project", route.root.absolutePath)
    BazelCommand(
        listOf(command) + routed,
        normalSurface = true,
        invocationDirectory = invocationDirectory,
      )
      .run()
    return
  }
  when (command) {
    "list" -> ListCommand(args).run()
    "render" -> RenderCommand(args).run()
    "show" -> ShowCommand(args).run()
    else -> error("Unsupported preview command: $command")
  }
}

private fun removeProjectFlag(args: List<String>): List<String> = buildList {
  var index = 0
  while (index < args.size) {
    val arg = args[index]
    when {
      arg == "--project" -> index += 2
      arg.startsWith("--project=") -> index++
      else -> {
        add(arg)
        index++
      }
    }
  }
}

internal data class BazelBuildCommandChoice(val command: String, val reason: String)

/** Name of the environment override; a committed `buildCommand` in bazel.json still wins. */
internal const val BUILD_COMMAND_ENV = "COMPOSE_PREVIEW_BUILD_COMMAND"

/**
 * Picks the executable when neither `--build-command` nor a configured `buildCommand` names one.
 * Never runs a candidate; the reason is reported so a surprising choice is diagnosable.
 */
internal fun selectBazelBuildCommand(
  root: File,
  path: String? = System.getenv("PATH"),
  override: String? = System.getenv(BUILD_COMMAND_ENV),
): BazelBuildCommandChoice {
  if (!override.isNullOrBlank()) return BazelBuildCommandChoice(override, "\$$BUILD_COMMAND_ENV")
  if (root.resolve(".blazerc").isFile) return BazelBuildCommandChoice("blaze", ".blazerc")
  fun present(name: String): Boolean =
    path
      .orEmpty()
      .split(File.pathSeparator)
      .filter { it.isNotBlank() }
      .any { directory ->
        listOf(name, "$name.exe").any { binary ->
          File(directory, binary).let { it.isFile && it.canExecute() }
        }
      }
  // .bazelversion pins a version that only Bazelisk honours.
  if (root.resolve(".bazelversion").isFile && present("bazelisk"))
    return BazelBuildCommandChoice("bazelisk", ".bazelversion and bazelisk on PATH")
  return listOf("bazel", "bazelisk", "blaze").firstOrNull(::present)?.let {
    BazelBuildCommandChoice(it, "first of bazel, bazelisk, blaze on PATH")
  } ?: BazelBuildCommandChoice("bazel", "no candidate on PATH")
}
