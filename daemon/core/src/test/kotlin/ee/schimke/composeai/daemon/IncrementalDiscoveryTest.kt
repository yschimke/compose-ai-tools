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
 * **Scan fixture.**
 * [`TestPreviewFixtures`][ee.schimke.composeai.daemon.fixtures.TestPreviewFixtures] carries two
 * `@TestPreview`-annotated methods on the test classpath. The `@TestPreview` annotation is the
 * daemon-test-only stand-in for `androidx.compose.ui.tooling.preview.Preview` — we can't depend on
 * the real Compose tooling artefact here without inverting
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
    assertEquals("ee.schimke.composeai.daemon.fixtures.TestPreviewFixtures", first.className)
    assertEquals("firstPreview", first.methodName)
    assertEquals("first", first.displayName)
  }

  @Test
  fun `scanForFile drops a function that combines @CaptureGutter with @ScrollingPreview`() {
    // The authoritative pass (PreviewDiscovery) rejects that combination; the incremental scan must
    // too, or a source edit re-adds the rejected preview to the index (issue #4467 / #4488 review).
    val syntheticKt = Path.of(System.getProperty("java.io.tmpdir"), "GutterScrollFixtures.kt")
    // Capture stderr so the rejection diagnostic (which the full pass emits as a warning) can be
    // asserted — the incremental path must name the function it drops, not remove it silently.
    val savedErr = System.err
    val captured = java.io.ByteArrayOutputStream()
    val results =
      try {
        System.setErr(java.io.PrintStream(captured, true, "UTF-8"))
        discovery.scanForFile(syntheticKt)
      } finally {
        System.setErr(savedErr)
      }
    val diagnostics = captured.toString("UTF-8")
    val methods = results.map { it.methodName }.toSet()

    // Both contradictory combinations are dropped — the direct one and the one whose gutter is
    // hoisted onto a multi-preview annotation, which the guard's meta-closure walk has to catch.
    assertFalse(
      "the direct gutter+scroll combination must be skipped; got $methods",
      "gutteredScrollingPreview" in methods,
    )
    assertFalse(
      "the hoisted gutter+scroll combination must be skipped; got $methods",
      "hoistedGutterScrollingPreview" in methods,
    )
    // Either annotation on its own still surfaces.
    assertTrue("gutter-only must survive; got $methods", "gutterOnlyPreview" in methods)
    assertTrue("scroll-only must survive; got $methods", "scrollOnlyPreview" in methods)
    // An all-zero gutter is equivalent to no annotation, so scroll + zero-gutter is NOT the
    // forbidden combination — it must survive, matching the authoritative pass rather than being
    // dropped on the annotation's bare presence.
    assertTrue(
      "zero-gutter + scroll must survive; got $methods",
      "zeroGutterScrollingPreview" in methods,
    )
    // The drop is announced, not silent: the diagnostic names the rejected function(s).
    assertTrue(
      "rejection must be reported to stderr; got: $diagnostics",
      "gutteredScrollingPreview" in diagnostics &&
        "@CaptureGutter cannot be combined with @ScrollingPreview" in diagnostics,
    )
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

  @Test
  fun `scoped scan keeps dependency jars but excludes other class directories`() {
    val root = Files.createTempDirectory("incremental-roots")
    try {
      val target = Files.createDirectories(root.resolve("target/com/example"))
      val targetRoot = target.parent.parent
      val other = Files.createDirectories(root.resolve("other/com/example"))
      val otherRoot = other.parent.parent
      val dependencyJar = Files.createFile(root.resolve("annotations.jar"))
      val source = root.resolve("project/src/main/kotlin/com/example/Preview.kt")
      Files.createDirectories(source.parent)

      val scoped =
        IncrementalDiscovery(
          classpath = listOf(targetRoot, otherRoot, dependencyJar),
          knownPreviewAnnotationFqns = setOf(testPreviewFqn),
        )

      assertEquals(listOf(targetRoot, dependencyJar), scoped.scanRootsForFile(source))
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
