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

/**
 * Warns when `@Preview` functions live in `src/debug/` on a module that applied Google's
 * `com.android.compose.screenshot` plugin. That source set is part of the main debug variant, so it
 * compiles against `debugImplementation` + `implementation` only — NOT
 * `screenshotTestImplementation`. Preview-only helpers (theme wrappers, fixture composables)
 * written against the `screenshotTest` dependency closure end up failing in two ways once dropped
 * into `src/debug/`:
 *
 * 1. Compile-time: `compileDebugKotlin` fails with `Unresolved reference` on the
 *    screenshotTest-only symbols.
 * 2. Render-time (when the file still compiles): `composePreviewDiscover` picks the previews up via
 *    the shared `build/tmp/kotlin-classes/debug/` output, but their dependency tail isn't on
 *    `composePreviewRender`'s classpath and the preview throws on `Class.forName` / first
 *    composition, producing an `.error.json` sidecar with no PNG.
 *
 * Both modes leave the user with a confusing failure pointing at our pipeline. This task surfaces
 * the source-set mismatch at discovery time so the fix ("move it to `src/screenshotTest/`") is
 * obvious.
 *
 * Match is by `@Preview` text occurrence — deliberately coarse. A false positive on a Kotlin
 * comment or string literal mentioning `@Preview` is cheap (one warn line); a false negative would
 * silently leak the confusing failure back through. Wired as a finalizer of
 * `composePreviewDiscover` so it never blocks discovery itself.
 */
abstract class CheckDebugPreviewsTask : DefaultTask() {

  @get:InputFiles
  @get:SkipWhenEmpty
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val debugSourceFiles: ConfigurableFileCollection

  /**
   * Project root path used to render module-relative source paths in the warning. Captured at
   * configuration time so the task action stays configuration-cache-safe (no `project` access at
   * execution).
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
