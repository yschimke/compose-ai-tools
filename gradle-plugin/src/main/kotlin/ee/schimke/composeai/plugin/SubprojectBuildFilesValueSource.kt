package ee.schimke.composeai.plugin

import java.io.File
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/**
 * IP-safe enumeration of every subproject's `build.gradle[.kts]` file. Drives the daemon's Tier-1
 * cheap-signal file set so an edit to `:lib/build.gradle.kts` flips `:app`'s dirty signal when
 * `:app` depends on `:lib` — without crossing `Project` boundaries.
 *
 * Implemented as a [ValueSource] (not a [org.gradle.api.services.BuildService]) because that's the
 * pattern Gradle documents for "external state read at configuration time": configuration cache
 * re-invokes [obtain] on each build and invalidates dependent CC entries only when the returned
 * list of files changes. File reads inside [obtain] aren't individually tracked as inputs — CC's
 * value-equality check is what catches both structural edits (new `include(":foo")` in
 * settings.gradle.kts) and content edits (rewriting `:lib/build.gradle.kts`), provided the returned
 * list's `equals` reflects each shape change.
 *
 * The list is sorted by absolute path so `equals` is stable across builds. The set of files doesn't
 * change on a content edit of one of those files, but the daemon's own runtime fingerprinting
 * hashes each file's contents on its side — the value source's job here is just to publish the list
 * of paths to track.
 *
 * The parser is intentionally heuristic — see [parseSettingsForSubprojectBuildFiles]. Custom
 * `project(":foo").projectDir = file("custom")` overrides aren't honoured (the BuildService it
 * replaced had the same trade-off), and we assume the conventional layout where `:a:b` lives at
 * `rootDir/a/b`.
 */
internal abstract class SubprojectBuildFilesValueSource :
  ValueSource<List<File>, SubprojectBuildFilesValueSource.Params> {

  interface Params : ValueSourceParameters {
    val rootDir: DirectoryProperty
  }

  override fun obtain(): List<File> {
    val rootDir = parameters.rootDir.asFile.get()
    return parseSettingsForSubprojectBuildFiles(rootDir).sortedBy { it.absolutePath }
  }

  companion object {
    /**
     * Locates `settings.gradle[.kts]` under [rootDir], parses its `include(":foo")` directives, and
     * returns each declared subproject's `build.gradle.kts` / `build.gradle` (whichever exists on
     * disk).
     *
     * Visible for unit tests so the parser can be exercised without a Gradle build.
     */
    internal fun parseSettingsForSubprojectBuildFiles(rootDir: File): List<File> {
      val settings =
        listOf("settings.gradle.kts", "settings.gradle")
          .map { File(rootDir, it) }
          .firstOrNull { it.isFile } ?: return emptyList()
      val raw = runCatching { settings.readText() }.getOrNull() ?: return emptyList()
      val text = stripGradleComments(raw)
      val out = LinkedHashSet<File>()
      val callRe = Regex("""\binclude\b\s*\(?\s*((?:["'][^"']+["']\s*,?\s*)+)""")
      val pathRe = Regex("""["']([^"']+)["']""")
      for (call in callRe.findAll(text)) {
        for (m in pathRe.findAll(call.groupValues[1])) {
          val raw2 = m.groupValues[1]
          val path = if (raw2.startsWith(":")) raw2 else ":$raw2"
          val dir = pathToDir(rootDir, path)
          listOf("build.gradle.kts", "build.gradle")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
            ?.let { out += it }
        }
      }
      return out.toList()
    }

    private fun pathToDir(rootDir: File, path: String): File {
      val parts = path.trim(':').split(':').filter { it.isNotEmpty() }
      if (parts.isEmpty()) return rootDir
      return File(rootDir, parts.joinToString(File.separator))
    }

    /**
     * Strips `// …` line comments and `/* … */` block comments. String literals aren't tracked: a
     * deliberately-commented sample inside a triple-quoted string is rare enough in real settings
     * scripts to ignore, and the worst case is a no-op.
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
