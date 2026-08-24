package ee.schimke.composeai.preview

/**
 * Synthetic `@CaptureGutter` / `@ScrollingPreview` stubs at their **real FQNs**, used by
 * `IncrementalDiscoveryTest` to exercise the gutter+scroll rejection without pulling
 * `:preview-annotations` (a Compose-adjacent artifact) onto the `:daemon:core` test classpath — the
 * same layering reason `TestPreview` is a stub rather than the real `@Preview`.
 *
 * `RUNTIME` retention so ClassGraph's annotation reader sees them. `@CaptureGutter` keeps the
 * ANNOTATION_CLASS target so the hoisted-onto-a-multi-preview case can be exercised.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class CaptureGutter(val all: Int = 0)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ScrollingPreview
