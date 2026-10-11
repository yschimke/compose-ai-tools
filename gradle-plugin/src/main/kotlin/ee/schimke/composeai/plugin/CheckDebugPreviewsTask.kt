package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Warns about `@Preview` functions in `src/debug/` on modules using
 * `com.android.compose.screenshot`. That source set compiles without
 * `screenshotTestImplementation`, so helpers written against it either fail `compileDebugKotlin` or
 * get discovered but throw at render (an `.error.json` with no PNG). Surfacing it points at the
 * fix: move it to `src/screenshotTest/`.
 *
 * Matches `@Preview` text, deliberately coarse: a false positive costs one warning. Finalizes
 * `composePreviewDiscover`, so it never blocks discovery.
 */
@DisableCachingByDefault(because = "warning-only text scan; rerunning is cheaper than caching")
abstract class CheckDebugPreviewsTask : DefaultTask() {

  @get:InputFiles
  @get:SkipWhenEmpty
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val debugSourceFiles: ConfigurableFileCollection

  /**
   * Project root for module-relative paths in the warning, captured at configuration time for
   * configuration-cache safety.
   */
  @get:Input abstract val projectDirectory: Property<String>

  @TaskAction
  fun check() {
    val hits =
      debugSourceFiles.files
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .filter { it.readText().contains("@Preview") }
    if (hits.isEmpty()) return
    val projectRoot = java.io.File(projectDirectory.get())
    val rels = hits.map { it.relativeTo(projectRoot).path }
    logger.warn(
      buildString {
        append("composePreviewCheckDebugPreviews: found ")
        append(hits.size)
        append(" file(s) under `src/debug/` containing `@Preview` while the ")
        append("`com.android.compose.screenshot` plugin is applied:\n")
        rels.take(10).forEach { append("  - ").append(it).append('\n') }
        if (rels.size > 10) append("  (+").append(rels.size - 10).append(" more)\n")
        append(
          "  `src/debug/` is part of the main debug variant — it sees " +
            "`debugImplementation` deps but NOT `screenshotTestImplementation`. " +
            "Preview-only code is typically authored against the screenshotTest " +
            "closure and will fail to compile or render here. " +
            "Move these files to `src/screenshotTest/{java,kotlin}/...` instead."
        )
      }
    )
  }
}
