package ee.schimke.composeai.daemon

/**
 * Pure verdict logic for the `assert.pixels` recording event (issues #1967, #2519): diff a recorded
 * frame against a committed baseline PNG and decide pass/fail, reusing the [PixelDiff] comparator
 * that the preview-review pipeline already uses. Kept free of file IO and the recording session so
 * it is unit-testable from raw PNG bytes — the backend session (`DesktopRecordingSession` /
 * `AndroidRecordingSession`) handles reading the frame + baseline off disk and turning this verdict
 * into `RecordingScriptEvidence`.
 *
 * Lives in `:daemon:core` (alongside [PixelDiff]) so **both** the desktop and Android recording
 * sessions share one implementation: each writes its frames the same way (a `frame-NNNNN.png` per
 * frame) and feeds the frame + baseline bytes here.
 *
 * Fail-closed: a missing baseline or a missing recorded frame is a [AssertionVerdict.Failed], never
 * a silent pass — the script asked to pin pixels, so an un-runnable check is a failure. `PixelDiff`
 * itself reports a dimension mismatch (different-sized baseline) as a non-`ok` result, so that path
 * also fails with a clear message.
 */
fun pixelAssertVerdict(
  actualPng: ByteArray?,
  baselinePng: ByteArray?,
  tolerance: PixelDiffTolerance = PixelDiffTolerance.DEFAULT,
): AssertionVerdict {
  if (baselinePng == null) {
    return AssertionVerdict.Failed(
      "assert.pixels: baseline PNG not found (set inputText / --baseline-dir)"
    )
  }
  if (actualPng == null) {
    return AssertionVerdict.Failed("assert.pixels: no recorded frame was captured for this tMs")
  }
  val result = PixelDiff.compare(actualPng, baselinePng, tolerance)
  if (result.ok) {
    return AssertionVerdict.Passed
  }
  return AssertionVerdict.Failed(
    "assert.pixels: ${result.message} " +
      "(offending=${result.offendingPixelCount}/${result.totalPixels}, maxDelta=${result.maxDelta})"
  )
}
