package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDataMapEntry
import ee.schimke.composeai.rcplayer.protocol.RcDataMapLookup
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcIdList
import ee.schimke.composeai.rcplayer.protocol.RcIdLookup
import ee.schimke.composeai.rcplayer.protocol.RcIdMap
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcMatrixConstant
import ee.schimke.composeai.rcplayer.protocol.RcMatrixExpression
import ee.schimke.composeai.rcplayer.protocol.RcMatrixVectorMath
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcRootContentBehavior
import ee.schimke.composeai.rcplayer.protocol.RcRootContentDescription
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextLength
import ee.schimke.composeai.rcplayer.protocol.RcTextLookup
import ee.schimke.composeai.rcplayer.protocol.RcTextLookupInt
import ee.schimke.composeai.rcplayer.protocol.RcTextMerge
import ee.schimke.composeai.rcplayer.protocol.RcTextSubtext
import ee.schimke.composeai.rcplayer.protocol.RcTextTransform
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueStringChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RcPlayerStateTest {
  @Test
  fun clickActionsMutateValuesInWireOrderAndEmitTypedHostEvents() {
    val events = mutableListOf<RcPlayerEvent>()
    var invalidations = 0
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        listOf(RcTextData(11, "selected"), RcFloatConstant(42, RcFloatWord.literal(7.5f))),
      )
    val state =
      RcPlayerState(document, eventSink = events::add, onInvalidated = { invalidations++ })

    state.executeClick(
      RcClickActionBlock(
        listOf(
          RcLinkedNode.Operation(RcValueIntegerChangeAction(20, 4)),
          RcLinkedNode.Operation(RcValueStringChangeAction(21, 11)),
          RcLinkedNode.Operation(RcValueFloatChangeAction(22, RcFloatWord(0x7fc0002a))),
          RcLinkedNode.Operation(RcHostAction(77)),
        )
      )
    )

    assertEquals(4, state.integer(20))
    assertEquals("selected", state.text(21))
    assertEquals(7.5f, state.resolve(RcFloatWord(0x7fc00016)))
    assertEquals(listOf<RcPlayerEvent>(RcPlayerEvent.HostAction(77)), events)
    assertEquals(1, invalidations)
  }

  @Test
  fun dynamicFloatListsResolveReferencesUpdateAndResetWhenResized() {
    val state =
      RcPlayerState(
        RcDocument(
          RcHeader(RcVersion(0, 1, 0)),
          listOf(
            RcFloatConstant(7, RcFloatWord.literal(3f)),
            RcFloatConstant(8, RcFloatWord.literal(1f)),
            RcFloatConstant(9, RcFloatWord.literal(12f)),
          ),
        )
      )
    val list = RcDynamicFloatList(0x200070, RcFloatWord(0x7fc00000 or 7))

    state.applyDataOperation(list)
    state.applyDataOperation(
      RcUpdateDynamicFloatList(0x200070, RcFloatWord(0x7fc00000 or 8), RcFloatWord(0x7fc00000 or 9))
    )
    state.applyDataOperation(
      RcUpdateDynamicFloatList(0x200070, RcFloatWord.literal(99f), RcFloatWord.literal(100f))
    )

    state.applyFloatExpression(
      RcFloatExpression(
        10,
        listOf(
          RcFloatWord(0xff800000.toInt() or 0x200070),
          RcFloatWord.literal(1f),
          RcFloatExpressionEvaluator.operatorWord(RcFloatExpressionEvaluator.OFFSET + 32),
        ),
        null,
      )
    )

    assertContentEquals(floatArrayOf(0f, 12f, 0f), state.floatValues(0x200070))
    assertEquals(12f, state.resolve(RcFloatWord(0x7fc00000 or 10)))
    state.setFloat(7, 2f)
    state.applyDataOperation(list)
    assertContentEquals(floatArrayOf(0f, 0f), state.floatValues(0x200070))
  }

  @Test
  fun integerExpressionsResolveDynamicIdsAndFeedTheIntegerStore() {
    val state =
      RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(RcIntegerConstant(7, 11))))
    val mask = (1 shl 0) or (1 shl 2)

    state.applyIntegerExpression(
      RcIntegerExpression(8, mask, listOf(7, 5, RcIntegerExpression.ADD))
    )

    assertEquals(16, state.integer(8))
  }

  @Test
  fun colorExpressionsCoverInterpolationHsvArgbAndDynamicAlpha() {
    val state =
      RcPlayerState(
        RcDocument(
          RcHeader(RcVersion(0, 1, 0)),
          listOf(
            RcColorConstant(7, 0xff000000.toInt()),
            RcColorConstant(8, 0xffffffff.toInt()),
            RcFloatConstant(20, RcFloatWord.literal(.25f)),
          ),
        )
      )

    state.applyColorExpression(
      RcColorExpression(30, RcColorExpression.ID_ID_INTERPOLATE, 7, 8, .5f.toRawBits())
    )
    state.applyColorExpression(
      RcColorExpression(
        31,
        (128 shl 16) or RcColorExpression.HSV_MODE,
        .5f.toRawBits(),
        1f.toRawBits(),
        1f.toRawBits(),
      )
    )
    state.applyColorExpression(
      RcColorExpression(
        32,
        (512 shl 16) or RcColorExpression.ARGB_MODE,
        .25f.toRawBits(),
        .5f.toRawBits(),
        .75f.toRawBits(),
      )
    )
    state.applyColorExpression(
      RcColorExpression(
        33,
        (20 shl 16) or RcColorExpression.IDARGB_MODE,
        1f.toRawBits(),
        0f.toRawBits(),
        0f.toRawBits(),
      )
    )

    assertEquals(0xffbababa.toInt(), state.color(30))
    assertEquals(0x8000ffff.toInt(), state.color(31))
    assertEquals(0x804080bf.toInt(), state.color(32))
    assertEquals(0x40ff0000, state.color(33))
  }

  @Test
  fun colorThemeUsesTheSameLightExactMatchAsAndroidX() {
    val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), emptyList()))
    val operation = RcColorTheme(40, 2, 3, 4, 0xffeeeeee.toInt(), 0xff111111.toInt())

    state.applyColorTheme(operation, RcTheme.LIGHT)
    assertEquals(0xffeeeeee.toInt(), state.color(40))
    state.applyColorTheme(operation, RcTheme.DARK)
    assertEquals(0xff111111.toInt(), state.color(40))
    state.applyColorTheme(operation, RcTheme.UNSPECIFIED)
    assertEquals(0xff111111.toInt(), state.color(40))
  }

  @Test
  fun colorAttributesMatchAndroidXNormalizedComponents() {
    val state =
      RcPlayerState(
        RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(RcColorConstant(7, 0x804080c0.toInt())))
      )
    val expected =
      listOf(7f / 12f, 2f / 3f, 192f / 255f, 64f / 255f, 128f / 255f, 192f / 255f, 128f / 255f)

    expected.forEachIndexed { type, value ->
      val outId = 20 + type
      state.applyColorAttribute(RcColorAttribute(outId, 7, type))
      assertEquals(value, state.resolve(RcFloatWord(0x7fc00000 + outId)), 0.000001f)
    }
  }

  @Test
  fun imageAttributesUseAuthoritativeDeclaredDimensions() {
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0)),
        listOf(RcBitmapData(7, 13, 17, RcBitmapData.TYPE_RAW8, 0, ByteArray(221))),
      )
    val state = RcPlayerState(document)

    state.applyImageAttribute(RcImageAttribute(20, 7, RcImageAttribute.IMAGE_WIDTH, emptyList()))
    state.applyImageAttribute(RcImageAttribute(21, 7, RcImageAttribute.IMAGE_HEIGHT, emptyList()))

    assertEquals(13f, state.resolve(RcFloatWord(0x7fc00014)))
    assertEquals(17f, state.resolve(RcFloatWord(0x7fc00015)))
  }

  @Test
  fun exposesLastRootMetadataAndNamedVariables() {
    val first = RcRootContentBehavior(0, 34, 2, 1)
    val last = RcRootContentBehavior(0, 34, 2, 4)
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0)),
        listOf(
          first,
          RcTextData(8, "Weather card"),
          RcRootContentDescription(8),
          RcNamedVariable(9, 1, "USER:temperature"),
          last,
        ),
      )

    val state = RcPlayerState(document)

    assertEquals(last, state.rootContentBehavior)
    assertEquals("Weather card", state.rootContentDescription)
    assertEquals(9, state.namedVariable("USER:temperature")?.id)
  }

  @Test
  fun appliesTypedHostOverridesByAndroidXVariableName() {
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0)),
        listOf(RcNamedVariable(9, RcNamedVariable.FLOAT_TYPE, "USER:temperature")),
      )

    val state = RcPlayerState(document, mapOf("USER:temperature" to RcNamedValue.FloatValue(21.5f)))

    assertEquals(
      21.5f,
      state.resolve(ee.schimke.composeai.rcplayer.protocol.RcFloatWord(0x7fc00009)),
    )
    assertFailsWith<IllegalArgumentException> {
      state.setNamedValue("USER:temperature", RcNamedValue.Text("wrong type"))
    }
  }

  @Test
  fun evaluatesAndroidXMatrixVectorMath() {
    val matrix =
      RcMatrixConstant(
        20,
        0,
        listOf(1f, 0f, 0f, 2f, 0f, 1f, 0f, 3f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
          .map(RcFloatWord::literal),
      )
    val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(matrix)))

    state.applyMatrixVectorMath(
      RcMatrixVectorMath(
        type = 0,
        outputs = listOf(30, 31),
        matrixId = 20,
        inputs = listOf(RcFloatWord.literal(4f), RcFloatWord.literal(5f)),
      )
    )

    assertEquals(6f, state.resolve(RcFloatWord(0x7fc00000 or 30)))
    assertEquals(8f, state.resolve(RcFloatWord(0x7fc00000 or 31)))
  }

  @Test
  fun matrixExpressionResolvesVariablesBeforeVectorMath() {
    val state =
      RcPlayerState(
        RcDocument(
          RcHeader(RcVersion(0, 1, 0)),
          listOf(RcFloatConstant(12, RcFloatWord.literal(6f))),
        )
      )
    val expression =
      RcMatrixExpression(
        id = 20,
        type = 0,
        expression =
          listOf(
            RcFloatWord(0x7fc00000 or 12),
            RcFloatWord.literal(7f),
            RcMatrixEvaluator.operator(8),
          ),
      )

    state.applyMatrixExpression(expression)
    state.applyMatrixVectorMath(
      RcMatrixVectorMath(
        type = 0,
        outputs = listOf(30, 31),
        matrixId = 20,
        inputs = listOf(RcFloatWord.literal(1f), RcFloatWord.literal(2f)),
      )
    )

    assertEquals(7f, state.resolve(RcFloatWord(0x7fc00000 or 30)))
    assertEquals(9f, state.resolve(RcFloatWord(0x7fc00000 or 31)))
  }

  @Test
  fun evaluatesTextOperationsInWireOrder() {
    val state =
      RcPlayerState(
        RcDocument(
          RcHeader(RcVersion(0, 1, 0)),
          listOf(RcTextData(1, "Remote"), RcTextData(2, " Compose")),
        )
      )

    state.applyTextOperation(RcTextMerge(3, 1, 2))
    state.applyTextOperation(RcTextSubtext(4, 3, RcFloatWord.literal(7f), RcFloatWord.literal(-1f)))
    state.applyTextOperation(RcTextLength(5, 4))
    state.applyTextOperation(
      RcTextTransform(6, 3, RcFloatWord.literal(0f), RcFloatWord.literal(-1f), 2)
    )

    assertEquals("Compose", state.text(4))
    assertEquals(7f, state.resolve(RcFloatWord(0x7fc00000 or 5)))
    assertEquals("REMOTE COMPOSE", state.text(6))
  }

  @Test
  fun evaluatesAndroidXCollectionLookupsIntoTypedStores() {
    val state =
      RcPlayerState(
        RcDocument(
          RcHeader(RcVersion(0, 1, 0)),
          listOf(
            RcTextData(1, "zero"),
            RcTextData(2, "one"),
            RcTextData(3, "temperature"),
            RcIntegerConstant(4, 1),
            RcFloatConstant(5, RcFloatWord.literal(21.5f)),
            RcIdList(10, listOf(1, 2)),
            RcIdMap(11, listOf(RcDataMapEntry("temperature", RcIdMap.TYPE_FLOAT, 5))),
          ),
        )
      )

    state.applyTextOperation(RcTextLookup(20, 10, RcFloatWord.literal(1f)))
    state.applyTextOperation(RcTextLookupInt(21, 10, 4))
    state.applyDataOperation(RcIdLookup(22, 10, RcFloatWord.literal(0f)))
    state.applyDataOperation(RcDataMapLookup(23, 11, 3))

    assertEquals("one", state.text(20))
    assertEquals("one", state.text(21))
    assertEquals(1, state.integer(22))
    assertEquals(21.5f, state.resolve(RcFloatWord(0x7fc00000 or 23)))
  }
}
