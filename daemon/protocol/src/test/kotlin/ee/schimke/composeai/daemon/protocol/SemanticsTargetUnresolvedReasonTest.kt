package ee.schimke.composeai.daemon.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android backend ferries a [SemanticsTargetUnresolvedReason] from the Robolectric sandbox to
 * the host as a JSON string across the do-not-acquire bridge (issue #1784), so the encode (sandbox,
 * `encodeDefaults = true`) → decode (host, `ignoreUnknownKeys = true`) round-trip has to be exact —
 * including the `@SerialName` enum spellings the wire + the TS client read.
 */
class SemanticsTargetUnresolvedReasonTest {

  private val encode = Json { encodeDefaults = true }
  private val decode = Json { ignoreUnknownKeys = true }

  @Test
  fun roundTripsAmbiguousWithCandidates() {
    val original =
      SemanticsTargetUnresolvedReason(
        code = SemanticsTargetUnresolvedCode.AMBIGUOUS,
        target = SemanticsInputTarget(testTag = "row"),
        matchCount = 2,
        candidates =
          listOf(
            SemanticsTargetCandidate(
              ref = "r/tag:row[0]",
              testTag = "row",
              boundsInRoot = "0,0,2,2",
            ),
            SemanticsTargetCandidate(
              ref = "r/tag:row[1]",
              testTag = "row",
              boundsInRoot = "0,2,2,4",
            ),
          ),
      )
    val json = encode.encodeToString(SemanticsTargetUnresolvedReason.serializer(), original)
    val back = decode.decodeFromString(SemanticsTargetUnresolvedReason.serializer(), json)
    assertEquals(original, back)
  }

  @Test
  fun enumSerialNamesAreWireStable() {
    val json =
      encode.encodeToString(
        SemanticsTargetUnresolvedReason.serializer(),
        SemanticsTargetUnresolvedReason(code = SemanticsTargetUnresolvedCode.NO_SEMANTICS_ROOT),
      )
    assertTrue("expected camelCase serial name in $json", json.contains("\"noSemanticsRoot\""))
  }

  @Test
  fun decodesWithDefaultsWhenFieldsOmitted() {
    val back =
      decode.decodeFromString(
        SemanticsTargetUnresolvedReason.serializer(),
        """{"code":"noMatch"}""",
      )
    assertEquals(SemanticsTargetUnresolvedCode.NO_MATCH, back.code)
    assertEquals(0, back.matchCount)
    assertTrue(back.candidates.isEmpty())
  }
}
