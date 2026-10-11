package ee.schimke.composeai.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test
import org.gradle.work.DisableCachingByDefault

/**
 * The Android `composePreviewRender` task: a Robolectric [Test], subtyped only to carry the same
 * `--preview` / `--preview-id` / `--exclude-preview-id` / `--exclude-preview-row` /
 * `--permutations` options as the desktop [RenderPreviewsTask] (#2066 / #2966 / #2977); a plain
 * `Test` can't declare `@Option`s.
 *
 * [AndroidPreviewSupport] sets the list properties' conventions from Gradle properties, forwards
 * them as `composeai.preview.*` system properties for `PreviewFilter`, and disables caching for
 * filtered runs. Otherwise stock `Test`.
 */
@DisableCachingByDefault(
  because = "Robolectric rendering runs a test JVM whose environment is not a declared input"
)
abstract class RobolectricRenderTask : Test() {

  /**
   * `--preview` name/glob patterns; empty renders everything. Convention: `composePreview.filter`.
   */
  @get:Input abstract val previewFilters: ListProperty<String>

  @Option(
    option = "preview",
    description =
      "Render only previews whose simple or fully-qualified name matches this pattern " +
        "(repeatable; supports '*'/'?' globs or a plain substring). No match fails the task. " +
        "Overrides -PcomposePreview.filter.",
  )
  fun setPreviewFilterOption(values: List<String>) {
    previewFilters.set(values)
  }

  /** `--preview-id` id/glob patterns (repeatable). Convention: `composePreview.idFilter`. */
  @get:Input abstract val previewIdFilters: ListProperty<String>

  @Option(
    option = "preview-id",
    description =
      "Render only previews whose discovered id matches this pattern (repeatable; supports " +
        "'*'/'?' globs or a plain substring). Selects individual members of a multipreview / " +
        "@PreviewParameter fan-out, which --preview cannot. Applied after --preview. No match " +
        "fails the task. Overrides -PcomposePreview.idFilter.",
  )
  fun setPreviewIdFilterOption(values: List<String>) {
    previewIdFilters.set(values)
  }

  /**
   * `--exclude-preview-id` id/glob patterns (repeatable). Convention: `composePreview.idExclude`.
   */
  @get:Input abstract val previewIdExcludes: ListProperty<String>

  @Option(
    option = "exclude-preview-id",
    description =
      "Skip previews whose discovered id matches this pattern (repeatable; '*'/'?' globs or a " +
        "plain substring), rendering everything else. The polarity a deferred catalog palette " +
        "needs. Applied after --preview-id. Excluding every preview fails the task. Overrides " +
        "-PcomposePreview.idExclude.",
  )
  fun setPreviewIdExcludeOption(values: List<String>) {
    previewIdExcludes.set(values)
  }

  /**
   * `--exclude-preview-row` label patterns. Convention: `composePreview.rowExclude`. Rows only
   * exist once the provider is enumerated in the render JVM, so id patterns can't name them.
   * Case-insensitive on the `<stem>_<label>.png` label, and never empties a preview's rows. Mirrors
   * the desktop task.
   */
  @get:Input abstract val previewRowExcludes: ListProperty<String>

  @Option(
    option = "exclude-preview-row",
    description =
      "Skip @PreviewParameter rows whose label matches this pattern (repeatable; '*'/'?' globs or " +
        "an exact label, case-insensitive), rendering the rest. Addresses one row of a " +
        "parameterized preview, which --exclude-preview-id cannot. Never empties a preview's rows. " +
        "Overrides -PcomposePreview.rowExclude.",
  )
  fun setPreviewRowExcludeOption(values: List<String>) {
    previewRowExcludes.set(values)
  }

  /** Extra render fan-outs; `accessibility` adds dark, RTL and 2x font-scale siblings. */
  @get:Input abstract val permutations: ListProperty<String>

  @Option(
    option = "permutations",
    description =
      "Render extra preview permutations. Currently supports 'accessibility' (dark, RTL, " +
        "fontscale-2x). Repeatable or comma-separated. Overrides -PcomposePreview.permutations.",
  )
  fun setPermutationsOption(values: List<String>) {
    permutations.set(values)
  }
}

/**
 * Makes a render [Test] task's output locale-independent.
 *
 * Under `LC_CTYPE=POSIX` the JVM's `sun.jnu.encoding` is US-ASCII. Gradle's HTML test report
 * creates a directory per test method (here, per preview name from consumer source), so any
 * non-ASCII preview name fails report generation, printing hundreds of duplicate lines that bury
 * the result. Disabling the HTML report removes that; failures are already reported via
 * `.error.json` sidecars. [Test.setDefaultCharacterEncoding] fixes the forked JVM's streams;
 * `sun.jnu.encoding` can't be overridden with `-D` since JDK 18.
 *
 * The JUnit XML report stays (files are named after ASCII class names), so CI collection works. The
 * cost: the `composePreviewRender-reports` artifact loses its browsable HTML, though the XML keeps
 * every stack trace.
 */
internal fun configureRenderTaskReporting(task: org.gradle.api.tasks.testing.Test) {
  task.defaultCharacterEncoding = "UTF-8"
  task.reports.html.required.set(false)
  task.addTestOutputListener(ComposerNoticeListener())
}

/**
 * Literal rather than `LinkBufferComposer.FLAG_FIELD`, to avoid a dependency on
 * `:data-render-core`.
 */
private const val COMPOSER_NOTICE_MARKER = "isLinkBufferComposerEnabled"

/**
 * Promotes the "which composer drew this?" notice to the build log. Desktop's forked stderr passes
 * through, but Gradle captures a passing test's output into the JUnit XML. With
 * `linkBufferComposer=auto`, a silent degrade is exactly the failure to avoid. Forwards only
 * matching lines (not all Robolectric output) and de-duplicates across shards.
 */
private class ComposerNoticeListener : org.gradle.api.tasks.testing.TestOutputListener {

  private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  override fun onOutput(
    descriptor: org.gradle.api.tasks.testing.TestDescriptor,
    event: org.gradle.api.tasks.testing.TestOutputEvent,
  ) {
    for (line in event.message.lineSequence()) {
      val trimmed = line.trim()
      if (!trimmed.contains(COMPOSER_NOTICE_MARKER)) continue
      if (seen.add(trimmed)) {
        org.gradle.api.logging.Logging.getLogger("compose-preview").lifecycle(trimmed)
      }
    }
  }
}
