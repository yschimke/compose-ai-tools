package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Project-mode render history: the timeline the viewer inlines when `serve` runs against a local
 * checkout, and the content-addressed lane its entries link to.
 *
 * Every git call is faked, so this covers the parts that are actually this class's own — ref
 * resolution and its `origin/` fallback, the payload scoping, the blob allowlist, and the memo —
 * without needing a repository with a delivery branch in it. The log parsing and collapse rules
 * they feed are covered by [PreviewHistoryTest].
 */
class ServeProjectHistoryTest {

  private val repoRoot = File("/repo")
  private val previewId = "samples:compose-m3/com.example.ProfilePreview_default"
  private val otherId = "samples:compose-m3/com.example.OtherPreview_default"
  private val sha = "df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80"

  private val blobA = "a".repeat(40)
  private val blobB = "b".repeat(40)
  private val blobOther = "c".repeat(40)

  private val baselines =
    """
    {
      "$previewId": {"module": "samples:compose-m3", "renderBasename": "ProfilePreview.png"},
      "$otherId": {"module": "samples:compose-m3", "renderBasename": "OtherPreview.png"}
    }
    """
      .trimIndent()

  /** Two versions for the profile preview, one (so: no timeline) for the other. */
  private val log =
    listOf(
        header(sha, "2026-05-22T11:08:37+00:00", "Update preview baselines from 57ac24f3"),
        raw(blobA, "renders/samples:compose-m3/ProfilePreview.png"),
        raw(blobOther, "renders/samples:compose-m3/OtherPreview.png"),
        header(
          "8b9f6f2bc953756edcb13963e09cd57c54866570",
          "2026-05-07T08:34:51+00:00",
          "Update preview baselines from cf69a4a0",
        ),
        raw(blobB, "renders/samples:compose-m3/ProfilePreview.png"),
      )
      .joinToString("\n")

  /** `git log --format=%x01%H%x1f%aI%x1f%s` — see [PreviewHistory.logArgs]. */
  private fun header(commit: String, date: String, subject: String) =
    "\u0001$commit\u001F$date\u001F$subject"

  private fun raw(blob: String, path: String) = ":100644 100644 ${"0".repeat(40)} $blob M\t$path"

