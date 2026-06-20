package ee.schimke.composeai.data.sharedelement

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedElementModelsTest {

  private val json = Json { prettyPrint = false }

  @Test
  fun payloadRoundTripsThroughJson() {
    val payload =
      SharedElementPayload(
        findings =
          listOf(
            SharedElementFinding(
              key = "avatar",
              status = SharedElementMatchStatus.MATCHED,
              modifier = "sharedElement",
              targetBoundsInRoot = "0,0,120,120",
            ),
            SharedElementFinding(key = "badge", status = SharedElementMatchStatus.UNMATCHED),
            SharedElementFinding(
              key = "title",
              status = SharedElementMatchStatus.MULTIPLE_MATCHES,
              occurrences = 2,
              modifier = "sharedBounds",
            ),
          )
      )

    val decoded = json.decodeFromString<SharedElementPayload>(json.encodeToString(payload))

    assertEquals(payload, decoded)
  }

  @Test
  fun derivedCountsAreNotSerialisedButAreComputedOnRead() {
    val payload =
      SharedElementPayload(
        findings =
          listOf(
            SharedElementFinding("a", SharedElementMatchStatus.MATCHED),
            SharedElementFinding("b", SharedElementMatchStatus.MATCHED),
            SharedElementFinding("c", SharedElementMatchStatus.UNMATCHED),
            SharedElementFinding("d", SharedElementMatchStatus.MULTIPLE_MATCHES, occurrences = 3),
          )
      )

    assertEquals(2, payload.matchedCount)
    assertEquals(1, payload.unmatchedCount)
    assertEquals(1, payload.multipleMatchesCount)
    assertTrue(payload.hasProblems)

    // The derived tallies live in the class body, so they must not leak into the wire form.
    val encoded = json.encodeToString(payload)
    assertFalse(encoded.contains("matchedCount"))
    assertFalse(encoded.contains("hasProblems"))
  }

  @Test
  fun wellFormedTransitionHasNoProblems() {
    val payload =
      SharedElementPayload(
        findings =
          listOf(
            SharedElementFinding("avatar", SharedElementMatchStatus.MATCHED),
            SharedElementFinding("title", SharedElementMatchStatus.MATCHED),
          )
      )

    assertFalse(payload.hasProblems)
    assertEquals(0, payload.unmatchedCount)
  }

  @Test
  fun emptyPayloadIsTheDefault() {
    assertEquals(emptyList<SharedElementFinding>(), SharedElementPayload().findings)
    assertFalse(SharedElementPayload().hasProblems)
  }
}
