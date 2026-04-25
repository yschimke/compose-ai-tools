package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PreviewListResponseTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `envelope pins schema and round-trips captures`() {
    val response =
      PreviewListResponse(
        previews =
          listOf(
            PreviewResult(
              id = "Preview_A",
              module = "sample-android",
              functionName = "PreviewA",
              className = "com.example.PreviewsKt",
              sourceFile = "Previews.kt",
              captures =
                listOf(
                  CaptureResult(
                    advanceTimeMillis = 0L,
                    pngPath = "/abs/A_TIME_0ms.png",
                    sha256 = "deadbeef",
                    changed = true,
                  ),
                  CaptureResult(
                    advanceTimeMillis = 500L,
                    pngPath = "/abs/A_TIME_500ms.png",
                    sha256 = "feedface",
                    changed = false,
                  ),
                ),
              pngPath = "/abs/A_TIME_0ms.png",
              sha256 = "deadbeef",
              changed = true,
            )
          ),
        counts = PreviewCounts(total = 1, changed = 1, unchanged = 0, missing = 0),
      )

    val text = json.encodeToString(PreviewListResponse.serializer(), response)
    val parsed = json.decodeFromString(PreviewListResponse.serializer(), text)
    assertEquals(response, parsed)

    // Schema is the agent contract — keep it greppable without parsing.
    assertTrue("\"schema\":\"$SHOW_LIST_SCHEMA\"" in text)
    // Multi-capture fan-out is faithfully serialised.
    assertEquals(2, parsed.previews.single().captures.size)
    assertEquals(500L, parsed.previews.single().captures[1].advanceTimeMillis)
  }

  @Test
  fun `default schema field stays at v1`() {
    // Bump this test deliberately when rolling to v2 — guards against
    // accidentally breaking the CLI→agent contract.
    val response = PreviewListResponse(previews = emptyList())
    assertEquals("compose-preview-show/v1", response.schema)
  }

  @Test
  fun `brief response drops null and default fields`() {
    // encodeDefaults=false on the brief encoder is the lever that makes
    // this work — verify it actually fires.
    val response =
      BriefPreviewListResponse(
        previews =
          listOf(
            BriefPreviewResult(
              id = "P",
              captures = listOf(BriefCapture(png = "/p.png", sha = "abcdef012345", changed = true)),
            )
          ),
        counts = PreviewCounts(total = 1, changed = 1, unchanged = 0, missing = 0),
      )
    val brief =
      Json {
          prettyPrint = false
          encodeDefaults = false
        }
        .encodeToString(BriefPreviewListResponse.serializer(), response)

    // Schema and core fields are present.
    assertTrue(""""schema":"$SHOW_LIST_BRIEF_SCHEMA"""" in brief)
    assertTrue(""""id":"P"""" in brief)
    assertTrue(""""png":"/p.png"""" in brief)
    // Nulls / unspecified optionals are absent.
    assertTrue("\"module\"" !in brief)
    assertTrue("\"a11y\"" !in brief)
    assertTrue("\"time\"" !in brief)
    assertTrue("\"scroll\"" !in brief)
  }

  @Test
  fun `manifest with old-shape renderOutput still parses via ignoreUnknownKeys`() {
    // Pre-multi-capture manifests had `renderOutput` directly on
    // `PreviewInfo`. New CLI types don't carry that field; verify they
    // still parse without throwing (it's just dropped).
    val legacy =
      """
      {
        "module":"m",
        "variant":"debug",
        "previews":[{
          "id":"X","functionName":"X","className":"K",
          "params":{"name":null,"group":"g"},
          "renderOutput":"renders/X.png"
        }]
      }
      """
        .trimIndent()
    val parsed = json.decodeFromString(PreviewManifest.serializer(), legacy)
    assertEquals("X", parsed.previews.single().id)
    assertEquals("g", parsed.previews.single().params.group)
    // Default capture list is a single empty capture.
    assertEquals(1, parsed.previews.single().captures.size)
  }
}