  /** Records every git invocation and answers the three this class makes. */
  private class FakeGit(
    private val refs: Map<String, String>,
    private val files: Map<String, String>,
    private val log: String,
  ) : GitRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(workdir: File, args: List<String>): GitResult {
      calls += args
      return when {
        args.firstOrNull() == "rev-parse" -> {
          val ref = args.last().removeSuffix("^{commit}")
          refs[ref]?.let { GitResult(0, "$it\n") } ?: GitResult(1, "")
        }
        args.firstOrNull() == "show" ->
          files[args.last()]?.let { GitResult(0, it) } ?: GitResult(128, "")
        args.contains("log") -> GitResult(0, log)
        else -> GitResult(1, "")
      }
    }
  }

  private fun png(marker: Byte = 1): ByteArray =
    byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), marker)

  private fun history(
    git: GitRunner,
    branch: String = ServeProjectHistory.DEFAULT_BRANCH,
    blobs: Map<String, ByteArray> = mapOf(blobA to png(1), blobB to png(2)),
    now: () -> Long = { 0L },
    refreshMillis: Long = ServeProjectHistory.DEFAULT_REFRESH_MILLIS,
  ) =
    ServeProjectHistory(
      repoRoot = repoRoot,
      branch = branch,
      refreshMillis = refreshMillis,
      git = git,
      readBlobBytes = { _, sha -> blobs[sha] },
      now = now,
    )

  private fun fakeGit(refs: Map<String, String> = mapOf("compose-preview/main" to sha)) =
    FakeGit(refs = refs, files = mapOf("$sha:baselines.json" to baselines), log = log)

  @Test
  fun `inlines only the requested preview's timeline`() {
    val json = history(fakeGit()).timelineJsonFor(previewId)
    val root = Json.parseToJsonElement(json!!).jsonObject
    assertEquals(
      PreviewHistoryManifest.FORMAT_VERSION,
      root["formatVersion"]?.jsonPrimitive?.content,
    )
    assertEquals(sha, root["generatedFrom"]?.jsonPrimitive?.content)
    val previews = root["previews"]!!.jsonObject
    // Scoped to one preview: shipping the whole branch's manifest into every page would carry
    // hundreds of timelines the page can't draw.
    assertEquals(setOf(previewId), previews.keys)
    val timeline = previews[previewId]!!.jsonObject
    assertEquals(
      "renders/samples:compose-m3/ProfilePreview.png",
      timeline["path"]?.jsonPrimitive?.content,
    )
    val versions = timeline["versions"]!!.jsonArray
    assertEquals(2, versions.size)
    assertEquals(blobA, versions[0].jsonObject["blob"]?.jsonPrimitive?.content)
    // The source commit is recovered from the publish subject, exactly as the published manifest
    // does — it's what a chip labels itself with.
    assertEquals("57ac24f3", versions[0].jsonObject["sourceSha"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a single-version preview carries no payload`() {
    // One version is not a timeline: the viewer refuses to draw it, so the page shouldn't carry it.
    assertNull(history(fakeGit()).timelineJsonFor(otherId))
  }

  @Test
  fun `an unknown preview carries no payload`() {
    assertNull(history(fakeGit()).timelineJsonFor("samples:compose-m3/com.example.Gone_default"))
  }

  @Test
  fun `falls back to the remote-tracking ref`() {
    // The ordinary shape of a clone that publishes baselines from CI: no local branch, only
    // origin/compose-preview/main.
    val git = fakeGit(refs = mapOf("origin/compose-preview/main" to sha))
    assertTrue(history(git).timelineJsonFor(previewId) != null)
    assertTrue(git.calls.any { it.contains("origin/compose-preview/main^{commit}") })
  }

  @Test
  fun `an unresolvable ref means no timeline`() {
    val git = fakeGit(refs = emptyMap())
    assertNull(history(git).timelineJsonFor(previewId))
    // Nothing beyond the two rev-parse probes: no log walk is attempted against a ref that isn't
    // there.
    assertTrue(git.calls.all { it.firstOrNull() == "rev-parse" })
  }

  @Test
  fun `a branch with no baselines means no timeline`() {
    // Without baselines.json there is no render-path → preview-id join, so every timeline would be
    // dropped anyway.
    val git = FakeGit(refs = mapOf("compose-preview/main" to sha), files = emptyMap(), log = log)
    assertNull(history(git).timelineJsonFor(previewId))
  }

  @Test
  fun `serves a render the timeline names`() {
    assertTrue(history(fakeGit()).renderBytes(blobB)?.contentEquals(png(2)) == true)
  }

  @Test
  fun `refuses a blob the timeline does not name`() {
    val stranger = "d".repeat(40)
    val history = history(fakeGit(), blobs = mapOf(stranger to png(3)))
    // A real, readable object in the repository that no timeline addresses — a source file, a
    // secret in an old commit. "Present in the object store" is deliberately not sufficient: this
    // endpoint hands out renders the manifest names, and nothing else.
    assertNull(history.renderBytes(stranger))
    assertNull(history.renderBytes("not-a-sha"))
    assertNull(history.renderBytes(blobA.take(8)))
  }

  @Test
  fun `refuses an object that is not a PNG`() {
    val history = history(fakeGit(), blobs = mapOf(blobA to "#!/bin/sh\n".toByteArray()))
    assertNull(history.renderBytes(blobA))
  }

  @Test
  fun `computes once per refresh window`() {
    val git = fakeGit()
    var clock = 0L
    val history = history(git, now = { clock }, refreshMillis = 1_000)
    repeat(5) { history.timelineJsonFor(previewId) }
    val once = git.calls.size
    assertEquals(1, git.calls.count { it.contains("log") })

    // Past the window the branch is re-read, so fetching the delivery branch mid-session shows up
    // without restarting the server.
    clock = 2_000
    history.timelineJsonFor(previewId)
    assertEquals(2, git.calls.count { it.contains("log") })
    assertTrue(git.calls.size > once)
  }
}
