package ee.schimke.composeai.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test

/**
 * The Android `composePreviewRender` task — a Robolectric [Test] that renders `@Preview`s inside
 * the test JVM — subtyped only so it can carry the same `--preview` / `--preview-id` /
 * `--exclude-preview-id` / `--exclude-preview-row` command-line options the desktop
 * [RenderPreviewsTask] exposes (issues #2066 / #2966 / #2977). A plain `Test` can't declare
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
}
