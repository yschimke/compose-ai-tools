package ee.schimke.composeai.daemon.fixtures

/**
 * Synthetic `@Preview`-shaped annotation used by [IncrementalDiscoveryTest] to exercise the
 * ClassGraph scan path without pulling Compose UI tooling onto the `:daemon:core` test classpath
 * (which would be a layering inversion — the daemon core deliberately doesn't depend on Compose).
 *
 * `RUNTIME` retention so ClassGraph's annotation reader sees it; `name` / `group` parameters mirror
 * the real `@Preview`'s `name = "..."` / `group = "..."` so the diff-tracked field extraction in
 * `IncrementalDiscovery.toDto` has something to read.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class TestPreview(val name: String = "", val group: String = "")

/**
 * Holder for two `@TestPreview`-annotated methods that share a source file. The
 * [IncrementalDiscoveryTest] scans against this class's compiled bytecode; the bytecode's
 * `SourceFile` attribute is `TestPreview.kt` (the file that holds the class declaration).
 */
class TestPreviewFixtures {
  @TestPreview(name = "first") fun firstPreview() {}

  @TestPreview(group = "alpha") fun secondPreview() {}

  fun notAPreview() {}
}
