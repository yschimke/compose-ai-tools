package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Test

/**
 * The trust boundary for the published activity feed. A catalog is third-party data carrying free
 * text other people wrote, so these assert the *rejections* as much as the happy path.
 */
class ServeParityActivityStoreTest {
  private val fileSystem = FakeFileSystem()
  private val root = "/bundle".toPath()
  private val json = Json { prettyPrint = true }
  private val wireJson = Json { ignoreUnknownKeys = true }

  private val sha = "4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d"

  private fun write(activity: ParityActivity) {
    fileSystem.createDirectories(root / ParityActivity.DIRECTORY)
    fileSystem.write(root / ParityActivity.DIRECTORY / ParityActivity.FILE) {
      writeUtf8(json.encodeToString(activity))
    }
  }

  private fun load(): ParityActivity? = ServeParityActivityStore.load(File("/bundle"), fileSystem)

  @Test
  fun `loads a well-formed feed from the catalog`() {
    write(
      ParityActivity(
        generatedAt = "2026-08-06T09:12:00Z",
        windowDays = 30,
        code =
          CodeLane(
            repo = "yschimke/m3-catalog",
            ref = "main",
            events =
              listOf(
                CodeEvent(
                  sha = sha,
                  subject = "feat: fan out the Navigation section",
                  at = "2026-08-05T10:00:00Z",
                  author = "Yuri",
                  previewIds = listOf("nav-rail__ideal__default__light"),
                  components = listOf("Navigation/Rail"),
                )
              ),
          ),
        figma =
          FigmaLane(
            fileKey = "ocdacdEsnHipMJD3egzxKb",
            fileName = "Material 3 Design Kit",
            comments =
              listOf(
                FigmaCommentEvent(
                  id = "c1",
                  at = "2026-08-04T08:00:00Z",
                  message = "The rail's active indicator is 4dp too tall.",
                  author = "Dana",
                  nodeId = "51592:4768",
                  previewIds = listOf("nav-rail__ideal__default__light"),
                )
              ),
          ),
        gaps =
          listOf(
            MappingGap(
              kind = MappingGap.Kind.DANGLING_MAPPING,
              detail = "design-map names a preview the catalog no longer publishes",
              previewId = "gone__ideal__default__light",
            )
          ),
      )
    )

    val loaded = assertNotNull(load())
    assertEquals("yschimke/m3-catalog", loaded.code?.repo)
    assertEquals(1, loaded.code?.events?.size)
    assertEquals("51592:4768", loaded.figma?.comments?.single()?.nodeId)
    assertEquals(1, loaded.gaps.size)
  }

  @Test
  fun `a catalog that publishes no feed loads as null`() {
    assertNull(load())
  }

  @Test
  fun `a wrong schema token drops the whole feed`() {
    write(
      ParityActivity(
        schema = "something-else/v9",
        code =
          CodeLane(
            events = listOf(CodeEvent(sha = sha, subject = "x", at = "2026-08-05T10:00:00Z"))
          ),
      )
    )
    assertNull(load())
  }

  @Test
  fun `malformed records are dropped and the rest of the feed survives`() {
    val sanitized =
      ServeParityActivityStore.sanitize(
        ParityActivity(
          code =
            CodeLane(
              repo = "not a repo",
              events =
                listOf(
                  // No parseable timestamp: the feed is ordered by time, so this has nowhere to go.
                  CodeEvent(sha = sha, subject = "undated", at = "yesterday"),
                  // Not a sha: the commit URL is rebuilt from it, so a bad one must not become a
                  // link.
                  CodeEvent(sha = "HEAD~1", subject = "not a sha", at = "2026-08-05T10:00:00Z"),
                  CodeEvent(sha = sha, subject = "kept", at = "2026-08-05T10:00:00Z"),
                ),
            ),
          figma =
            FigmaLane(
              fileKey = "not/a/key",
              comments =
                listOf(
                  FigmaCommentEvent(
                    id = "c1",
                    at = "2026-08-04T08:00:00Z",
                    message = "kept",
                    nodeId = "javascript:alert(1)",
                  ),
                  FigmaCommentEvent(id = "c2", at = "2026-08-04T08:00:00Z", message = "  "),
                ),
            ),
          gaps =
            listOf(
              MappingGap(kind = "invented-kind", detail = "dropped"),
              MappingGap(kind = MappingGap.Kind.UNMAPPED_DESIGN_NODE, detail = "kept"),
            ),
        )
      )

    val loaded = assertNotNull(sanitized)
    assertEquals(listOf("kept"), loaded.code?.events?.map { it.subject })
    assertNull(loaded.code?.repo, "a repo that isn't owner/name must not become a link base")
    assertEquals(listOf("kept"), loaded.figma?.comments?.map { it.message })
    assertNull(loaded.figma?.comments?.single()?.nodeId, "a non-node-id must not survive")
    assertNull(loaded.figma?.fileKey)
    assertEquals(listOf("kept"), loaded.gaps.map { it.detail })
  }

  @Test
  fun `a feed with nothing left after validation is treated as no feed`() {
    val sanitized =
      ServeParityActivityStore.sanitize(
        ParityActivity(
          code = CodeLane(events = listOf(CodeEvent(sha = "nope", subject = "x", at = "x")))
        )
      )
    assertNull(sanitized, "an empty dashboard is worse than not offering the page")
  }

  @Test
  fun `events are ordered newest-first regardless of the order published`() {
    val sanitized =
      assertNotNull(
        ServeParityActivityStore.sanitize(
          ParityActivity(
            code =
              CodeLane(
                events =
                  listOf(
                    CodeEvent(sha = sha, subject = "older", at = "2026-08-01T10:00:00Z"),
                    CodeEvent(sha = sha, subject = "newer", at = "2026-08-05T10:00:00Z"),
                  )
              )
          )
        )
      )
    assertEquals(listOf("newer", "older"), sanitized.code?.events?.map { it.subject })
  }

