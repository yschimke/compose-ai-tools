package ee.schimke.composeai.daemon.history

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * H10-read — `GitRefHistorySource` unit tests. Each test synthesises a temp git repo on disk, sets
 * up a `refs/heads/preview/...` ref with the documented storage convention (HISTORY.md §
 * "GitRefHistorySource"), and exercises the source through its public surface.
 *
 * **`git` binary required.** All tests `Assume.assumeTrue` on `git --version` so CI runners without
 * git skip cleanly. Production callers (the daemon mains) tolerate missing git the same way
 * [GitProvenance] does — silent degradation.
 */
class GitRefHistorySourceTest {

  private lateinit var repoRoot: Path
  private val warnLog = StringBuilder()
  private val warnCount = AtomicInteger(0)

  private val warnEmitter: (String) -> Unit = { msg ->
    synchronized(warnLog) {
      warnLog.append(msg).append('\n')
      warnCount.incrementAndGet()
    }
  }

  @Before
  fun setUp() {
    assumeTrue("git on PATH required for GitRefHistorySource tests", gitAvailable())
    repoRoot = Files.createTempDirectory("git-ref-test")
    runOk("git", "init", "-q", repoRoot.toString())
    runOk("git", "-C", repoRoot.toString(), "config", "user.email", "test@example.com")
    runOk("git", "-C", repoRoot.toString(), "config", "user.name", "Test")
    runOk("git", "-C", repoRoot.toString(), "config", "commit.gpgsign", "false")
    runOk("git", "-C", repoRoot.toString(), "config", "tag.gpgsign", "false")
    // Seed an initial commit so HEAD exists — we reuse it as the parent of preview/ refs.
    Files.writeString(repoRoot.resolve("README"), "init")
    runOk("git", "-C", repoRoot.toString(), "add", "README")
    runOk("git", "-C", repoRoot.toString(), "commit", "-q", "-m", "init")
  }

  @After
  fun tearDown() {
    if (this::repoRoot.isInitialized) repoRoot.toFile().deleteRecursively()
  }

  // -------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------

  @Test
  fun missing_ref_returns_empty_and_warns_once() {
    val source =
      GitRefHistorySource(
        repoRoot = repoRoot,
        ref = "refs/heads/preview/nonexistent",
        warnEmitter = warnEmitter,
      )
    val page = source.list(HistoryFilter())
    assertEquals(0, page.totalCount)
    assertTrue(page.entries.isEmpty())
    assertEquals(1, warnCount.get())
    assertTrue("warn must include ref name", warnLog.contains("refs/heads/preview/nonexistent"))
    assertTrue("warn must include hint", warnLog.contains("git fetch"))

    // Second call → no second warn (one-shot guard).
    val page2 = source.list(HistoryFilter())
    assertEquals(0, page2.totalCount)
    assertEquals(1, warnCount.get())

    // read() of a missing entry → null. Must not warn again either.
    val read = source.read("does-not-exist", includeBytes = false)
    assertNull(read)
    assertEquals(1, warnCount.get())
  }

  @Test
  fun empty_ref_returns_empty_no_warn() {
    // Create the ref pointing at an empty tree (no _index.jsonl, no preview dirs).
    val emptyTree = createEmptyTree()
    val emptyCommit = commitTree(emptyTree, parent = null, message = "empty")
    runOk("git", "-C", repoRoot.toString(), "update-ref", "refs/heads/preview/main", emptyCommit)

    val source =
      GitRefHistorySource(
        repoRoot = repoRoot,
        ref = "refs/heads/preview/main",
        warnEmitter = warnEmitter,
      )
    val page = source.list(HistoryFilter())
    assertEquals(0, page.totalCount)
    assertTrue(page.entries.isEmpty())
    assertEquals("ref exists → no warn", 0, warnCount.get())
  }

