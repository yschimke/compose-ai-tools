package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistorySourceInfo
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scenario **S10 — Main-history read (fake mode)**. Synthesises a temp git repo with a populated
 * `refs/heads/preview/main` ref, drives [FakeDaemonMain] with `composeai.daemon.gitRefHistory`
 * pointing at that ref, and asserts that:
 *
 * 1. `history/list({sourceKind: "git"})` returns the entries from the git ref.
 * 2. Each entry's `source.kind == "git"`.
 *
 * The render-write side of the daemon is unchanged — we don't render here; we just prove the
 * read-only `GitRefHistorySource` plumbs end-to-end through the wire.
 */
class S10MainHistoryReadTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  @Test
  fun s10_main_history_read_fake_mode() {
    assumeTrue("git on PATH required for S10", gitAvailable())
    val moduleBuildDir = File("build")
    val fixtureDir =
      File(moduleBuildDir, "daemon-harness/fixtures/s10").apply {
        deleteRecursively()
        mkdirs()
      }
    File(fixtureDir, "previews.json").writeText("[]")

    val repoRoot = Files.createTempDirectory("s10-repo").toFile().apply { deleteOnExit() }
    initGitRepo(repoRoot)
    // Populate refs/heads/preview/main with two synthetic entries.
    val entries =
      listOf(
        synth(
          id = "20260430-101234-aaaaaaaa",
          previewId = "com.example.A",
          bytes = "alpha".toByteArray(),
          timestamp = "2026-04-30T10:12:34Z",
        ),
        synth(
          id = "20260430-101300-bbbbbbbb",
          previewId = "com.example.A",
          bytes = "beta".toByteArray(),
          timestamp = "2026-04-30T10:13:00Z",
        ),
      )
    populatePreviewMainRef(repoRoot, entries)

    val historyDir = Files.createTempDirectory("s10-history").toFile().apply { deleteOnExit() }
    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }
    val launcher =
      FakeHarnessLauncher(
        fixtureDir = fixtureDir,
        classpath = classpath,
        historyDir = historyDir,
        workspaceRoot = repoRoot,
        gitRefHistory = listOf("refs/heads/preview/main"),
      )
    val client = HarnessClient.start(launcher)
    try {
      client.initialize()
      client.sendInitialized()

      // history/list({sourceKind: "git"}) should return the two ref entries.
      val gitOnly = client.historyList(HistoryListParams(sourceKind = "git"))
      assertEquals(2, gitOnly.totalCount)
      assertEquals(2, gitOnly.entries.size)
      for (entry in gitOnly.entries) {
        val source = entry.jsonObject["source"]!!.jsonObject
        assertEquals("git", source["kind"]?.jsonPrimitive?.contentOrNull)
      }

      // history/list with no filter — also surfaces both git entries (LocalFs is empty here).
      val all = client.historyList(HistoryListParams())
      assertEquals(2, all.totalCount)

      val exitCode = client.shutdownAndExit()
      assertEquals(0, exitCode)
    } catch (t: Throwable) {
      System.err.println("S10MainHistoryReadTest failed; stderr from daemon:\n${client.dumpStderr()}")
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
      repoRoot.deleteRecursively()
      historyDir.deleteRecursively()
    }
  }

  // -------------------------------------------------------------------------
  // Helpers — temp git repo manipulation via shell-out (mirrors the unit-test helpers in
  // GitRefHistorySourceTest; intentionally not extracted to test fixtures because the harness
  // module deliberately doesn't depend on `:daemon:core` test sources).
  // -------------------------------------------------------------------------

  private fun initGitRepo(repoRoot: File) {
    runOk("git", "init", "-q", repoRoot.absolutePath)
    runOk("git", "-C", repoRoot.absolutePath, "config", "user.email", "test@example.com")
    runOk("git", "-C", repoRoot.absolutePath, "config", "user.name", "Test")
    runOk("git", "-C", repoRoot.absolutePath, "config", "commit.gpgsign", "false")
    File(repoRoot, "README").writeText("init")
    runOk("git", "-C", repoRoot.absolutePath, "add", "README")
    runOk("git", "-C", repoRoot.absolutePath, "commit", "-q", "-m", "init")
  }

  /** Mirror of `PreviewIdSanitiser.sanitise` (internal in :daemon:core; pinned shape). */
  private fun sanitisePreviewId(previewId: String): String {
    if (previewId.isEmpty()) return "_"
    val sb = StringBuilder(previewId.length)
    for (c in previewId) {
      sb.append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
    }
    return sb.toString()
  }

  private fun populatePreviewMainRef(repoRoot: File, entries: List<SynthEntry>) {
    val byPreview = entries.groupBy { sanitisePreviewId(it.entry.previewId) }
    val rootEntries = StringBuilder()
    for ((sanitised, group) in byPreview) {
      val sub = StringBuilder()
      for (synth in group) {
        val pngSha = hashObject(repoRoot, synth.bytes)
        val sidecarText = json.encodeToString(HistoryEntry.serializer(), synth.entry)
        val sidecarSha = hashObject(repoRoot, sidecarText.toByteArray(StandardCharsets.UTF_8))
        sub.append("100644 blob $pngSha\t${synth.entry.id}.png\n")
        sub.append("100644 blob $sidecarSha\t${synth.entry.id}.json\n")
      }
      val subTree = mktree(repoRoot, sub.toString())
      rootEntries.append("040000 tree $subTree\t$sanitised\n")
    }
    val indexLines = entries.sortedBy { it.entry.timestamp }.joinToString("\n") {
      json.encodeToString(HistoryEntry.serializer(), it.entry.copy(previewMetadata = null))
    } + "\n"
    val indexSha = hashObject(repoRoot, indexLines.toByteArray(StandardCharsets.UTF_8))
    rootEntries.append("100644 blob $indexSha\t_index.jsonl\n")
    val rootTree = mktree(repoRoot, rootEntries.toString())
    val commit = commitTree(repoRoot, rootTree, "history")
    runOk("git", "-C", repoRoot.absolutePath, "update-ref", "refs/heads/preview/main", commit)
  }

  private fun synth(id: String, previewId: String, bytes: ByteArray, timestamp: String): SynthEntry {
    val pngHash = LocalFsHistorySource.sha256Hex(bytes)
    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = ":harness",
        timestamp = timestamp,
        pngHash = pngHash,
        pngSize = bytes.size.toLong(),
        pngPath = "$id.png",
        producer = "daemon",
        trigger = "renderNow",
        source = HistorySourceInfo(kind = "fs", id = "fs:/synthetic"),
        renderTookMs = 1L,
      )
    return SynthEntry(entry, bytes)
  }

  private data class SynthEntry(val entry: HistoryEntry, val bytes: ByteArray)

  private fun mktree(repoRoot: File, input: String): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.absolutePath, "mktree"))
        .redirectErrorStream(false)
        .start()
    pb.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "mktree timed out" }
    require(pb.exitValue() == 0) {
      "mktree failed: ${pb.errorStream.bufferedReader().readText()}"
    }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun hashObject(repoRoot: File, bytes: ByteArray): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.absolutePath, "hash-object", "-w", "--stdin"))
        .redirectErrorStream(false)
        .start()
    pb.outputStream.use { it.write(bytes) }
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "hash-object timed out" }
    require(pb.exitValue() == 0) {
      "hash-object failed: ${pb.errorStream.bufferedReader().readText()}"
    }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun commitTree(repoRoot: File, treeSha: String, message: String): String {
    val pb =
      ProcessBuilder(listOf("git", "-C", repoRoot.absolutePath, "commit-tree", treeSha, "-m", message))
        .redirectErrorStream(false)
        .start()
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "commit-tree timed out" }
    require(pb.exitValue() == 0) {
      "commit-tree failed: ${pb.errorStream.bufferedReader().readText()}"
    }
    return pb.inputStream.bufferedReader().readText().trim()
  }

  private fun runOk(vararg args: String) {
    val pb = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
    require(pb.waitFor(15, TimeUnit.SECONDS)) { "${args.joinToString(" ")} timed out" }
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