  @Test
  fun `free text is truncated rather than dropped`() {
    val long = "x".repeat(900)
    val sanitized =
      assertNotNull(
        ServeParityActivityStore.sanitize(
          ParityActivity(
            code =
              CodeLane(
                events = listOf(CodeEvent(sha = sha, subject = long, at = "2026-08-05T10:00:00Z"))
              )
          )
        )
      )
    val subject = assertNotNull(sanitized.code?.events?.single()?.subject)
    assertTrue(subject.length <= 400, "expected clamped subject, got ${subject.length}")
    assertTrue(subject.endsWith("…"))
  }

  @Test
  fun `outbound links are rebuilt from validated parts`() {
    assertEquals(
      "https://github.com/yschimke/m3-catalog/commit/$sha",
      ServeParityActivityStore.commitUrl("yschimke/m3-catalog", sha),
    )
    assertNull(ServeParityActivityStore.commitUrl("javascript:alert(1)", sha))
    assertNull(ServeParityActivityStore.commitUrl("yschimke/m3-catalog", "HEAD"))

    // Figma's URL form spells a node id `73-6` where the API and the design map use `73:6`.
    assertEquals(
      "https://www.figma.com/design/abc123?node-id=51592-4768",
      ServeParityActivityStore.nodeUrl("abc123", "51592:4768"),
    )
    assertNull(ServeParityActivityStore.nodeUrl("abc/123", "51592:4768"))
    assertNull(ServeParityActivityStore.nodeUrl("abc123", "not-a-node"))
    assertEquals("https://www.figma.com/design/abc123", ServeParityActivityStore.fileUrl("abc123"))
    assertNull(ServeParityActivityStore.fileUrl(null))
  }

  /**
   * The producer↔consumer contract, pinned by a file both sides use.
   *
   * `scripts/design-artifacts/parity-activity.mjs` writes this schema and this Kotlin reads it —
   * two languages, one wire format, and nothing in either build that would notice them drifting
   * apart. The committed fixture is the emitter's own output (regenerate it by re-running the
   * builders in `parity-activity.test.mjs`); loading it here means a field the emitter renames, or
   * a shape the reader tightens, fails a test instead of silently publishing a feed the server
   * discards.
   */
  @Test
  fun `the emitter's own output loads as a complete feed`() {
    val fixture = File(repoRoot(), "scripts/design-artifacts/fixtures/parity-activity.json")
    assertTrue(fixture.isFile, "missing wire-format fixture at $fixture")
    val loaded =
      assertNotNull(
        ServeParityActivityStore.sanitize(
          wireJson.decodeFromString<ParityActivity>(fixture.readText())
        ),
        "the emitter's output must survive the reader's validation",
      )

    val code = assertNotNull(loaded.code)
    val figma = assertNotNull(loaded.figma)
    assertEquals("yschimke/m3-catalog", code.repo)
    assertEquals(1, code.events.size)
    // `+00:00` offsets, not just `Z` — git's `%aI` emits the former and the reader must take it.
    assertEquals("2026-08-05T10:00:00+00:00", code.events.single().at)
    assertEquals("ocdacdEsnHipMJD3egzxKb", figma.fileKey)
    assertEquals(1, figma.versions.size)
    // The comment resolved to the preview it specifies, which is what makes the page a parity view.
    assertEquals(
      listOf("switch-on__ideal__default__light"),
      figma.comments.single().previewIds,
    )
    assertEquals(MappingGap.Kind.UNMAPPED_DESIGN_NODE, loaded.gaps.single().kind)

    // **The namespace check.** The emitter and the server key previews in two different alphabets:
    // a design map names the raw *discovery* id (`sections.ButtonsKt.FilledButton_Light`) while the
    // serve host keys a preview by the route-safe id derived from its image path. The feed must
    // carry the latter — emitting the former loses every inbound link on the page silently, since
    // `ServeParityDashboard` just filters unknown ids out and renders a row with no target. So
    // assert the *shape*, not only the value: a discovery id here is a bug even if it parses.
    val eventPreviewIds =
      code.events.flatMap { it.previewIds } + figma.comments.flatMap { it.previewIds }
    assertTrue(eventPreviewIds.isNotEmpty(), "the fixture must exercise the join")
    for (id in eventPreviewIds) {
      assertFalse(
        id.contains('.'),
        "expected a route-safe serve id, got what looks like a discovery id: $id",
      )
    }
    // …and they are ids a dashboard built over that catalog would actually match.
    val dashboard =
      ServeParityDashboard.build(
        previews = eventPreviewIds.distinct().map { ServePreview(it, it) },
        hasReference = { false },
        activity = loaded,
      )
    assertTrue(
      dashboard.feed.any { it.previewIds.isNotEmpty() },
      "the published feed must produce inbound links against a catalog serving those ids",
    )
    // …and every outbound link the page will build from it resolves.
    assertNotNull(ServeParityActivityStore.commitUrl(code.repo, code.events.single().sha))
    assertNotNull(
      ServeParityActivityStore.nodeUrl(
        figma.fileKey,
        figma.comments.single().nodeId,
      )
    )
  }

  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root from ${System.getProperty("user.dir")}")
  }

  @Test
  fun `unparseable json loads as no feed rather than throwing`() {
    fileSystem.createDirectories(root / ParityActivity.DIRECTORY)
    fileSystem.write(root / ParityActivity.DIRECTORY / ParityActivity.FILE) {
      writeUtf8("{not json")
    }
    assertNull(load())
  }
}
