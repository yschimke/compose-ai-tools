package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Dev-mode `uses:` index: that a preview is credited with the calls in **its own**
 * declaration and no other's, that one file is read once for the previews sharing it, and that
 * "could not index" never arrives dressed as "nothing matched".
 *
 * The parse itself is [UsageSourceParserTest]'s subject; what is under test here is the split — the
 * step that decides which of a section file's calls belong to which card.
 */
class PreviewUsageIndexTest {

  private val fetched = mutableListOf<String>()
  private val log = mutableListOf<String>()
  private var now = 0L

  /**
   * One section file with two previews in it, formatted the way ktfmt leaves a catalog section: a
   * blank line between top-level declarations, which is the separator the declaration scan reads.
   */
  private val buttons =
    """
    @file:CatalogGroup(name = "Buttons", section = "Actions")

    package sections

    @CatalogComponent(id = "Button/Filled")
    @Composable
    fun FilledButton() = Sticker {
      Spacer()
      Button(onClick = {}) { Text("Filled") }
    }

    @CatalogComponent(id = "Button/Tonal")
    @Composable
    fun TonalButton() = Sticker {
      FilledTonalButton(onClick = {}) { Icon(); Text("Tonal") }
    }
    """
      .trimIndent()

  /** The 1-based line of the first line containing [needle], as discovery would have stamped it. */
  private fun lineOf(needle: String): Int = buttons.lines().indexOfFirst { it.contains(needle) } + 1

  private val filled = lineOf("""Button(onClick = {}) { Text("Filled") }""")

  private val tonal = lineOf("""FilledTonalButton""")

  private fun location(bodyLine: Int) =
    PlaygroundSeedResolver.Location(
      repo = "yschimke/m3-catalog",
      ref = "main",
      module = ":catalog",
      sourceFile = "src/main/kotlin/sections/Buttons.kt",
      bodyLine = bodyLine,
    )

  private val locations = mapOf("Filled" to location(filled), "Tonal" to location(tonal))

  private fun index(
    locate: (String, String) -> PlaygroundSeedResolver.Location? = { _, id -> locations[id] },
    body: (String) -> ByteArray? = { buttons.toByteArray() },
    parser: () -> UsageSourceParser? = { UsageSourceParser.of { log += it } },
    maxBytes: Int = PreviewUsageIndex.DEFAULT_MAX_BYTES,
    maxFiles: Int = PreviewUsageIndex.DEFAULT_MAX_FILES,
    ttlSeconds: Long = PreviewUsageIndex.DEFAULT_TTL_SECONDS,
  ) =
    PreviewUsageIndex(
      locate = locate,
      fetch = {
        fetched += it
        body(it)
      },
      parser = parser,
      maxBytes = maxBytes,
      maxFiles = maxFiles,
      ttlSeconds = ttlSeconds,
      clock = { now },
      onLog = { log += it },
    )

  private val ids = listOf("Filled", "Tonal")

  /**
   * The point of the whole feature: neither preview's *name* says it lays out a `Spacer`, and the
   * grid's own filter can only ever match a name.
   */
  @Test
  fun `a preview is credited with the calls in its own declaration`() {
    val index = index()
    assertEquals(setOf("Filled"), index.match("m3", ids, "Spacer").ids)
    assertEquals(setOf("Tonal"), index.match("m3", ids, "FilledTonalButton").ids)
  }

  /** A call in the neighbouring declaration is not this preview's, blank-line separator and all. */
  @Test
  fun `a neighbour's calls do not leak across the declaration boundary`() {
    assertEquals(setOf("Tonal"), index().match("m3", ids, "Icon").ids)
  }

  /** Both previews share a wrapper, and both are credited with it. */
  @Test
  fun `a call both previews make matches both`() {
    assertEquals(setOf("Filled", "Tonal"), index().match("m3", ids, "Sticker").ids)
  }

  /**
   * Substring, case-insensitive: a filter box is for half-remembered names. `button` therefore
   * reaches `FilledTonalButton` as well as `Button`, which is the point rather than a rough edge —
   * "show me everything buttonish" is the question someone changing a button API is asking.
   */
  @Test
  fun `matching is a case-insensitive substring of the callee`() {
    assertEquals(setOf("Filled", "Tonal"), index().match("m3", ids, "button").ids)
    assertEquals(setOf("Tonal"), index().match("m3", ids, "FILLEDTONAL").ids)
  }

  /** A name nothing calls is an empty answer from an index that ran — not an unavailable one. */
  @Test
  fun `a name nothing calls matches nothing and stays available`() {
    val match = index().match("m3", ids, "SwipeToReveal")
    assertTrue(match.ids.isEmpty())
    assertTrue(match.available)
  }

  /** One fetch for the file, however many previews are declared in it, and again for the cache. */
  @Test
  fun `one source file is read once for every preview in it`() {
    val index = index()
    index.match("m3", ids, "Button")
    index.match("m3", ids, "Text")
    assertEquals(
      listOf(
        "https://raw.githubusercontent.com/yschimke/m3-catalog/main/" +
          "catalog/src/main/kotlin/sections/Buttons.kt"
      ),
      fetched,
    )
  }

  /**
   * Past the TTL the file is read again, so a republished catalog stops answering from the old one.
   */
  @Test
  fun `the index is rebuilt once its ttl lapses`() {
    val index = index(ttlSeconds = 60)
    index.match("m3", ids, "Button")
    now += 61_000
    index.match("m3", ids, "Button")
    assertEquals(2, fetched.size)
  }

