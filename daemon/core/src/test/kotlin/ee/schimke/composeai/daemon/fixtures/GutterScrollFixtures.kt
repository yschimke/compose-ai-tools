package ee.schimke.composeai.daemon.fixtures

import ee.schimke.composeai.preview.CaptureGutter
import ee.schimke.composeai.preview.ScrollingPreview

/**
 * Fixtures for `IncrementalDiscovery`'s rejection of `@CaptureGutter` + `@ScrollingPreview`. The
 * incremental scan must drop that combination the same way the authoritative `PreviewDiscovery`
 * pass does, or a source edit would re-add a rejected preview to the daemon's index.
 *
 * Four methods: the two contradictory combinations (one direct, one with the gutter hoisted onto a
 * multi-preview annotation) must be skipped; the two single-annotation controls must survive.
 */
class GutterScrollFixtures {
  @TestPreview(name = "guttered-scroll")
  @CaptureGutter(all = 4)
  @ScrollingPreview
  fun gutteredScrollingPreview() {}

  @HoistedGutterTestPreview @ScrollingPreview fun hoistedGutterScrollingPreview() {}

  @TestPreview(name = "gutter-only") @CaptureGutter(all = 4) fun gutterOnlyPreview() {}

  @TestPreview(name = "scroll-only") @ScrollingPreview fun scrollOnlyPreview() {}

  // An all-zero gutter is equivalent to no annotation (PreviewDiscovery drops it), so this is NOT
  // the forbidden combination — it must survive, exactly as the full pass keeps it.
  @TestPreview(name = "zero-gutter-scroll")
  @CaptureGutter(all = 0)
  @ScrollingPreview
  fun zeroGutterScrollingPreview() {}
}

/** A multi-preview annotation carrying a hoisted gutter — the meta-closure the guard must walk. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@TestPreview(name = "hoisted")
@CaptureGutter(all = 4)
annotation class HoistedGutterTestPreview
