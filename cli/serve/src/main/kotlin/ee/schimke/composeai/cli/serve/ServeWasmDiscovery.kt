package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File

// Wasm-app discovery for `--component-browser`.
//
// Lifted out of the server body during the ServeCommand split: neither declaration reads a single
// option, so neither belongs on a class that exists to hold the configured run. Being top-level is
// also what lets the test move across with them — otherwise a pure function of (root, modules)
// would have needed a whole `ServeOptions` constructed to call it.

internal data class AutomaticWasmProject(
  val gradlePath: String,
  val projectDir: File,
  val buildScript: String,
) {
  fun supports(module: PreviewModule): Boolean {
    if (projectDir.canonicalFile == module.projectDir.canonicalFile) return true
    val path = module.gradlePath.removePrefix(":")
    val colonPath = ":$path"
    val typeSafeAccessor =
      "projects." +
        path.split(':').joinToString(".") { segment ->
          segment.split('-', '_').let { words ->
            words.first() +
              words.drop(1).joinToString("") { word -> word.replaceFirstChar { it.uppercase() } }
          }
        }
    return buildScript.contains("project(\"$colonPath\")") ||
      buildScript.contains("project('$colonPath')") ||
      buildScript.contains(typeSafeAccessor) ||
      Regex("project\\s*\\(\\s*path\\s*=\\s*[\\\"']${Regex.escape(colonPath)}[\\\"']")
        .containsMatchIn(buildScript)
  }

  fun distribution(): File? =
    listOf(
        File(projectDir, "build/dist/wasmJs/productionExecutable"),
        File(projectDir, "build/wasmDist"),
        File(projectDir, "build/dist/wasmJs/developmentExecutable"),
      )
      .firstOrNull {
        File(it, "index.html").isFile && File(it, ServeDefaults.COMPONENT_PROTOCOL_MARKER).isFile
      }
}

internal fun discoverWasmProjects(
  root: File,
  gradleProjects: List<PreviewModule> = emptyList(),
): List<AutomaticWasmProject> =
  root
    .walkTopDown()
    .onEnter { it == root || (it.name != "build" && it.name != ".gradle" && it.name != ".git") }
    .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
    .mapNotNull { script ->
      val text = runCatching { script.readText() }.getOrNull() ?: return@mapNotNull null
      val dir = script.parentFile
      val hasDistribution =
        listOf(
            File(dir, "build/dist/wasmJs/productionExecutable/index.html"),
            File(dir, "build/wasmDist/index.html"),
            File(dir, "build/dist/wasmJs/developmentExecutable/index.html"),
          )
          .any { it.isFile }
      if (!hasDistribution && (!text.contains("wasmJs") || !text.contains("binaries.executable"))) {
        return@mapNotNull null
      }
      val configuredProject = gradleProjects.firstOrNull {
        it.projectDir.canonicalFile == dir.canonicalFile
      }
      val relative = dir.relativeTo(root).invariantSeparatorsPath
      val path =
        configuredProject?.gradlePath
          ?: relative.split('/').filter { it.isNotEmpty() }.joinToString(":")
      if (path.isEmpty()) null else AutomaticWasmProject(path, dir, text)
    }
    .toList()

/**
 * Construct the [ServeHttpServer], start it, advertise over mDNS (when [mdnsPreviewIds] is non-null
 * and the bind is exposed), print the banner, and block until shutdown. Shared by the module-backed
 * [run] and the module-less [runBundleServer]. [closeables] are extra resources (worktrees) closed
 * on shutdown; nulls are ignored.
 */
