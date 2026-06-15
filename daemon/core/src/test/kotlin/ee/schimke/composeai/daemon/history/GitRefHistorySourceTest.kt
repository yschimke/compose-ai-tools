package ee.schimke.composeai.daemon.history

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * `GitRefHistorySource` unit tests — the reporting-branch source (read + WRITE_LOCAL). Each test
 * synthesises a temp git repo, then drives the source through its public surface; WRITE_LOCAL tests
 * dogfood the writer (write via the source, read back via the source) and inspect the resulting ref
 * tree directly.
 *
 * **`git` binary required.** All tests `Assume.assumeTrue` on `git --version` so CI runners without
 * git skip cleanly. Production callers tolerate missing git the same way [GitProvenance] does.
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

  private val ref = "refs/heads/preview/main"

  @Before
  fun setUp() {
    assumeTrue("git on PATH required for GitRefHistorySource tests", gitAvailable())
    repoRoot = Files.createTempDirectory("git-ref-test")
    runOk("git", "init", "-q", repoRoot.toString())
    runOk("git", "-C", repoRoot.toString(), "config", "user.email", "test@example.com")
    runOk("git", "-C", repoRoot.toString(), "config", "user.name", "Test")
    runOk("git", "-C", repoRoot.toString(), "config", "commit.gpgsign", "false")
    runOk("git", "-C", repoRoot.toString(), "config", "tag.gpgsign", "false")
    Files.writeString(repoRoot.resolve("README"), "init")
    runOk("git", "-C", repoRoot.toString(), "add", "README")
    runOk("git", "-C", repoRoot.toString(), "commit", "-q", "-m", "init")
  }

  @After
  fun tearDown() {
    if (this::repoRoot.isInitialized) repoRoot.toFile().deleteRecursively()
  }

  // -------------------------------------------------------------------------
  // Read: missing / empty ref
  // -------------------------------------------------------------------------

  @Test
  fun missing_ref_returns_empty_and_warns_once() {
    val source = source(SyncModeOf.READ_ONLY, "refs/heads/preview/nonexistent")
    val page = source.list(HistoryFilter())
    assertEquals(0, page.totalCount)
    assertTrue(page.entries.isEmpty())
    assertEquals(1, warnCount.get())
    assertTrue(warnLog.contains("refs/heads/preview/nonexistent"))
    assertTrue(warnLog.contains("git fetch"))

    source.list(HistoryFilter())
    assertEquals("one-shot warn guard", 1, warnCount.get())

    assertNull(source.read("does-not-exist", includeBytes = false))
    assertEquals(1, warnCount.get())
  }

  @Test
  fun empty_ref_returns_empty_no_warn() {
    val emptyCommit = commitTree(mktree(""), parent = null, message = "empty")
    runOk("git", "-C", repoRoot.toString(), "update-ref", ref, emptyCommit)

    val page = source(SyncModeOf.READ_ONLY).list(HistoryFilter())
    assertEquals(0, page.totalCount)
    assertEquals("ref exists → no warn", 0, warnCount.get())
  }

  // -------------------------------------------------------------------------
  // WRITE_LOCAL
  // -------------------------------------------------------------------------

  @Test
  fun supportsWrites_reflects_sync_mode() {
    assertFalse(source(SyncModeOf.READ_ONLY).supportsWrites())
    assertTrue(source(SyncModeOf.WRITE_LOCAL).supportsWrites())
  }

  @Test
  fun write_local_creates_ref_round_trips_and_writes_manifest() {
    val src = source(SyncModeOf.WRITE_LOCAL)
    val bytes = "render-A".toByteArray()
    val e =
      entry(
        id = "20260430-101234-aaaaaaaa",
        previewId = "com.example.A",
        bytes = bytes,
        a11yHierarchy = json.parseToJsonElement("""{"nodes":[]}"""),
        theme =
          json.parseToJsonElement(
            """{"resolvedTokens":{"colorScheme":{},"typography":{},"shapes":{}}}"""
          ),
      )
    assertEquals(WriteResult.WRITTEN, src.write(e, bytes))

    // The ref tree carries the git-as-the-log layout (overwritten per-preview paths + manifest).
    val tree = capture("git", "-C", repoRoot.toString(), "ls-tree", "-r", "--name-only", ref)
    assertTrue(tree.contains("com.example.A/render.png"))
    assertTrue(tree.contains("com.example.A/entry.json"))
    assertTrue(tree.contains("com.example.A/a11y.json"))
    assertTrue(tree.contains("com.example.A/theme.json"))
    assertTrue(tree.contains("manifest.json"))
    assertFalse("no a11y-atf file when not captured", tree.contains("a11y-atf.json"))

    val manifest =
      json.decodeFromString(
        ReportingBranchManifest.serializer(),
        capture("git", "-C", repoRoot.toString(), "show", "$ref:manifest.json"),
      )
    assertEquals(1, manifest.formatVersion)
    assertEquals(1, manifest.previews.size)
    assertEquals("com.example.A", manifest.previews[0].previewId)
    assertTrue(
      manifest.previews[0].dataProducts.containsAll(listOf("a11y/hierarchy", "compose/theme"))
    )

    // Round-trips through the source's own read surface, stamped as a git source.
    val page = src.list(HistoryFilter())
    assertEquals(1, page.totalCount)
    val listed = page.entries[0]
    // Commit-walk timeline (#1868): entries are addressed by `<shortCommit>:<previewId>`.
    assertEquals("com.example.A", listed.previewId)
    assertTrue(listed.id.endsWith(":com.example.A"))
    assertEquals("git", listed.source.kind)
    assertTrue(listed.source.id.startsWith("git:$ref"))

    val read = src.read(listed.id, includeBytes = true)
    assertNotNull(read)
    assertTrue(File(read!!.pngPath).exists())
    assertEquals("render-A", String(read.pngBytes!!))
  }

  @Test
  fun write_local_skips_unchanged_render() {
    val src = source(SyncModeOf.WRITE_LOCAL)
    val bytes = "steady".toByteArray()
    val e = entry(id = "20260430-100000-11111111", previewId = "com.example.Steady", bytes = bytes)

    assertEquals(WriteResult.WRITTEN, src.write(e, bytes))
    assertEquals(
      "identical render content adds nothing → no second commit",
      WriteResult.SKIPPED_DUPLICATE,
      src.write(e, bytes),
    )
    assertEquals("1", capture("git", "-C", repoRoot.toString(), "rev-list", "--count", ref).trim())
  }

  @Test
  fun write_local_overwrites_and_drops_stale_data_file() {
    val src = source(SyncModeOf.WRITE_LOCAL)
    val previewId = "com.example.Card"

    val first =
      entry(
        id = "20260430-100000-aaaaaaaa",
        previewId = previewId,
        bytes = "v1".toByteArray(),
        a11yHierarchy =
          json.parseToJsonElement("""{"nodes":[{"label":"x","boundsInScreen":"0,0,1,1"}]}"""),
      )
    assertEquals(WriteResult.WRITTEN, src.write(first, "v1".toByteArray()))
    assertTrue(
      capture("git", "-C", repoRoot.toString(), "ls-tree", "-r", "--name-only", ref)
        .contains("$previewId/a11y.json")
    )

    // A later render with different pixels and NO a11y must overwrite render.png and drop
    // a11y.json.
    val second =
      entry(id = "20260430-100100-bbbbbbbb", previewId = previewId, bytes = "v2".toByteArray())
    assertEquals(WriteResult.WRITTEN, src.write(second, "v2".toByteArray()))

    val tree = capture("git", "-C", repoRoot.toString(), "ls-tree", "-r", "--name-only", ref)
    assertTrue(tree.contains("$previewId/render.png"))
    assertFalse(
      "stale a11y.json must be removed on overwrite",
      tree.contains("$previewId/a11y.json"),
    )

    val page = src.list(HistoryFilter())
    // Commit-walk timeline (#1868): both renders of the preview are visible (one per commit), even
    // though the tip tree keeps only the latest render.png.
    assertEquals("timeline read → one entry per changed render", 2, page.totalCount)
    assertEquals("com.example.Card", page.entries[0].previewId)
    assertTrue(page.entries[0].id.endsWith(":com.example.Card"))
    assertEquals("2", capture("git", "-C", repoRoot.toString(), "rev-list", "--count", ref).trim())
  }

  // -------------------------------------------------------------------------
  // Commit-walk timeline read (#1868)
  // -------------------------------------------------------------------------

  @Test
  fun commit_walk_exposes_full_timeline_and_reads_an_older_point() {
    val src = source(SyncModeOf.WRITE_LOCAL)
    val previewId = "com.example.Timeline"
    // Three distinct renders of the same preview → three commits (skip-if-no-diff would collapse
    // identical ones, so the bytes differ).
    val v1 =
      entry("20260430-100000-11111111", previewId, "v1".toByteArray(), "2026-04-30T10:00:00Z")
    val v2 =
      entry("20260430-100100-22222222", previewId, "v2".toByteArray(), "2026-04-30T10:01:00Z")
    val v3 =
      entry("20260430-100200-33333333", previewId, "v3".toByteArray(), "2026-04-30T10:02:00Z")
    assertEquals(WriteResult.WRITTEN, src.write(v1, "v1".toByteArray()))
    assertEquals(WriteResult.WRITTEN, src.write(v2, "v2".toByteArray()))
    assertEquals(WriteResult.WRITTEN, src.write(v3, "v3".toByteArray()))

    val page = src.list(HistoryFilter())
    // The tip tree still holds one render.png, but the timeline exposes all three commits.
    assertEquals(3, page.totalCount)
    assertTrue(page.entries.all { it.previewId == previewId })
    assertTrue(page.entries.all { it.id.endsWith(":$previewId") })
    // Newest commit first.
    assertEquals("2026-04-30T10:02:00Z", page.entries[0].timestamp)
    assertEquals("2026-04-30T10:00:00Z", page.entries[2].timestamp)

    // previousId is relinked to the adjacent-older timeline id (resolvable on the ref), not the raw
    // LocalFs timestamp id; the oldest entry has none.
    assertEquals(page.entries[1].id, page.entries[0].previousId)
    assertEquals(page.entries[2].id, page.entries[1].previousId)
    assertNull(page.entries[2].previousId)

    // Read the *oldest* point by its `<shortCommit>:<previewId>` id → its own bytes, not the tip's.
    val oldest = page.entries[2]
    val read = src.read(oldest.id, includeBytes = true)
    assertNotNull(read)
    assertEquals("v1", String(read!!.pngBytes!!))
    assertEquals("git", read.entry.source.kind)
  }

  @Test
  fun cross_source_listing_dedups_and_filters_by_source_kind() {
    val historyDir = Files.createTempDirectory("xsource-localfs")
    try {
      val localFs = LocalFsHistorySource(historyDir = historyDir)
      val git = source(SyncModeOf.WRITE_LOCAL)
      val previewId = "com.example.X"

      // Same render written to BOTH sources.
      val sharedBytes = "shared".toByteArray()
      val shared =
        entry(id = "20260430-090000-deadbeef", previewId = previewId, bytes = sharedBytes)
      localFs.write(shared, sharedBytes)
      git.write(shared, sharedBytes)

      // A git-only render of a different preview.
      val gitOnlyBytes = "git-only".toByteArray()
      val gitOnly =
        entry(id = "20260430-100000-99999999", previewId = "com.example.Y", bytes = gitOnlyBytes)
      git.write(gitOnly, gitOnlyBytes)

      val manager =
        HistoryManager(sources = listOf(localFs, git), module = ":t", gitProvenance = null)

      val all = manager.list(HistoryFilter())
      assertEquals(2, all.totalCount)
      assertEquals(
        "shared render surfaces from LocalFs (priority 0)",
        "fs",
        all.entries.first { it.id == shared.id }.source.kind,
      )
      assertEquals("git", all.entries.first { it.previewId == "com.example.Y" }.source.kind)

      val gitPage = manager.list(HistoryFilter(sourceKind = "git"))
      assertEquals(2, gitPage.totalCount)
      assertTrue(gitPage.entries.all { it.source.kind == "git" })

      val fsPage = manager.list(HistoryFilter(sourceKind = "fs"))
      assertEquals(1, fsPage.totalCount)
      assertEquals(shared.id, fsPage.entries.single().id)
    } finally {
      historyDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun reads_legacy_index_format_as_fallback() {
    // A ref written under the legacy read-only format (`_index.jsonl` + `<id>.{png,json}`) must
    // keep reading via the fallback, even though the writer now emits the git-as-the-log layout.
    val e =
      entry(
        id = "20260430-100000-aaaaaaaa",
        previewId = "com.example.Legacy",
        bytes = "x".toByteArray(),
      )
    val dir = "com.example.Legacy"
    val pngSha = hashObject("x".toByteArray())
    val sidecarSha =
      hashObject(
        json.encodeToString(HistoryEntry.serializer(), e).toByteArray(StandardCharsets.UTF_8)
      )
    val sub = mktree("100644 blob $pngSha\t${e.id}.png\n100644 blob $sidecarSha\t${e.id}.json\n")
    val indexSha =
      hashObject(
        (json.encodeToString(HistoryEntry.serializer(), e) + "\n").toByteArray(
          StandardCharsets.UTF_8
        )
      )
    val root = mktree("040000 tree $sub\t$dir\n100644 blob $indexSha\t_index.jsonl\n")
    val commit = commitTree(root, parent = null, message = "legacy")
    runOk("git", "-C", repoRoot.toString(), "update-ref", ref, commit)

    val src = source(SyncModeOf.READ_ONLY)
    val page = src.list(HistoryFilter())
    assertEquals(1, page.totalCount)
    assertEquals(e.id, page.entries[0].id)
    assertEquals("git", page.entries[0].source.kind)

    val read = src.read(e.id, includeBytes = true)
    assertNotNull(read)
    assertEquals("x", String(read!!.pngBytes!!))
  }

  // -------------------------------------------------------------------------
  // H10-read — on-demand ref serving through HistoryManager (issue #1872)
  // -------------------------------------------------------------------------

  @Test
  fun on_demand_ref_serves_unconfigured_branch_through_manager() {
    // Populate the reporting branch via the WRITE_LOCAL writer (dogfood), with two previews.
    val writer = source(SyncModeOf.WRITE_LOCAL)
    val bytesA = "render-A".toByteArray()
    val bytesB = "render-B".toByteArray()
    val entryA = entry(id = "20260430-101200-aaaaaaaa", previewId = "com.example.A", bytes = bytesA)
    val entryB = entry(id = "20260430-101300-bbbbbbbb", previewId = "com.example.B", bytes = bytesB)
    assertEquals(WriteResult.WRITTEN, writer.write(entryA, bytesA))
    assertEquals(WriteResult.WRITTEN, writer.write(entryB, bytesB))

    val localDir = Files.createTempDirectory("on-demand-localfs")
    try {
      // The branch is deliberately NOT in `gitRefs` — it's only reachable on demand via `ref`.
      val manager =
        HistoryManager.forLocalFsAndGitRefs(
          historyDir = localDir,
          module = ":t",
          gitProvenance = null,
          gitRefs = emptyList(),
          repoRoot = repoRoot,
          warnEmitter = warnEmitter,
        )

      // Without `ref`, only the (empty) configured local source is consulted — the branch is
      // unseen.
      assertEquals(0, manager.list(HistoryFilter()).totalCount)
      assertNull(manager.read(entryA.id, includeBytes = false))

      // With `ref`, the listing is served on-demand from that branch, stamped as a git source.
      val page = manager.list(HistoryFilter(ref = ref))
      assertEquals(2, page.totalCount)
      assertTrue(page.entries.all { it.source.kind == "git" })
      // Commit-walk ids are `<shortCommit>:<previewId>`; match on the stable previewId.
      assertEquals(
        setOf("com.example.A", "com.example.B"),
        page.entries.map { it.previewId }.toSet(),
      )

      // Other filter dimensions still apply within the ref's entries.
      val narrowed = manager.list(HistoryFilter(ref = ref, previewId = "com.example.A"))
      assertEquals(1, narrowed.totalCount)
      assertEquals("com.example.A", narrowed.entries.single().previewId)

      // A ref-scoped read resolves the bytes by the timeline id; an id without a ref stays
      // invisible.
      val aId = narrowed.entries.single().id
      val read = manager.read(aId, includeBytes = true, ref = ref)
      assertNotNull(read)
      assertEquals("render-A", String(read!!.pngBytes!!))
      assertEquals("git", read.entry.source.kind)
      assertNull(manager.read(aId, includeBytes = false))
    } finally {
      localDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun on_demand_ref_without_factory_yields_empty() {
    // A manager with no git-ref factory (no repo root — fake-mode/test paths) must tolerate a `ref`
    // request: it returns empty / null rather than throwing or falling back to configured sources.
    val manager = HistoryManager(sources = emptyList(), module = ":t", gitProvenance = null)
    assertEquals(0, manager.list(HistoryFilter(ref = ref)).totalCount)
    assertNull(manager.read("any-id", includeBytes = false, ref = ref))
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  // Alias so the tests read clearly without importing the nested enum at call sites.
  private object SyncModeOf {
    val READ_ONLY = GitRefHistorySource.SyncMode.READ_ONLY
    val WRITE_LOCAL = GitRefHistorySource.SyncMode.WRITE_LOCAL
  }

  private fun source(
    syncMode: GitRefHistorySource.SyncMode,
    ref: String = this.ref,
  ): GitRefHistorySource =
    GitRefHistorySource(
      repoRoot = repoRoot,
      ref = ref,
      syncMode = syncMode,
      warnEmitter = warnEmitter,
    )

  private fun entry(
    id: String,
    previewId: String,
    bytes: ByteArray,
    timestamp: String = "2026-04-30T10:12:34Z",
    a11yHierarchy: JsonElement? = null,
    theme: JsonElement? = null,
  ): HistoryEntry =
    HistoryEntry(
      id = id,
      previewId = previewId,
      module = ":t",
      timestamp = timestamp,
      pngHash = LocalFsHistorySource.sha256Hex(bytes),
      pngSize = bytes.size.toLong(),
      pngPath = "$id.png",
      producer = "daemon",
      trigger = "renderNow",
      source = HistorySourceInfo(kind = "fs", id = "fs:/some/dir"),
      renderTookMs = 1L,
      a11yHierarchy = a11yHierarchy,
      theme = theme,
    )

  private fun mktree(input: String): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString(), "mktree"))
        .redirectErrorStream(false)
        .start()
    pb.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "mktree timed out" }
    require(pb.exitValue() == 0) { "mktree failed" }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun hashObject(bytes: ByteArray): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString(), "hash-object", "-w", "--stdin"))
        .redirectErrorStream(false)
        .start()
    pb.outputStream.use { it.write(bytes) }
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "hash-object timed out" }
    require(pb.exitValue() == 0) { "hash-object failed" }
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
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "commit-tree timed out" }
    require(pb.exitValue() == 0) { "commit-tree failed" }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun capture(vararg args: String): String {
    val pb = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
    val out = pb.inputStream.readBytes()
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "${args.joinToString(" ")} timed out" }
    require(pb.exitValue() == 0) { "${args.joinToString(" ")} failed" }
    return String(out, StandardCharsets.UTF_8)
  }

  private fun runOk(vararg args: String) {
    val pb = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "${args.joinToString(" ")} timed out" }
    if (pb.exitValue() != 0) {
      error("${args.joinToString(" ")} failed: ${pb.inputStream.bufferedReader().readText()}")
    }
  }

  private fun gitAvailable(): Boolean =
    try {
      val pb = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
      pb.waitFor(5, TimeUnit.SECONDS) && pb.exitValue() == 0
    } catch (_: Throwable) {
      false
    }
}