  @Test
  fun populated_ref_lists_and_reads_entries_with_git_source_kind() {
    // Build three sidecar+png pairs for two preview dirs, plus a matching _index.jsonl.
    val entries =
      listOf(
        synthEntry(
          id = "20260430-101234-aaaaaaaa",
          previewId = "com.example.A",
          bytes = "first".toByteArray(),
        ),
        synthEntry(
          id = "20260430-101300-bbbbbbbb",
          previewId = "com.example.A",
          bytes = "second".toByteArray(),
        ),
        synthEntry(
          id = "20260430-101400-cccccccc",
          previewId = "com.example.B",
          bytes = "third".toByteArray(),
        ),
      )
    populatePreviewMain(entries)

    val source =
      GitRefHistorySource(
        repoRoot = repoRoot,
        ref = "refs/heads/preview/main",
        warnEmitter = warnEmitter,
      )
    val page = source.list(HistoryFilter())
    assertEquals(3, page.totalCount)
    assertEquals(3, page.entries.size)
    // Newest first by timestamp (iso strings in entries).
    assertEquals("20260430-101400-cccccccc", page.entries[0].id)
    // All entries get source.kind = "git"; the id includes the source's id + the ref's commit.
    for (entry in page.entries) {
      assertEquals("git", entry.source.kind)
      assertTrue(entry.source.id.startsWith("git:refs/heads/preview/main"))
    }

    // read() resolves a real entry to a cache file containing the original bytes.
    val read = source.read("20260430-101300-bbbbbbbb", includeBytes = true)
    assertNotNull(read)
    val pngFile = File(read!!.pngPath)
    assertTrue("PNG cache file must exist", pngFile.exists())
    assertEquals("second", pngFile.readText())
    assertNotNull(read.pngBytes)
    assertEquals("second", String(read.pngBytes!!))
    assertEquals("git", read.entry.source.kind)
  }

  @Test
  fun corrupt_index_skips_truncated_line_and_lists_others() {
    val good1 =
      synthEntry(
        id = "20260430-100000-11111111",
        previewId = "com.example.A",
        bytes = "a".toByteArray(),
      )
    val good2 =
      synthEntry(
        id = "20260430-100100-22222222",
        previewId = "com.example.A",
        bytes = "b".toByteArray(),
      )
    val tree =
      createTreeWithEntriesAndIndex(listOf(good1, good2)) { jsonl ->
        // Corrupt: append a half-line that's not valid JSON.
        jsonl + "{\"id\":\"truncat" + "\n"
      }
    val commit = commitTree(tree, parent = null, message = "corrupt-index")
    runOk("git", "-C", repoRoot.toString(), "update-ref", "refs/heads/preview/main", commit)

    val source =
      GitRefHistorySource(
        repoRoot = repoRoot,
        ref = "refs/heads/preview/main",
        warnEmitter = warnEmitter,
      )
    val page = source.list(HistoryFilter())
    // Two valid entries — the third (truncated) line is skipped.
    assertEquals(2, page.totalCount)
    assertEquals(2, page.entries.size)
  }

