package ee.schimke.composeai.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test

/**
 * The Android `composePreviewRender` task — a Robolectric [Test] that renders `@Preview`s inside
 * the test JVM — subtyped only so it can carry the same `--preview` / `--preview-id` /
 * `--exclude-preview-id` / `--exclude-preview-row` / `--permutations` command-line options the
 * desktop [RenderPreviewsTask] exposes (issues #2066 / #2966 / #2977). A plain `Test` can't declare
 * `@Option`s, and before #2977 those options (and their `composePreview.filter` / `.idFilter` /
 * `.idExclude` property conventions) reached only the desktop backend, so
 * `:app:composePreviewRender --preview Foo` was inert on an Android module.
 *
 * The three list properties are the single source of truth: [AndroidPreviewSupport] sets their
 * conventions from the matching Gradle properties, forwards them to the Robolectric render JVM as
 * the `composeai.preview.*` system properties `PreviewFilter` reads, and gates the build cache off
 * any non-empty filter (a filtered render writes a partial `renders/` set — see the desktop task's
 * identical `cacheIf` reasoning). Everything else about the task is stock `Test` behaviour.
 */
abstract class RobolectricRenderTask : Test() {

  /**
   * `--preview` name/glob patterns (repeatable). Empty (default) renders every preview. Convention
   * comes from `composePreview.filter`; the option overrides it. `@Input` so a filter change
   * re-renders.
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
   * `--exclude-preview-row` label patterns (repeatable). Convention: `composePreview.rowExclude`.
   *
   * The one axis the id patterns above can't express on either backend: the id filters run over
   * DISCOVERED entries, and a `@PreviewParameter` provider's rows don't exist until
   * `expandParameterProvider` enumerates them inside this render JVM. Matched against the row's
   * label — the token in `<stem>_<label>.png` — case-insensitively, and never allowed to empty a
   * preview's row set. Mirrors the desktop task's `previewRowExcludes`.
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

  /**
   * Extra render fan-outs. Currently `accessibility`, which adds dark, RTL, and 2x font-scale
   * siblings for every discovered Compose preview.
   */
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
 * Make a render [Test] task's own output locale-independent.
 *
 * Agent sandboxes and minimal CI images routinely run under `LC_CTYPE=POSIX`, which leaves the JVM
 * with `sun.jnu.encoding=ANSI_X3.4-1968` (US-ASCII). That breaks a render task in a way that looks
 * nothing like an encoding problem:
 * - `Test`'s **HTML** reporter creates one output directory *per test method*, and for these tasks
 *   a test method is a preview, whose display name comes from consumer source — `@Preview(name =
 *   "Play Store — 10 inch tablet")`. Gradle writes those directory names using the *daemon's*
 *   platform encoding, so any preview name containing a non-ASCII character (an em dash, an accent,
 *   CJK) fails report generation with "Malformed input or input contains unmappable characters".
 *   Gradle then prints one line per failing file **twice** — once in the failure summary and once
 *   in the cause list — so a handful of em-dashed preview names buries the actual render result
 *   under hundreds of lines. Disabling the HTML report removes the failure class outright, and
 *   costs nothing: this task's product is PNGs, and per-preview failures are already reported
 *   through the `.error.json` sidecars that `formatMissingPreviewsMessage` reads.
 * - [Test.setDefaultCharacterEncoding] fixes the forked render JVM's own streams. It cannot fix the
 *   report-writing above (that happens in the daemon), and since JDK 18 `sun.jnu.encoding` cannot
 *   be overridden with `-D` at all — hence disabling the report rather than trying to re-encode it.
 *
 * The **JUnit XML** report is deliberately left enabled: its files are named after the test *class*
 * (`RobolectricRenderTest_Shard0`), which is always ASCII, so it is unaffected and CI test-result
 * collection keeps working.
 *
 * Cost of turning HTML off, stated plainly: the `composePreviewRender-reports` CI artifact
 * (`.github/actions/apply/action.yml`) uploads `build/reports/tests/composePreviewRender/`
 * alongside `build/test-results/composePreviewRender/`, so that artifact loses its browsable HTML.
 * No diagnostic content is lost — the HTML report is *generated from* the JUnit XML, which still
 * ships with the full per-test stack traces — but triage from the artifact means reading XML
 * instead of opening `index.html`. That is the deliberate trade: a browsable report on the runs
 * that succeed, versus renders that fail outright on every machine with a non-UTF-8 locale.
 */
internal fun configureRenderTaskReporting(task: org.gradle.api.tasks.testing.Test) {
  task.defaultCharacterEncoding = "UTF-8"
  task.reports.html.required.set(false)
  task.addTestOutputListener(ComposerNoticeListener())
}

/**
 * The marker every `LinkBufferComposer` notice carries — the runtime flag's own field name. Matched
 * as a literal rather than referencing `LinkBufferComposer.FLAG_FIELD`: the plugin does not depend
 * on `:data-render-core` (it forwards `composeai.render.*` by name, see
 * [AndroidPreviewClasspath.buildSystemProperties]), and this keeps that direction of the dependency
 * graph unchanged for one string.
 */
private const val COMPOSER_NOTICE_MARKER = "isLinkBufferComposerEnabled"

/**
 * Promotes the render JVM's "which composer drew this?" notice onto the build log.
 *
 * Needed because of an asymmetry between the lanes. The desktop renderer is a forked process whose
 * stderr passes straight through, so its notice lands in the build output. This lane renders inside
 * a Gradle `Test` worker, and Gradle *captures* a passing test's streams into the JUnit XML rather
 * than printing them — so the notice reached `build/test-results/…` and nowhere a person looks.
 *
 * That is fine for chatter, and wrong for this line specifically.
 * `composeai.render.linkBufferComposer=auto` trades the hard failure for a render on whatever
 * composer the runtime has, and the *only* thing keeping that from being the silently-ignored
 * opt-in `LinkBufferComposer` refuses to be is the notice being visible. A degrade nobody sees is
 * the failure mode, not the fallback.
 *
 * Forwards just the matching lines rather than setting `testLogging.showStandardStreams`, which
 * would carry every Robolectric warning in the batch along with them, and de-duplicates: the
 * announcement is once per Robolectric sandbox, so a sharded module would otherwise repeat one
 * identical line per shard.
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
