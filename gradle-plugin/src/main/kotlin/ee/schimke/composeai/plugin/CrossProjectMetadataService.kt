package ee.schimke.composeai.plugin

import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Isolated-Projects-safe cross-project metadata source for the two features that #1546 had to drop
 * to clear Gradle's IP validation (issue #1549):
 *
 * 1. **`hasPreviewDependency` deep walk** (issue #241). The CMP-Android shape `:composeApp` →
 *    `:shared` declares preview tooling on `:shared`'s `commonMain.api`; the old implementation
 *    chased that via `rootProject.findProject(...)` + `evaluationDependsOn(...)`, both rejected
 *    under `-Dorg.gradle.unsafe.isolated-projects=true`.
 * 2. **Multi-module cheap-signal file walk** (daemon Tier-1 dirty). The old
 *    `collectCheapSignalFiles` walked `rootProject.allprojects` to fingerprint every subproject's
 *    `build.gradle.kts` — same IP violation.
 *
 * The service replaces both by **reading the build text directly off disk** rather than crossing
 * `Project` boundaries. Settings (`settings.gradle[.kts]`) gives us the project tree; each
 * subproject's `build.gradle[.kts]` is parsed for the preview-tooling coords listed in
 * [previewToolingCoordNames] and for declared `project(":foo")` references. Both happen entirely
 * inside this service's lazy initializer — Gradle never sees a cross-project API call, so IP
 * validation passes.
 *
 * ## Change tracking
 *
 * The service's parameters declare **every file the parser will read** through a Gradle-managed
 * [ConfigurableFileCollection], populated via a
 * [org.gradle.api.provider.ProviderFactory.fileContents]- backed provider chain. That gives CC
 * content-level invalidation on each tracked file:
 * - editing `settings.gradle.kts` to add/remove an `include(":foo")` re-derives the list (and
 *   adds/removes its build file from the tracked set);
 * - editing any subproject's `build.gradle[.kts]` content flips the file's content fingerprint;
 * - both shapes invalidate the CC entries of every project that queried the service during its own
 *   configuration, forcing a reconfigure with fresh data.
 *
 * This matters under Isolated Projects, where CC entries are per-project. Without an explicit input
 * declaration, `:app` could reuse a stale "no preview tooling reachable through `:shared`" result
 * after `:shared/build.gradle.kts` had been edited to add a tooling coord — the read inside the
 * lazy initializer would otherwise bypass CC fingerprinting entirely. Populating
 * [Params.trackedBuildScripts] is what closes that gap.
 *
 * Trade-offs vs. the pre-IP implementation:
 * - **Custom `project.projectDir` overrides** (`project(":foo").projectDir = file("custom")`) are
 *   not honoured — we assume the conventional layout where `:a:b` lives at `rootDir/a/b`. The few
 *   consumers that override this can keep using the `enforcePreviewToolingDependency = false`
 *   escape hatch.
 * - **Convention plugins that declare deps in `build-logic/`** rather than the consumer's own
 *   `build.gradle.kts` won't be detected. Convention plugins are an included build, which is out of
 *   scope here — included builds register their own metadata service if they apply the plugin.
 * - **String-literal Gradle DSL only.** A computed plugin id or coordinate (e.g. via a Kotlin val
 *   indirection) won't match. In practice consumer build files declare preview-tooling deps with
 *   literal strings or version-catalog accessors, both of which our regex catches.
 *
 * Registration is **idempotent across the build**: every applying project calls [registerIfAbsent],
 * the BuildService machinery returns the same instance keyed by [NAME], and the lazy initializer
 * runs exactly once per Gradle invocation. Auto-inject mode wires this through the init script's
 * `pluginManager.apply(...)` call without needing a settings-side hook — the plugin's `apply()`
 * method is what fronts the registration.
 */
internal abstract class CrossProjectMetadataService :
  BuildService<CrossProjectMetadataService.Params> {

  interface Params : BuildServiceParameters {
    val rootDir: org.gradle.api.file.DirectoryProperty

    /**
     * Every `settings.gradle[.kts]` + subproject `build.gradle[.kts]` the parser will read,
     * declared as a Gradle-managed file collection so configuration-cache fingerprints each file's
     * content. The actual reads happen inside [data]'s lazy via plain [File.readText]; this
     * collection exists purely to plug the parse into CC's input-tracking machinery.
     */
    val trackedBuildScripts: ConfigurableFileCollection
  }

  private val data: CrossProjectMetadata by lazy {
    // Touch `trackedBuildScripts` to make the CC fingerprint pick up the file set + contents
    // before the lazy parse executes. The actual file reads inside `CrossProjectMetadata.build`
    // run against the same paths — equivalent values, but the CC-tracked access is what closes
    // the IP-CC stale-data gap.
    parameters.trackedBuildScripts.files
    CrossProjectMetadata.build(parameters.rootDir.asFile.get())
  }

  fun hasPreviewToolingDeep(projectPath: String): Boolean = data.hasPreviewToolingDeep(projectPath)

  fun allBuildFiles(): List<File> = data.allBuildFiles()

  companion object {
    internal const val NAME = "composeAiPreviewCrossProjectMetadata"

    fun registerIfAbsent(
      project: Project
    ): org.gradle.api.provider.Provider<CrossProjectMetadataService> {
      val rootDir = project.rootDir
      // Tracked settings file. Wrap the absolute `File` as a `RegularFile` via
      // `fileProperty().fileValue(...)` (Layout.projectDirectory.file expects a *relative* path),
      // then feed it through `providers.fileContents(...)` so CC fingerprints the contents.
      val settingsFile =
        listOf("settings.gradle.kts", "settings.gradle")
          .map { File(rootDir, it) }
          .firstOrNull { it.isFile }
      val settingsContentsProvider =
        if (settingsFile != null) {
          val regular = project.objects.fileProperty().fileValue(settingsFile).get()
          // `.asText` returns a Provider<String>; CC tracks the underlying file.
          project.providers.fileContents(regular).asText.orElse("")
        } else {
          project.providers.provider { "" }
        }

      return project.gradle.sharedServices.registerIfAbsent(
        NAME,
        CrossProjectMetadataService::class.java,
      ) {
        parameters.rootDir.set(rootDir)
        if (settingsFile != null) {
          // Settings itself goes in the tracked set directly so CC fingerprints its content even
          // if no subprojects are declared yet.
          parameters.trackedBuildScripts.from(settingsFile)
        }
        // Subproject build-file list derived from the settings contents Provider. The chain
        // `Provider<String> -> Provider<List<File>>` keeps CC's input tracking intact: when
        // settings.gradle.kts changes (add / remove an include), the file list changes, the
        // tracked-set fingerprint changes, and CC invalidates dependent project entries.
        // Each file in the final collection is then fingerprinted by content on its own.
        parameters.trackedBuildScripts.from(
          settingsContentsProvider.map { contents ->
            CrossProjectMetadata.parseSettings(rootDir, contents)
              .values
              .flatMap { dir -> listOf(File(dir, "build.gradle.kts"), File(dir, "build.gradle")) }
              .filter { it.isFile }
          }
        )
      }
    }

    /** Returns the registered service, or `null` if it wasn't registered (test harnesses). */
    fun find(project: Project): CrossProjectMetadataService? =
      runCatching {
          val registration =
            project.gradle.sharedServices.registrations.findByName(NAME) ?: return@runCatching null
          registration.service.get() as? CrossProjectMetadataService
        }
        .getOrNull()
  }
}

/**
 * Pure parser + in-memory model. Split off from [CrossProjectMetadataService] so unit tests don't
 * need to spin up a Gradle build to exercise the regexes and the transitive closure.
 *
 * Snapshot semantics: the data is captured once at construction (via [build]) and never mutated.
 * The BuildService's [CrossProjectMetadataService.Params.trackedBuildScripts] is what makes CC
 * invalidate consumers when any tracked file's content changes; the next build re-instantiates the
 * service, the lazy parse runs again, and a fresh snapshot is built.
 */
internal class CrossProjectMetadata(
  private val projectDirsByPath: Map<String, File>,
  private val previewToolingPaths: Set<String>,
  private val projectDepsByPath: Map<String, List<String>>,
) {
  fun allBuildFiles(): List<File> =
    projectDirsByPath.values
      .flatMap { dir -> listOf(File(dir, "build.gradle.kts"), File(dir, "build.gradle")) }
      .filter { it.isFile }

  fun hasPreviewToolingDeep(projectPath: String): Boolean {
    val visited = HashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(projectPath)
    while (queue.isNotEmpty()) {
      val cur = queue.removeFirst()
      if (!visited.add(cur)) continue
      if (cur in previewToolingPaths) return true
      projectDepsByPath[cur]?.let { queue.addAll(it) }
    }
    return false
  }

  internal fun projectDirs(): Map<String, File> = projectDirsByPath

  internal fun directProjectDeps(path: String): List<String> =
    projectDepsByPath[path] ?: emptyList()

  internal fun previewToolingPathsForTest(): Set<String> = previewToolingPaths

  companion object {
    /**
     * Coordinate names whose presence in a subproject's `build.gradle[.kts]` text counts as "this
     * module hosts preview tooling." Mirrors `previewArtifactSignals` in [AndroidPreviewSupport] —
     * group is implicit in the coord name (we don't require text proximity between group and name,
     * just a hit on the name). False positives on this side downgrade to over-detection (the plugin
     * registers tasks where it might not have), which is strictly safer than under-detection.
     */
    internal val previewToolingCoordNames =
      listOf(
        "ui-tooling-preview-android",
        "components-ui-tooling-preview",
        "tiles-tooling-preview",
        "ui-tooling-preview",
      )

    fun build(rootDir: File): CrossProjectMetadata {
      val projectDirs = parseSettings(rootDir)
      val previewTooling = LinkedHashSet<String>()
      val projectDeps = LinkedHashMap<String, List<String>>()
      for ((path, dir) in projectDirs) {
        val buildFile = findBuildFile(dir) ?: continue
        val raw = runCatching { buildFile.readText() }.getOrNull() ?: continue
        val text = stripGradleComments(raw)
        if (declaresPreviewTooling(text)) previewTooling.add(path)
        val deps = parseProjectDeps(text)
        if (deps.isNotEmpty()) projectDeps[path] = deps
      }
      return CrossProjectMetadata(projectDirs, previewTooling, projectDeps)
    }

    internal fun parseSettings(rootDir: File): Map<String, File> {
      val settings = findSettingsFile(rootDir) ?: return mapOf(":" to rootDir)
      val raw = runCatching { settings.readText() }.getOrNull() ?: return mapOf(":" to rootDir)
      return parseSettings(rootDir, raw)
    }

    /**
     * Parser variant that takes the settings-file contents as a pre-read string — used by
     * [CrossProjectMetadataService.Companion.registerIfAbsent] so the read goes through
     * `providers.fileContents` (CC-tracked) rather than a bare [File.readText] inside the lazy data
     * initializer.
     */
    internal fun parseSettings(rootDir: File, settingsContents: String): Map<String, File> {
      val map = LinkedHashMap<String, File>()
      map[":"] = rootDir
      val text = stripGradleComments(settingsContents)
      // `include(":a", ":b")` / `include ":a", ":b"` / `include(":a")` — capture each quoted
      // path independently so trailing commas / line continuations don't matter.
      val callRe = Regex("""\binclude\b\s*\(?\s*((?:["'][^"']+["']\s*,?\s*)+)""")
      val pathRe = Regex("""["']([^"']+)["']""")
      for (call in callRe.findAll(text)) {
        for (m in pathRe.findAll(call.groupValues[1])) {
          val raw = m.groupValues[1]
          val path = if (raw.startsWith(":")) raw else ":$raw"
          map[path] = pathToDir(rootDir, path)
        }
      }
      return map
    }

    private fun pathToDir(rootDir: File, path: String): File {
      val parts = path.trim(':').split(':').filter { it.isNotEmpty() }
      if (parts.isEmpty()) return rootDir
      return File(rootDir, parts.joinToString(File.separator))
    }

    private fun findSettingsFile(rootDir: File): File? =
      listOf("settings.gradle.kts", "settings.gradle")
        .map { File(rootDir, it) }
        .firstOrNull { it.isFile }

    private fun findBuildFile(dir: File): File? =
      listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.isFile }

    internal fun declaresPreviewTooling(text: String): Boolean =
      previewToolingCoordNames.any { name ->
        // Match `:name:` (Maven coord inside a quoted string), or `"name"` standalone, or
        // a catalog alias whose accessor segment ends in the same words (rare but cheap to
        // include). The boundary-on-both-sides keeps `ui-tooling-preview` from matching
        // `ui-tooling-preview-android` and vice-versa, since the alternative branch covers
        // that explicitly.
        Regex("""(?<![A-Za-z0-9._-])${Regex.escape(name)}(?![A-Za-z0-9._-])""")
          .containsMatchIn(text)
      }

    internal fun parseProjectDeps(text: String): List<String> {
      val re = Regex("""\bproject\s*\(\s*(?:path\s*=\s*)?["']([^"']+)["']""")
      val out = LinkedHashSet<String>()
      for (m in re.findAll(text)) {
        val p = m.groupValues[1]
        if (!p.startsWith(":")) continue
        out.add(p)
      }
      return out.toList()
    }

    /**
     * Strips `// …` line comments and `/* … */` block comments — same scan as `AutoInject.kt`'s
     * `stripGradleComments`. String literals aren't tracked: a deliberately-commented sample inside
     * a triple-quoted string is rare enough in real build scripts to ignore, and the worst case is
     * a no-op (parsing what's effectively an example over-detects, not under-detects).
     */
    internal fun stripGradleComments(source: String): String {
      val sb = StringBuilder(source.length)
      var i = 0
      while (i < source.length) {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        if (c == '/' && next == '/') {
          val newline = source.indexOf('\n', i)
          if (newline < 0) break
          i = newline
        } else if (c == '/' && next == '*') {
          val end = source.indexOf("*/", i + 2)
          i = if (end < 0) source.length else end + 2
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }
  }
}