  @Test
  fun cross_source_listing_dedups_and_filter_by_sourceKind() {
    // Same render lands in BOTH a LocalFs source and the git ref. Cross-source dedup keys on
    // (previewId, pngHash) and keeps the first writer (LocalFs).
    val historyDir = Files.createTempDirectory("xsource-localfs")
    try {
      val localFs = LocalFsHistorySource(historyDir = historyDir)
      val previewId = "com.example.X"
      val sharedBytes = "shared-render".toByteArray()
      val sharedHash = LocalFsHistorySource.sha256Hex(sharedBytes)

      // 1. Write the same render to LocalFs.
      val localFsEntry =
        HistoryEntry(
          id = "20260430-090000-deadbeef",
          previewId = previewId,
          module = ":t",
          timestamp = "2026-04-30T09:00:00Z",
          pngHash = sharedHash,
          pngSize = sharedBytes.size.toLong(),
          pngPath = "20260430-090000-deadbeef.png",
          producer = "daemon",
          trigger = "renderNow",
          source = HistorySourceInfo(kind = "fs", id = "fs:${historyDir.toAbsolutePath()}"),
          renderTookMs = 1L,
        )
      localFs.write(localFsEntry, sharedBytes)

      // 2. Build a git ref carrying the same render PLUS one git-only render.
      val gitOnlyBytes = "git-only".toByteArray()
      val gitRefEntries =
        listOf(
          synthEntryFromHash(
            id = localFsEntry.id,
            previewId = previewId,
            timestamp = localFsEntry.timestamp,
            pngHash = sharedHash,
            bytes = sharedBytes,
          ),
          synthEntry(
            id = "20260430-100000-99999999",
            previewId = previewId,
            bytes = gitOnlyBytes,
            timestamp = "2026-04-30T10:00:00Z",
          ),
        )
      populatePreviewMain(gitRefEntries)

      val gitSource =
        GitRefHistorySource(
          repoRoot = repoRoot,
          ref = "refs/heads/preview/main",
          warnEmitter = warnEmitter,
        )
      val manager =
        HistoryManager(sources = listOf(localFs, gitSource), module = ":t", gitProvenance = null)

      val all = manager.list(HistoryFilter())
      // Two unique renders — shared render's LocalFs copy is canonical, git-only render comes from
      // git.
      assertEquals(2, all.totalCount)
      val sharedSurface = all.entries.first { it.id == localFsEntry.id }
      assertEquals(
        "shared render must surface from LocalFs (priority 0)",
        "fs",
        sharedSurface.source.kind,
      )
      val gitOnlySurface = all.entries.first { it.id == "20260430-100000-99999999" }
      assertEquals("git", gitOnlySurface.source.kind)

      // sourceKind=git filter narrows to git-source entries — both the shared render's git copy
      // AND the git-only render show up here because the LocalFs (kind="fs") source is filtered
      // out at the per-source layer before merge-dedup runs.
      val gitOnlyPage = manager.list(HistoryFilter(sourceKind = "git"))
      assertEquals(2, gitOnlyPage.totalCount)
      assertTrue(gitOnlyPage.entries.all { it.source.kind == "git" })

      // sourceKind=fs filter shows just the LocalFs canonical copy.
      val fsOnlyPage = manager.list(HistoryFilter(sourceKind = "fs"))
      assertEquals(1, fsOnlyPage.totalCount)
      assertEquals(localFsEntry.id, fsOnlyPage.entries.single().id)
    } finally {
      historyDir.toFile().deleteRecursively()
    }
  }

  // -------------------------------------------------------------------------
  // Helpers — temp git repo manipulation via shell-out
  // -------------------------------------------------------------------------

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  private fun synthEntry(
    id: String,
    previewId: String,
    bytes: ByteArray,
    timestamp: String =
      "2026-04-30T${id.substring(9, 11)}:${id.substring(11, 13)}:${id.substring(13, 15)}Z",
  ): SynthEntry {
    val pngHash = LocalFsHistorySource.sha256Hex(bytes)
    return synthEntryFromHash(id, previewId, timestamp, pngHash, bytes)
  }