  /**
   * A catalog republished under the same id with its preview list changed must not answer from the
   * previous publication's index, TTL or no TTL.
   */
  @Test
  fun `a changed preview list rebuilds the index inside the ttl`() {
    val index = index()
    index.match("m3", ids, "Button")
    index.match("m3", ids + "Elevated", "Button")
    assertEquals(2, fetched.size)
  }

  /** No parser sidecar: unavailable, and the caller can tell that from "nothing matched". */
  @Test
  fun `without the parser the index reports itself unavailable`() {
    val match = index(parser = { null }).match("m3", ids, "Button")
    assertFalse(match.available)
    assertTrue(match.ids.isEmpty())
    assertTrue(log.any { it.contains("usage-source-psi not staged") }, log.toString())
  }

  /** An uploaded bundle carries no source locations at all; same distinction, different cause. */
  @Test
  fun `without source locations the index reports itself unavailable`() {
    val match = index(locate = { _, _ -> null }).match("m3", ids, "Button")
    assertFalse(match.available)
    assertTrue(fetched.isEmpty(), "nothing to fetch when nothing is located")
  }

  /** A fetch that fails drops that file, and the previews in it, rather than the whole index. */
  @Test
  fun `a file that cannot be read drops out of the index`() {
    val match = index(body = { null }).match("m3", ids, "Button")
    assertTrue(match.ids.isEmpty())
    // Still `available`: the catalog IS indexable, this read failed. The count line says "0 of n",
    // which is the truth about what was found.
    assertTrue(match.available)
  }

  /** Over the byte cap the file is refused, and says so. */
  @Test
  fun `a file over the byte cap is refused`() {
    val match = index(maxBytes = 10).match("m3", ids, "Button")
    assertTrue(match.ids.isEmpty())
    assertTrue(log.any { it.contains("over the 10-byte index cap") }, log.toString())
  }

  /**
   * A preview with no body-line anchor cannot be placed inside the file, so it is left out — rather
   * than credited with every call in a file it shares with five other components.
   */
  @Test
  fun `a preview without a body line is left out rather than given the whole file`() {
    val match =
      index(locate = { _, _ -> location(filled).copy(bodyLine = null) }).match("m3", ids, "Button")
    assertTrue(match.ids.isEmpty())
    assertTrue(match.available)
  }

  /** The file cap is reported, because a partial index that looks complete answers "no" wrongly. */
  @Test
  fun `hitting the file cap is reported as truncation`() {
    val perPreview =
      mapOf(
        "Filled" to location(filled),
        "Tonal" to location(tonal).copy(sourceFile = "src/main/kotlin/sections/Other.kt"),
      )
    val match = index(locate = { _, id -> perPreview[id] }, maxFiles = 1).match("m3", ids, "Button")
    assertTrue(match.truncated)
    assertEquals(1, fetched.size)
  }

  /**
   * Two top-level declarations with **no blank line between them** — the case
   * `PlaygroundSeedResolver.declarationLines` deliberately gets wrong.
   *
   * Its blank-line rule cannot see the second declaration start, so it returns one range covering
   * both. That is the safe failure for seeding an editor buffer (hand over too much rather than cut
   * code in half) and the unsafe one here: under it both previews inherit both call sets and
   * `uses:Spacer` answers with the preview that never calls one. The parse reports real declaration
   * spans, so attribution no longer depends on how the file is formatted.
   */
  @Test
  fun `declarations with no blank line between them do not share their calls`() {
    val packed =
      """
      package sections

      @Composable
      fun A() = Sticker { Spacer() }
      @Composable
      fun B() = Sticker { Icon() }
      """
        .trimIndent()
    fun lineIn(needle: String) = packed.lines().indexOfFirst { it.contains(needle) } + 1
    val where =
      mapOf(
        "A" to location(lineIn("fun A()")).copy(sourceFile = "src/main/kotlin/sections/Packed.kt"),
        "B" to location(lineIn("fun B()")).copy(sourceFile = "src/main/kotlin/sections/Packed.kt"),
      )
    val index = index(locate = { _, id -> where[id] }, body = { packed.toByteArray() })
    assertEquals(setOf("A"), index.match("m3", listOf("A", "B"), "Spacer").ids)
    assertEquals(setOf("B"), index.match("m3", listOf("A", "B"), "Icon").ids)
  }

  /**
   * A body over the fetcher's cap comes back as a truncated prefix plus one byte, and a prefix
   * still parses — so accepting it would report the calls in the first 256 KiB as if they were the
   * file's. The cap here matches the fetcher's precisely so that read is refused instead.
   */
  @Test
  fun `a truncated read is refused rather than parsed as the whole file`() {
    assertEquals(
      PlaygroundSeedResolver.DEFAULT_MAX_BYTES,
      PreviewUsageIndex.DEFAULT_MAX_BYTES,
      "a larger cap here silently accepts the fetcher's truncated prefix",
    )
    // Exactly what `PlaygroundSeedResolver.httpFetch` hands back for an over-cap body.
    val truncated = ByteArray(PreviewUsageIndex.DEFAULT_MAX_BYTES + 1) { ' '.code.toByte() }
    val match = index(body = { truncated }).match("m3", ids, "Button")
    assertTrue(match.ids.isEmpty())
    assertTrue(log.any { it.contains("index cap") }, log.toString())
  }

  /** CRLF source must not walk the call offsets out of the declaration they belong to. */
  @Test
  fun `a CRLF file indexes the same as an LF one`() {
    val crlf = index(body = { buttons.replace("\n", "\r\n").toByteArray() })
    assertEquals(setOf("Filled"), crlf.match("m3", ids, "Spacer").ids)
    assertEquals(setOf("Tonal"), crlf.match("m3", ids, "Icon").ids)
  }
}
