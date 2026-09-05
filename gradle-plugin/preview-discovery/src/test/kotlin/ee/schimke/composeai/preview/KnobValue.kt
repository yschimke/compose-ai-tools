package ee.schimke.composeai.preview

/**
 * A test-local stand-in for the published `@KnobValue`, declared in its real package rather than
 * depended on.
 *
 * `:preview-discovery` is a separate composite build and cannot reach `:preview-annotations` — but
 * it also does not need to, and the substitution is the point rather than a workaround: discovery
 * matches this annotation by its **descriptor**, read out of the class file, and never by the type.
 * A fixture carrying an identically-named annotation therefore exercises exactly the production
 * path, and proves the two are coupled by name alone.
 *
 * Keep the package and the name in step with `api/preview-annotations/.../preview/KnobValue.kt`;
 * the descriptor is written down once, in [ee.schimke.composeai.discovery.PreviewKnobDefaults].
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class KnobValue(val value: String)