  private fun synthEntryFromHash(
    id: String,
    previewId: String,
    timestamp: String,
    pngHash: String,
    bytes: ByteArray,
  ): SynthEntry {
    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = ":t",
        timestamp = timestamp,
        pngHash = pngHash,
        pngSize = bytes.size.toLong(),
        pngPath = "$id.png",
        producer = "daemon",
        trigger = "renderNow",
        // The on-ref entry usually carries whatever source it was written under; rewrite happens
        // at read-time. Here we set "fs" to prove the rewrite is unconditional.
        source = HistorySourceInfo(kind = "fs", id = "fs:/some/dir"),
        renderTookMs = 1L,
      )
    return SynthEntry(entry = entry, bytes = bytes)
  }

  private data class SynthEntry(val entry: HistoryEntry, val bytes: ByteArray)

  private fun populatePreviewMain(entries: List<SynthEntry>) {
    val tree = createTreeWithEntriesAndIndex(entries) { it }
    val commit = commitTree(tree, parent = null, message = "history")
    runOk("git", "-C", repoRoot.toString(), "update-ref", "refs/heads/preview/main", commit)
  }

  /**
   * Builds an in-tree layout `<previewIdSan>/<id>.{png,json}` + `_index.jsonl` and returns the tree
   * sha. Uses `git hash-object` + `git mktree` rather than building the working tree.
   */
  private fun createTreeWithEntriesAndIndex(
    entries: List<SynthEntry>,
    transformIndex: (String) -> String,
  ): String {
    // Group entries by sanitisedPreviewId. For each group, build a sub-tree with the per-entry
    // .png + .json blobs.
    val byPreview = entries.groupBy { PreviewIdSanitiser.sanitise(it.entry.previewId) }
    val rootEntries = StringBuilder()
    for ((sanitised, group) in byPreview) {
      val subTreeEntries = StringBuilder()
      for (synth in group) {
        val pngSha = hashObject(synth.bytes)
        val sidecarText = json.encodeToString(HistoryEntry.serializer(), synth.entry)
        val sidecarSha = hashObject(sidecarText.toByteArray(StandardCharsets.UTF_8))
        subTreeEntries.append("100644 blob $pngSha\t${synth.entry.id}.png\n")
        subTreeEntries.append("100644 blob $sidecarSha\t${synth.entry.id}.json\n")
      }
      val subTree = mktree(subTreeEntries.toString())
      rootEntries.append("040000 tree $subTree\t$sanitised\n")
    }
    // Build _index.jsonl — entries minus previewMetadata, one per line, append-order (oldest
    // first).
    val sortedByTs = entries.sortedBy { it.entry.timestamp }
    val indexBody =
      sortedByTs.joinToString("\n") {
        json.encodeToString(HistoryEntry.serializer(), it.entry.copy(previewMetadata = null))
      } + "\n"
    val transformed = transformIndex(indexBody)
    val indexSha = hashObject(transformed.toByteArray(StandardCharsets.UTF_8))
    rootEntries.append("100644 blob $indexSha\t_index.jsonl\n")
    return mktree(rootEntries.toString())
  }

  private fun createEmptyTree(): String = mktree("")

  private fun mktree(input: String): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString(), "mktree"))
        .redirectErrorStream(false)
        .start()
    pb.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
    val finished = pb.waitFor(15, TimeUnit.SECONDS)
    require(finished) { "mktree timed out" }
    require(pb.exitValue() == 0) { "mktree failed: ${pb.errorStream.bufferedReader().readText()}" }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun hashObject(bytes: ByteArray): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString(), "hash-object", "-w", "--stdin"))
        .redirectErrorStream(false)
        .start()
    pb.outputStream.use { it.write(bytes) }
    val finished = pb.waitFor(15, TimeUnit.SECONDS)
    require(finished) { "hash-object timed out" }
    require(pb.exitValue() == 0) {
      "hash-object failed: ${pb.errorStream.bufferedReader().readText()}"
    }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun commitTree(treeSha: String, parent: String?, message: String): String {
    val args =
      mutableListOf("git", "-C", repoRoot.toString(), "commit-tree", treeSha, "-m", message)
    if (parent != null) {
      args.add("-p")
      args.add(parent)
    }
    val pb = ProcessBuilder(args).redirectErrorStream(false).start()
    val finished = pb.waitFor(15, TimeUnit.SECONDS)
    require(finished) { "commit-tree timed out" }
    require(pb.exitValue() == 0) {
      "commit-tree failed: ${pb.errorStream.bufferedReader().readText()}"
    }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun runOk(vararg args: String) {
    val pb = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
    val finished = pb.waitFor(15, TimeUnit.SECONDS)
    require(finished) { "${args.joinToString(" ")} timed out" }
    if (pb.exitValue() != 0) {
      val out = pb.inputStream.bufferedReader().readText()
      error("${args.joinToString(" ")} failed: $out")
    }
  }

  private fun gitAvailable(): Boolean {
    return try {
      val pb = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
      pb.waitFor(5, TimeUnit.SECONDS) && pb.exitValue() == 0
    } catch (_: Throwable) {
      false
    }
  }
}
