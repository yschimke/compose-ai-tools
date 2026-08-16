package ee.schimke.composeai.preview

/**
 * Marks a `@Preview` as tooling/helper UI rather than application UI.
 *
 * Helper previews are still rendered and published normally. [includeInA11y] controls whether the
 * accessibility pipeline audits their canned content. Set it to `false` for visual-only specimens
 * whose intentionally repetitive labels would otherwise produce findings about the helper itself.
 *
 * ```
 * @Preview
 * @PreviewHelper(includeInA11y = false)
 * @Composable
 * fun ColorSchemeSpecimenPreview() { /* ... */ }
 * ```
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class PreviewHelper(val includeInA11y: Boolean = true)
