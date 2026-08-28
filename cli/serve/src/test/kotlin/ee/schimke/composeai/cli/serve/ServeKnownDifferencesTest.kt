package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem

/**
 * The host **carries** the known-difference document; it does not judge it.
 *
 * So the tests below are not about the contract — that has its own conformance suite, in
 * JavaScript, which the browser and the offline driver run the same implementation against. They
 * are about the three reader obligations §4 names and no lexical rule inside the engine can
 * discharge: refuse by size before allocating, resolve containment rather than assume it, and open
 * the exact spelling that was asked for.
 *
 * Each is a divergence between two consumers if it is got wrong, not merely a bug: a file this host
 * serves and a checkout cannot open, or bytes one record is hashed against and another record owns.
 */
class ServeKnownDifferencesTest {
  private fun bundle(): File =
    Files.createTempDirectory("known-differences").toFile().also { it.deleteOnExit() }

  private fun write(root: File, relative: String, text: String): File {
    val file = File(root, relative)
    file.parentFile.mkdirs()
    file.writeText(text)
    return file
  }

  private fun artifactPath(id: String, name: String) =
    "${ServeKnownDifferences.DIRECTORY}/${ServeKnownDifferences.ARTIFACT_DIRECTORY}/$id/$name"

  // -- the document --------------------------------------------------------------------------

  @Test
  fun `a catalog with no document publishes none`() {
    assertNull(ServeKnownDifferences.document(bundle(), FileSystem.SYSTEM))
  }

  @Test
  fun `the document is carried verbatim, malformed or not`() {
    // The point of the whole class: `document-unreadable` is a verdict the *engine* must be able to
    // reach, and it can only reach it if the bytes arrive intact. A host that parsed on the way out
    // would answer this case by dropping the document, which reads as "this catalog accepts
    // nothing" — a clean bill of health for a broken file.
    val root = bundle()
    val text = "{ not json at all"
    write(root, "${ServeKnownDifferences.DIRECTORY}/${ServeKnownDifferences.DOCUMENT_FILE}", text)
    val document = ServeKnownDifferences.document(root, FileSystem.SYSTEM)
    assertEquals(ServeKnownDifferences.Document.Text(text), document)
  }

  @Test
  fun `a document past the ceiling is refused rather than reported absent`() {
    val root = bundle()
    write(
      root,
      "${ServeKnownDifferences.DIRECTORY}/${ServeKnownDifferences.DOCUMENT_FILE}",
      "x".repeat(ServeKnownDifferences.MAX_DOCUMENT_BYTES + 1),
    )
    assertEquals(
      ServeKnownDifferences.Document.TooLarge,
      ServeKnownDifferences.document(root, FileSystem.SYSTEM),
    )
  }

  @Test
  fun `a document exactly at the ceiling is legal`() {
    // Inclusive at both ends, like every other cap in this contract. A `>=` check would refuse what
    // the engine calls legal, and the two consumers would disagree about the case in between.
    val root = bundle()
    write(
      root,
      "${ServeKnownDifferences.DIRECTORY}/${ServeKnownDifferences.DOCUMENT_FILE}",
      "x".repeat(ServeKnownDifferences.MAX_DOCUMENT_BYTES),
    )
    assertTrue(
      ServeKnownDifferences.document(root, FileSystem.SYSTEM) is ServeKnownDifferences.Document.Text
    )
  }

  // -- the artifacts -------------------------------------------------------------------------

  @Test
  fun `an artifact is read from its acceptance's own directory`() {
    val root = bundle()
    write(root, artifactPath("glyph", "mask.png"), "not really a png")
    val artifact = ServeKnownDifferences.artifact(root, "glyph/mask.png", FileSystem.SYSTEM)
    assertTrue(artifact is ServeKnownDifferences.Artifact.Bytes)
    assertEquals(
      "not really a png",
      String(artifact.bytes),
    )
  }

  @Test
  fun `a nested path inside the acceptance is allowed`() {
    val root = bundle()
    write(root, artifactPath("glyph", "nested/mask.png"), "bytes")
    assertTrue(
      ServeKnownDifferences.artifact(root, "glyph/nested/mask.png", FileSystem.SYSTEM)
        is ServeKnownDifferences.Artifact.Bytes
    )
  }

  @Test
  fun `a missing artifact is unreadable, not contained`() {
    val root = bundle()
    write(root, artifactPath("glyph", "mask.png"), "bytes")
    assertEquals(
      ServeKnownDifferences.Artifact.Unreadable,
      ServeKnownDifferences.artifact(root, "glyph/absent.png", FileSystem.SYSTEM),
    )
  }

  @Test
  fun `a directory is unreadable rather than bytes`() {
    val root = bundle()
    File(root, artifactPath("glyph", "mask.png")).mkdirs()
    assertEquals(
      ServeKnownDifferences.Artifact.Unreadable,
      ServeKnownDifferences.artifact(root, "glyph/mask.png", FileSystem.SYSTEM),
    )
  }

