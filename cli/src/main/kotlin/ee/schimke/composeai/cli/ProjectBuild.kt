package ee.schimke.composeai.cli

import java.io.File

internal enum class ProjectBuildKind {
  GRADLE,
  BAZEL,
  BUCK,
}

internal data class ProjectBuild(val kind: ProjectBuildKind, val root: File)

/**
 * Prefer Gradle within a workspace; don't escape a nested Bazel workspace or Buck2 project into an
 * outer build.
 */
internal fun detectProjectBuild(start: File): ProjectBuild? {
  var directory: File? = start.canonicalFile
  var buildFileRoot: File? = null
  var wrapperRoot: File? = null
  while (directory != null) {
    val dir = directory
    if (hasSettingsFile(dir)) return ProjectBuild(ProjectBuildKind.GRADLE, dir)
    if (wrapperRoot == null && dir.resolve("gradlew").isFile) wrapperRoot = dir
    if (isBuckProjectMarker(dir)) {
      val root = detectBuckRoot(dir) ?: dir
      return if (wrapperRoot == root) ProjectBuild(ProjectBuildKind.GRADLE, root)
      else ProjectBuild(ProjectBuildKind.BUCK, root)
    }
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
    "buck" ->
      detectBuckRoot(directory)?.let { ProjectBuild(ProjectBuildKind.BUCK, it) }
        ?: error("No Buck2 project (.buckconfig or .buckroot) found at $directory")
    else ->
      error("Unknown --build-system '$requested'; expected auto, gradle, bazel, blaze or buck")
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
  // Before routing: on a configured Bazel workspace, routing would build the default target.
  if ("--help" in args || "-h" in args) {
    println(projectPreviewCommandHelp(command))
    return
  }
  val route =
    try {
      previewBuildRoute(args)
    } catch (e: Exception) {
      System.err.println("compose-preview: ${e.message}")
      kotlin.system.exitProcess(2)
    }
  if (route?.kind == ProjectBuildKind.BAZEL || route?.kind == ProjectBuildKind.BUCK) {
    val invocationDirectory = args.flagValue("--project")?.let(::File) ?: File(".")
    val routed = routedBuildArgs(route, args)
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

/**
 * [args] for the shared Bazel-family driver at [route]'s root. A Buck2 project, auto-detected or
 * not, is named `--build-system buck`: the driver reads that to collect through BXL.
 */
internal fun routedBuildArgs(route: ProjectBuild, args: List<String>): List<String> {
  val rooted = removeFlag(args, "--project") + listOf("--project", route.root.absolutePath)
  return if (route.kind == ProjectBuildKind.BUCK)
    removeFlag(rooted, "--build-system") + listOf("--build-system", "buck")
  else rooted
}

internal fun removeFlag(args: List<String>, flag: String): List<String> = buildList {
  var index = 0
  while (index < args.size) {
    val arg = args[index]
    when {
      arg == flag -> index += 2
      arg.startsWith("$flag=") -> index++
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

/** `compose-preview list|render|show --help` (#6115). */
internal fun projectPreviewCommandHelp(command: String): String =
  """
  compose-preview $command — ${
    when (command) {
      "list" -> "list the previews a Gradle module or Bazel/Buck target declares"
      "render" -> "render previews to PNG"
      else -> "render previews and show where their PNGs are"
    }
  }

  Usage: compose-preview $command [--module <:gradle:path> | --target <//bazel:label>] [options]

  Selection:  --id <exact id> | --function <exact function name> | --filter <substring>
              | --preview <reference>
  Output:     --json${if (command == "render") ", --output <file.png | dir/>" else ""}
  Build:      --project <dir>, --build-system gradle|bazel|buck, --config <bazel.json>

  Gradle projects run the Gradle plugin; Bazel and Buck workspaces run the same driver as
  `compose-preview bazel $command` (see `compose-preview bazel --help`).
  Run `compose-preview help --all` for every option.
  """
    .trimIndent()
