package ee.schimke.composeai.rcplayer.metrics

import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcDrawText
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextFromFloat
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextMeasure
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * These fixtures are only useful if a lane can *play* them, and the failure mode if it can't is
 * quiet: an unknown opcode makes `RemoteComposeBuffer.inflateFromBuffer` return, dropping every
 * operation after it, so a broken fixture renders as a truncated image rather than an error. So the
 * tests here are mostly about the document being well-formed on the wire and internally consistent,
 * not about pixels.
 */
class RcTextMetricDocumentsTest {

  @Test
  fun everyFixtureSurvivesTheRealWireCodec() {
    RcTextMetricDocuments.all().forEach { fixture ->
      val bytes = RcDocumentCodec.encode(fixture.document)
      assertEquals(
        fixture.document,
        RcDocumentCodec.decode(bytes),
        "${fixture.id} did not round-trip through the wire codec",
      )
    }
  }

  @Test
  fun theCardMeasuresEveryGuideExactlyOnce() {
    val measures =
      RcTextMetricDocuments.metricCard().document.operations.filterIsInstance<RcTextMeasure>()

    assertEquals(
      RcTextGuide.entries.size,
      measures.size,
      "the card must measure each guide once and nothing else",
    )
    assertEquals(
      RcTextGuide.entries.map { it.type }.toSet(),
      measures.map { it.type }.toSet(),
      "every guide's packed measurement type must reach the document",
    )
    assertEquals(
      measures.size,
      measures.map { it.outId }.toSet().size,
      "two guides sharing a float id would silently overwrite each other",
    )
  }

  @Test
  fun everyMeasuredFloatIsActuallyDrawn() {
    // A guide that is measured and then never referenced is invisible, and invisible is exactly the
    // failure this whole harness exists to stop happening to text metrics.
    RcTextMetricDocuments.all().forEach { fixture ->
      val measured =
        fixture.document.operations.filterIsInstance<RcTextMeasure>().map { it.outId }.toSet()
      val referenced = fixture.document.operations.flatMap(::referencedFloatIds).toSet()
      assertEquals(
        emptySet(),
        measured - referenced,
        "${fixture.id} measures floats it never draws",
      )
    }
  }

  @Test
  fun theCapAndXHeightProbesMeasureTheirOwnStrings() {
    val document = RcTextMetricDocuments.metricCard().document
    val texts = document.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    val measures = document.operations.filterIsInstance<RcTextMeasure>().associateBy { it.outId }

    // Cap height and x-height are the ink tops of `H` and `x`, measured by the lane with the same
    // paint as the specimen. If they ever pointed at the specimen instead they would read as its
    // tallest ink, which for this string is the `H` — the same number, from the wrong question, and
    // wrong the moment the specimen changes.
    val capMeasure =
      assertNotNull(
        measures.values.firstOrNull { texts[it.textId] == RcMetricProbe.CAP.text },
        "no measurement reads the cap-height probe",
      )
    assertEquals(RcTextGuide.CAP_TOP.type, capMeasure.type)
    val xMeasure =
      assertNotNull(
        measures.values.firstOrNull { texts[it.textId] == RcMetricProbe.X_HEIGHT.text },
        "no measurement reads the x-height probe",
      )
    assertEquals(RcTextGuide.X_TOP.type, xMeasure.type)
  }

  @Test
  fun textIdsAreUniqueWithinAFixture() {
    RcTextMetricDocuments.all().forEach { fixture ->
      val declared =
        fixture.document.operations.filterIsInstance<RcTextData>().map { it.id } +
          fixture.document.operations.filterIsInstance<RcTextFromFloat>().map { it.outId }
      assertEquals(
        declared.size,
        declared.toSet().size,
        "${fixture.id} declares the same text id twice",
      )
    }
  }

  @Test
  fun everyAllocatedIdIsAboveTheSystemRange() {
    // `RemoteComposeState.START_ID`. Below it the ids belong to the player — 10..18 alone are
    // `ID_OFFSET_TO_UTC` through `ID_ACCELERATION_Y` — and a listener registry keyed by the bare
    // number means a document reusing one gets its text invalidated by unrelated sensor traffic.
    // Nothing here fails at render time, which is exactly why it needs a test.
    val startId = 42
    RcTextMetricDocuments.all().forEach { fixture ->
      val allocated =
        fixture.document.operations.filterIsInstance<RcTextData>().map { "text" to it.id } +
          fixture.document.operations.filterIsInstance<RcTextFromFloat>().map {
            "text" to it.outId
          } +
          fixture.document.operations.filterIsInstance<RcColorConstant>().map { "color" to it.id } +
          fixture.document.operations.filterIsInstance<RcTextMeasure>().map { "float" to it.outId }
      allocated.forEach { (kind, id) ->
        assertTrue(id >= startId, "${fixture.id} allocates $kind id $id below START_ID ($startId)")
      }
    }
  }

