package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2.2 phase 2 — pins the daemon-side incremental rescan path.
 *
 * **Scan fixture.** [`TestPreviewFixtures`][ee.schimke.composeai.daemon.fixtures.TestPreviewFixtures]
 * carries two `@TestPreview`-annotated methods on the test classpath. The `@TestPreview`
 * annotation is the daemon-test-only stand-in for `androidx.compose.ui.tooling.preview.Preview` —
 * we can't depend on the real Compose tooling artefact here without inverting
 * [LAYERING.md](../../../../../../docs/daemon/LAYERING.md). The tests construct
 * [IncrementalDiscovery] with `knownPreviewAnnotationFqns = setOf("...TestPreview")` so the scan
 * recognises it.
 */
class IncrementalDiscoveryTest {

  private val testPreviewFqn = "ee.schimke.composeai.daemon.fixtures.TestPreview"

  /** The `:daemon:core` test runtime classpath — what Gradle hands the JVM. */
  private val testClasspath: List<Path> =
    System.getProperty("java.class.path")
      .split(File.pathSeparator)
      .filter { it.isNotBlank() }
      .map { Path.of(it) }

  private val discovery =
    IncrementalDiscovery(
      classpath = testClasspath,
      knownPreviewAnnotationFqns = setOf(testPreviewFqn),
    )

  // -----------------------------------------------------------------------
  // cheapPrefilter
  // -----------------------------------------------------------------------

  @Test
  fun `cheapPrefilter trips on text containing direct @Preview`() {
    val file = Files.createTempFile("filter-direct", ".kt")
    Files.writeString(
      file,
      """
      package com.example

      @TestPreview
      fun Foo() {}
      """
        .trimIndent(),
    )
    try {
      assertTrue(discovery.cheapPrefilter(file, PreviewIndex.empty()))
    } finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `cheapPrefilter trips on text containing fully-qualified @Preview`() {
    val file = Files.createTempFile("filter-fqn", ".kt")
    Files.writeString(
      file,
      """
      package com.example

      @ee.schimke.composeai.daemon.fixtures.TestPreview
      fun Foo() {}
      """
        .trimIndent(),
    )
    try {
      assertTrue(discovery.cheapPrefilter(file, PreviewIndex.empty()))
    } finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `cheapPrefilter returns false on plain text with no preview annotation and no index hit`() {
    val file = Files.createTempFile("filter-plain", ".kt")
    Files.writeString(
      file,
      """
      package com.example

      class Foo {
        fun bar() = 42
      }
      """
        .trimIndent(),
    )
    try {
      assertFalse(discovery.cheapPrefilter(file, PreviewIndex.empty()))
    } finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `cheapPrefilter trips when file is currently in index even without text match`() {
    val file = Files.createTempFile("filter-deleted-preview", ".kt")
    // A file that USED to have a preview, now doesn't (deletion case). The index still has the
    // preview anchored to this path; cheap pre-filter must still fire so the diff path can pick up
    // the removal.
    Files.writeString(file, "// nothing to see here\n")
    val index =
      PreviewIndex.fromMap(
        path = null,
        byId =
          mapOf(
            "Foo" to
              PreviewInfoDto(
                id = "Foo",
                className = "com.example.FooKt",
                methodName = "Foo",
                sourceFile = file.toString(),
              )
          ),
      )
    try {
      assertTrue(discovery.cheapPrefilter(file, index))
    } finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `cheapPrefilter returns true on I-O failure (fail-safe)`() {
    val nonExistent = Path.of("/nonexistent/probably-not-here-${System.nanoTime()}.kt")
    // I/O failure path → fail-safe true (so a transient read error can't drop a real edit).
    assertTrue(discovery.cheapPrefilter(nonExistent, PreviewIndex.empty()))
  }

  // -----------------------------------------------------------------------
  // scanForFile
  // -----------------------------------------------------------------------

  @Test
  fun `scanForFile happy path returns the two TestPreview methods on the fixture class`() {
    // The compiled `.class` lives in this test JVM's classpath; its bytecode `SourceFile`
    // attribute is `TestPreview.kt`. We hand a synthetic absolute path with that basename so the
    // scan's basename-match in collectPreviews trips.
    val syntheticKt = Path.of(System.getProperty("java.io.tmpdir"), "TestPreview.kt")
    val results = discovery.scanForFile(syntheticKt)
    assertNotNull(results)
    val ids = results.map { it.id }.toSet()
    assertTrue(
      "scan should pick up firstPreview; got $ids",
      ids.any { it.endsWith(".firstPreview_first") },
    )
    assertTrue(
      "scan should pick up secondPreview; got $ids",
      ids.any { it.endsWith(".secondPreview_alpha") },
    )
    val first = results.first { it.id.endsWith(".firstPreview_first") }
    assertEquals(
      "ee.schimke.composeai.daemon.fixtures.TestPreviewFixtures",
      first.className,
    )
    assertEquals("firstPreview", first.methodName)
    assertEquals("first", first.displayName)
  }

  @Test
  fun `scanForFile returns emptySet when no class on classpath sources to the saved file`() {
    val syntheticKt = Path.of(System.getProperty("java.io.tmpdir"), "DefinitelyNotAFixture.kt")
    val results = discovery.scanForFile(syntheticKt)
    assertEquals(emptySet<PreviewInfoDto>(), results)
  }

  @Test
  fun `scanForFile returns emptySet on a synthetic broken classpath without throwing`() {
    val brokenDiscovery =
      IncrementalDiscovery(
        classpath = listOf(Path.of("/nonexistent/path-${System.nanoTime()}")),
        knownPreviewAnnotationFqns = setOf(testPreviewFqn),
      )
    val syntheticKt = Path.of(System.getProperty("java.io.tmpdir"), "TestPreview.kt")
    val results = brokenDiscovery.scanForFile(syntheticKt)
    assertEquals(emptySet<PreviewInfoDto>(), results)
  }
}
