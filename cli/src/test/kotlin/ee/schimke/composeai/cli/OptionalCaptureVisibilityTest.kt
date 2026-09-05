package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.CaptureResult
import ee.schimke.composeai.previewdata.PreviewParams
import ee.schimke.composeai.previewdata.PreviewResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #5174: an `optional` capture that produced no PNG was reported through two channels that
 * disagreed. The row was tagged with a bare `[no PNG]`, indistinguishable from a genuine render
 * failure, while the summary underneath — and `counts.missing` — correctly left it out; and the
 * `counts` buckets then didn't add up to `total`, because the row belonged to none of them.
 *
 * These tests pin both halves: the tag says which misses are expected, and the buckets partition.
 */
class OptionalCaptureVisibilityTest {

  private fun result(
    id: String,
    kind: String = "COMPOSE",
    captures: List<CaptureResult>,
  ): PreviewResult =
    PreviewResult(
      id = id,
      module = "samples:demo",
      functionName = id.substringAfterLast('.'),
      className = id.substringBeforeLast('.'),
      params = PreviewParams(kind = kind),
      captures = captures,
      pngPath = captures.firstOrNull()?.pngPath,
      sha256 = captures.firstOrNull()?.sha256,
      changed = captures.firstOrNull()?.changed,
    )

  @Test
  fun `a required miss keeps the bare no-PNG tag`() {
    val r = result("p.Broken", captures = listOf(CaptureResult(pngPath = null)))
    assertEquals(" [no PNG]", previewStatusTag(r))
  }

  @Test
  fun `an optional miss is tagged as optional`() {
    // The non-launcher activity previews of issue #5174: discovery marks them best-effort because
    // a real screen may need intent extras it can't guess.
    val r =
      result(
        "activity__RedirectUriReceiverActivity",
        captures = listOf(CaptureResult(pngPath = null, optional = true)),
      )
    assertEquals(NO_PNG_OPTIONAL_TAG, previewStatusTag(r))
  }

  @Test
  fun `a required miss beside an optional one still reads as a failure`() {
    val r =
      result(
        "p.Mixed",
        captures =
          listOf(CaptureResult(pngPath = null, optional = true), CaptureResult(pngPath = null)),
      )
    assertEquals(" [no PNG]", previewStatusTag(r))
  }

  @Test
  fun `a kind that never emits a PNG says so`() {
    val r = result("p.Spatial", kind = "XR_SUBSPACE", captures = listOf(CaptureResult()))
    assertEquals(NO_PNG_BY_DESIGN_TAG, previewStatusTag(r))
  }

  @Test
  fun `rendered previews keep their changed and empty tags`() {
    val rendered = CaptureResult(pngPath = "/r/a.png", sha256 = "a", changed = false)
    assertEquals("", previewStatusTag(result("p.Same", captures = listOf(rendered))))
    assertEquals(
      " [changed]",
      previewStatusTag(result("p.Diff", captures = listOf(rendered.copy(changed = true)))),
    )
  }

  @Test
  fun `a bare no-PNG tag appears on the gate's rows and only those`() {
    // The invariant the two channels used to break: a bare `[no PNG]` — at the preview level or on
    // one of the capture lines under it — marks exactly the rows the "produced no PNG for N of M
    // preview(s)" summary enumerates. A partially-rendered fan-out is why the capture lines count:
    // its first capture rendered, so the preview-level tag stays `[changed]`/empty and the miss is
    // reported on the capture line that has it.
    val results =
      listOf(
        result("p.Ok", captures = listOf(CaptureResult(pngPath = "/r/ok.png", sha256 = "a"))),
        result("p.Broken", captures = listOf(CaptureResult(pngPath = null))),
        result("p.Optional", captures = listOf(CaptureResult(pngPath = null, optional = true))),
        result("p.Spatial", kind = "XR_SUBSPACE", captures = listOf(CaptureResult())),
        result(
          "p.FanOut",
          captures =
            listOf(
              CaptureResult(advanceTimeMillis = 0, pngPath = "/r/a.png", sha256 = "a"),
              CaptureResult(advanceTimeMillis = 500, pngPath = null),
            ),
        ),
      )
    fun tagsBareNoPng(r: PreviewResult) =
      previewStatusTag(r) == " [no PNG]" ||
        r.captures.any { captureStatusTag(it, r.params.kind) == " [no PNG]" }

    assertEquals(
      previewsMissingPng(results).map { it.id },
      results.filter { tagsBareNoPng(it) }.map { it.id },
    )
  }