  @Test
  fun everyDrawnTextIdIsDeclared() {
    RcTextMetricDocuments.all().forEach { fixture ->
      val declared =
        (fixture.document.operations.filterIsInstance<RcTextData>().map { it.id } +
            fixture.document.operations.filterIsInstance<RcTextFromFloat>().map { it.outId })
          .toSet()
      val drawn =
        fixture.document.operations.filterIsInstance<RcDrawText>().map { it.textId } +
          fixture.document.operations.filterIsInstance<RcCoreText>().map { it.textId }
      assertEquals(
        emptySet(),
        drawn.toSet() - declared,
        "${fixture.id} draws a text id nothing declares",
      )
    }
  }

  @Test
  fun theSemanticAlignmentsAreExercisedInBothDirections() {
    // `START` and `END` are the only alignments whose meaning depends on paragraph direction, and
    // on English text they land exactly where `LEFT` and `RIGHT` do. A matrix built only from LTR
    // text would therefore pass a lane that had hard-coded start→left and is wrong for every RTL
    // user, so each of the two is drawn once in each direction.
    val modes = RcTextMetricDocuments.LAYOUT_MODES
    listOf(RcTextLayout.ALIGN_START, RcTextLayout.ALIGN_END).forEach { align ->
      val alignmentFixtures = modes.filter {
        it.align == align && it.specimen != RcTextMetricDocuments.WRAPPING_SPECIMEN
      }
      assertTrue(
        alignmentFixtures.any { it.specimen == RcTextMetricDocuments.ALIGNMENT_SPECIMEN } &&
          alignmentFixtures.any { it.specimen == RcTextMetricDocuments.RTL_ALIGNMENT_SPECIMEN },
        "alignment $align is only exercised in one direction",
      )
    }
  }

  @Test
  fun theLayoutModesCoverEveryOverflowAndAlignment() {
    val modes = RcTextMetricDocuments.LAYOUT_MODES
    assertEquals(
      setOf(
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.OVERFLOW_VISIBLE,
        RcTextLayout.OVERFLOW_ELLIPSIS,
        RcTextLayout.OVERFLOW_START_ELLIPSIS,
        RcTextLayout.OVERFLOW_MIDDLE_ELLIPSIS,
      ),
      modes.map { it.overflow }.toSet(),
      "an overflow mode with no fixture is a mode nobody will ever diff",
    )
    assertEquals(
      setOf(
        RcTextLayout.ALIGN_LEFT,
        RcTextLayout.ALIGN_RIGHT,
        RcTextLayout.ALIGN_CENTER,
        RcTextLayout.ALIGN_JUSTIFY,
        RcTextLayout.ALIGN_START,
        RcTextLayout.ALIGN_END,
      ),
      modes.map { it.align }.toSet(),
    )
    assertTrue(modes.any { it.maxLines == 1 }, "single-line is half the point")
    assertTrue(modes.any { it.maxLines > 1 }, "multiline is the other half")
    assertTrue(
      modes.any { it.lineHeightAdd != 0f } && modes.any { it.lineHeightMultiplier != 1f },
      "line height moves every baseline after the first without touching a glyph, which is " +
        "precisely the divergence a pixel diff cannot name",
    )
  }

  @Test
  fun everyFixtureLinksIntoATree() {
    // The three AndroidX-backed harnesses tolerate an unterminated container at EOF, so a fixture
    // missing its root `ContainerEnd` renders perfectly on the very lanes it was developed against
    // and then throws the moment this repo's own runtime — the path the CMP lanes take — tries to
    // link it. Encoding and decoding cleanly is not enough; the tree has to close.
    RcTextMetricDocuments.all().forEach { fixture ->
      RcDocumentLinker.link(RcDocumentCodec.decode(RcDocumentCodec.encode(fixture.document)))
    }
  }

  @Test
  fun everyDocumentCarriesTheProfiledHeader() {
    // `CoreText` (239) is registered only in AndroidX's profiled operation maps, and a reader that
    // meets an opcode it has no entry for **returns** — dropping every remaining operation. So a
    // fixture with a legacy header does not render a bit worse, it renders truncated, and the first
    // thing anyone would blame is the text. Pin the header rather than rediscover that.
    RcTextMetricDocuments.all().forEach { fixture ->
      val header = fixture.document.header
      assertTrue(header.modern, "${fixture.id} would fall back to the legacy header")
      assertEquals(fixture.width, header.width, "${fixture.id} header width")
      assertEquals(fixture.height, header.height, "${fixture.id} header height")
      // Both of these are small ints in adjacent properties, and swapping them is not an error a
      // reader reports — it renders, at the wrong density or without `CoreText`. Pin the values.
      assertEquals(
        RcHeaderValue.IntValue(512),
        header.properties.firstOrNull { it.key == 14 }?.value,
        "${fixture.id} DOC_PROFILES must carry RcProfiles.PROFILE_ANDROIDX",
      )
      assertEquals(
        RcHeaderValue.IntValue(2),
        header.properties.firstOrNull { it.key == 27 }?.value,
        "${fixture.id} DOC_DENSITY_BEHAVIOR must carry DENSITY_BEHAVIOR_DP",
      )
    }
  }