  @Test
  fun `a traversal never reaches the filesystem`() {
    val root = bundle()
    write(root, "secret.txt", "not yours")
    for (path in
      listOf("glyph/../../secret.txt", "../secret.txt", "/etc/passwd", "glyph\\mask.png")) {
      assertEquals(
        ServeKnownDifferences.Artifact.NotContained,
        ServeKnownDifferences.artifact(root, path, FileSystem.SYSTEM),
        path,
      )
    }
  }

  @Test
  fun `a bare path with no acceptance directory is refused`() {
    // The `<id>` is the first segment and is what makes an artifact *someone's*. A path with no
    // directory names no record, so there is nothing to bound it to.
    val root = bundle()
    assertEquals(
      ServeKnownDifferences.Artifact.NotContained,
      ServeKnownDifferences.artifact(root, "mask.png", FileSystem.SYSTEM),
    )
  }

  @Test
  fun `a symlink into a sibling acceptance is not contained`() {
    // The bound that matters, and the one a root-only check misses. A link from `a/` into `b/`
    // resolves comfortably inside the artifact root while letting record `a` read bytes record `b`
    // owns — and `a`'s recorded hash is then checked against `b`'s file.
    val root = bundle()
    write(root, artifactPath("other", "mask.png"), "someone else's bytes")
    File(root, artifactPath("glyph", "")).mkdirs()
    val link = File(root, artifactPath("glyph", "mask.png")).toPath()
    val target = File(root, artifactPath("other", "mask.png")).toPath()
    try {
      Files.createSymbolicLink(link, target)
    } catch (_: UnsupportedOperationException) {
      return // A filesystem without symlinks cannot express the case; nothing to assert.
    }
    assertEquals(
      ServeKnownDifferences.Artifact.NotContained,
      ServeKnownDifferences.artifact(root, "glyph/mask.png", FileSystem.SYSTEM),
    )
  }

  @Test
  fun `an artifact past the ceiling is refused from its length`() {
    val root = bundle()
    write(
      root,
      artifactPath("glyph", "mask.png"),
      "x".repeat(ServeKnownDifferences.MAX_ARTIFACT_BYTES + 1),
    )
    assertEquals(
      ServeKnownDifferences.Artifact.TooLarge,
      ServeKnownDifferences.artifact(root, "glyph/mask.png", FileSystem.SYSTEM),
    )
  }

  @Test
  fun `a path segment a checkout cannot create is refused`() {
    // Portability, not tidiness. `CON.png` commits fine and evaluates fine on POSIX and cannot be
    // created under that name on Windows at all, so the offline engine would report
    // `artifact-unreadable` for a file this host served — the host-versus-checkout divergence the
    // grammar exists to close, from the host's side.
    val root = bundle()
    for (path in
      listOf("glyph/CON.png", "glyph/mask.png.", "CON/mask.png", "glyph/${"a".repeat(256)}.png")) {
      assertEquals(
        ServeKnownDifferences.Artifact.NotContained,
        ServeKnownDifferences.artifact(root, path, FileSystem.SYSTEM),
        path,
      )
    }
  }

  // -- the mirror ----------------------------------------------------------------------------

  @Test
  fun `the ceilings agree with the contract's reference implementation`() {
    // Two copies of a constant are fine while something fails when they disagree. These are
    // versioned with the schema and are deliberately not per-catalog settings, so a host that
    // guessed one would refuse what the engine calls legal — and the disagreement would surface as
    // a record that evaluates on one consumer and 413s on the other.
    val source =
      File(repoRoot(), "scripts/design-artifacts/known-differences.mjs").let {
        if (it.exists()) it else File("scripts/design-artifacts/known-differences.mjs")
      }
    assertTrue(source.exists(), "the reference implementation moved: ${source.absolutePath}")
    val text = source.readText()
    val documentBytes =
      Regex("maxDocumentBytes:\\s*([0-9 *_]+),").find(text)?.groupValues?.get(1)?.let(::evaluate)
    val artifactBytes =
      Regex("maxArtifactBytes:\\s*([0-9 *_]+),").find(text)?.groupValues?.get(1)?.let(::evaluate)
    val acceptances =
      Regex("maxAcceptances:\\s*([0-9 *_]+),").find(text)?.groupValues?.get(1)?.let(::evaluate)
    assertEquals(ServeKnownDifferences.MAX_DOCUMENT_BYTES.toLong(), documentBytes)
    assertEquals(ServeKnownDifferences.MAX_ARTIFACT_BYTES.toLong(), artifactBytes)
    // The schema token travels with them, for the same reason: the staging path refuses to fetch
    // for a document declaring anything else, so a host spelling it differently would fetch nothing
    // for every catalog rather than everything for one.
    val schema =
      Regex("KNOWN_DIFFERENCES_SCHEMA\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    assertEquals(ServeKnownDifferences.SCHEMA, schema)
    // Mirrored for the staging path's fetch list — a cap the host reads further than would fetch
    // artifacts belonging to records the engine rejects the whole document for.
    assertEquals(ServeKnownDifferences.MAX_ACCEPTANCES.toLong(), acceptances)
  }

  /**
   * `1024 * 1024` and `8 * 1024 * 1024` as written in the JavaScript — products of integers only.
   */
  private fun evaluate(expression: String): Long =
    expression.replace("_", "").split("*").map { it.trim().toLong() }.reduce(Long::times)
}