  @Test
  fun `capture tags distinguish an optional row from a failed one`() {
    assertEquals(
      " [no PNG]",
      captureStatusTag(CaptureResult(advanceTimeMillis = 0, pngPath = null), "COMPOSE"),
    )
    assertEquals(
      NO_PNG_OPTIONAL_TAG,
      captureStatusTag(
        CaptureResult(advanceTimeMillis = 0, pngPath = null, optional = true),
        "COMPOSE",
      ),
    )
    assertEquals(NO_PNG_BY_DESIGN_TAG, captureStatusTag(CaptureResult(), "XR_SUBSPACE"))
    assertEquals(
      " [changed]",
      captureStatusTag(CaptureResult(pngPath = "/r/a.png", changed = true), "COMPOSE"),
    )
    assertEquals(
      "",
      captureStatusTag(CaptureResult(pngPath = "/r/a.png", changed = false), "COMPOSE"),
    )
  }

  @Test
  fun `counts partition total`() {
    // The reported run in miniature: one changed, one unchanged, one required miss, one optional
    // miss. `0 + 33 + 1 != 37` was the bug; here `1 + 1 + 1 + 1 == 4`.
    val results =
      listOf(
        result(
          "p.Changed",
          captures = listOf(CaptureResult(pngPath = "/r/c.png", sha256 = "c", changed = true)),
        ),
        result(
          "p.Same",
          captures = listOf(CaptureResult(pngPath = "/r/s.png", sha256 = "s", changed = false)),
        ),
        result("p.Broken", captures = listOf(CaptureResult(pngPath = null))),
        result("p.Optional", captures = listOf(CaptureResult(pngPath = null, optional = true))),
      )
    val counts = previewCountsOf(results)
    assertEquals(
      PreviewCounts(total = 4, changed = 1, unchanged = 1, missing = 1, skipped = 1),
      counts,
    )
    assertEquals(counts.total, counts.changed + counts.unchanged + counts.missing + counts.skipped)
  }

  @Test
  fun `every classifiable shape lands in exactly one bucket`() {
    val shapes =
      listOf(
        result("p.Changed", captures = listOf(CaptureResult(pngPath = "/r/c.png", changed = true))),
        result("p.Same", captures = listOf(CaptureResult(pngPath = "/r/s.png", changed = false))),
        result("p.Broken", captures = listOf(CaptureResult(pngPath = null))),
        result("p.Optional", captures = listOf(CaptureResult(pngPath = null, optional = true))),
        result("p.Spatial", kind = "XR_SUBSPACE", captures = listOf(CaptureResult())),
        result("p.NoCaptures", captures = emptyList()),
        result(
          "p.PartialFanOut",
          captures = listOf(CaptureResult(pngPath = "/r/a.png"), CaptureResult(pngPath = null)),
        ),
      )
    val counts = previewCountsOf(shapes)
    assertEquals(shapes.size, counts.changed + counts.unchanged + counts.missing + counts.skipped)
    assertEquals(
      listOf(
        PreviewCountBucket.CHANGED,
        PreviewCountBucket.UNCHANGED,
        PreviewCountBucket.MISSING,
        PreviewCountBucket.SKIPPED,
        PreviewCountBucket.SKIPPED,
        PreviewCountBucket.SKIPPED,
        PreviewCountBucket.UNCHANGED,
      ),
      shapes.map { previewCountBucket(it) },
    )
  }
}