  @Test
  fun aLayoutRootIsFollowedDirectlyByItsComponent() {
    // An interposed `LayoutContent` between the root and its component makes the AOSP view player
    // build a tree it then never paints. The background modifiers still land, so the frame looks
    // plausible and merely has no text in it — the most expensive kind of wrong for a fixture whose
    // entire output is text.
    RcTextMetricDocuments.all()
      .map { it to it.document.operations.indexOfFirst { op -> op is RcRootLayout } }
      .filter { (_, rootIndex) -> rootIndex >= 0 }
      .forEach { (fixture, rootIndex) ->
        assertTrue(
          fixture.document.operations[rootIndex + 1] is RcBoxLayout,
          "${fixture.id} puts something between its layout root and its component",
        )
      }
  }

  @Test
  fun aLayoutFixtureMeasuresTheSameStringItLaysOut() {
    // The overlay's advance is only comparable to the laid-out line if both sides are the same
    // string. They are measured by different code — the player itself versus the host text stack —
    // which is the divergence the fixture is built to localise, so the *inputs* must match exactly.
    val fixture = RcTextMetricDocuments.layoutMode(RcTextMetricDocuments.LAYOUT_MODES.first())
    val layout = fixture.document.operations.filterIsInstance<RcCoreText>().single()
    val measure = fixture.document.operations.filterIsInstance<RcTextMeasure>().single()

    assertEquals(layout.textId, measure.textId)
    assertEquals(RcTextGuide.ADVANCE.type, measure.type)
  }

  @Test
  fun fixtureIdsAreUniqueAndFileSafe() {
    val ids = RcTextMetricDocuments.all().map { it.id }
    assertEquals(ids.size, ids.toSet().size, "two fixtures would write the same <id>.rc")
    ids.forEach { assertTrue(it.matches(Regex("[a-z0-9-]+")), "$it is not a safe file name") }
  }

  @Test
  fun theManifestIsTheShapeTheRenderHarnessesRead() {
    // `RcEmbeddedRenderHarness.Entry` / `RcViewPlayerRenderHarness` deserialize exactly this.
    val fixtures = RcTextMetricDocuments.all()
    val manifest = manifestJson(fixtures)

    fixtures.forEach {
      assertTrue(
        manifest.contains("""{"id": "${it.id}", "width": ${it.width}, "height": ${it.height}}"""),
        "manifest is missing a usable entry for ${it.id}:\n$manifest",
      )
    }
    assertTrue(manifest.trimStart().startsWith("["), "the manifest must be a JSON array")
  }

  @Test
  fun theWeightSweepAsksForEveryWeightSeparately() {
    val sweep = RcTextMetricDocuments.weightSweep()
    val measures = sweep.document.operations.filterIsInstance<RcTextMeasure>()

    // Two measurements per weight, and both matter. The advance alone cannot distinguish "the
    // requested weight reached no new face" from "this family is drawn duplexed, so its weights
    // deliberately share an advance" — the ink width, which tracks stem thickness, can.
    assertEquals(RcTextMetricDocuments.SWEEP_WEIGHTS.size * 2, measures.size)
    assertEquals(
      RcTextMetricDocuments.SWEEP_WEIGHTS.size,
      measures.count { it.type == RcTextGuide.ADVANCE.type },
    )
    assertEquals(
      RcTextMetricDocuments.SWEEP_WEIGHTS.size,
      measures.count { it.type == RcTextMeasurement.type(RcTextMeasurement.WIDTH) },
      "without a weight-sensitive number the sweep can only ever report the advance",
    )
    assertEquals(
      measures.size,
      measures.map { it.outId }.toSet().size,
      "each weight needs its own float, or the rules all land on the last one measured",
    )
    // 550 and 599 are the point of the sweep: they fall between the static instances a
    // weight-enumerated stylesheet ships, so they are where a nearest-instance fallback shows.
    assertTrue(RcTextMetricDocuments.SWEEP_WEIGHTS.containsAll(listOf(550, 599)))
  }

  private fun referencedFloatIds(operation: RcOperation): List<Int> =
    when (operation) {
      is RcDraw4 ->
        listOfNotNull(
          operation.first.referencedId,
          operation.second.referencedId,
          operation.third.referencedId,
          operation.fourth.referencedId,
        )
      is RcTextFromFloat -> listOfNotNull(operation.value.referencedId)
      else -> emptyList()
    }
}
