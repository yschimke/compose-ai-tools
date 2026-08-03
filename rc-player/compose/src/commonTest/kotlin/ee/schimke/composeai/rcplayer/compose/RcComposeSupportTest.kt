package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasContent
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionCall
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerAttribute
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHapticFeedback
import ee.schimke.composeai.rcplayer.protocol.RcHapticType
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcImpulseProcess
import ee.schimke.composeai.rcplayer.protocol.RcImpulseStart
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcMarqueeModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickType
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttributeType
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWakeIn
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RcComposeSupportTest {
  @Test
  fun portableAnimationSpecsAreSharedByWasmAndIosProfiles() {
    val document = RcDocument(header, listOf(animationSpec()))

    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16).fullyRenderable)
    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).fullyRenderable)
  }

  @Test
  fun particleExitVariantsAreRejectedClearlyOnBothCmpProfiles() {
    val document =
      RcDocument(header, listOf(animationSpec().copy(exitAnimation = RcLayoutAnimation.Particle)))

    val wasm = document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16)
    val ios = document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16)
    assertEquals("exit animation 7 requires ParticleAnimation", wasm.issues.single().detail)
    assertEquals(wasm.issues, ios.issues)
  }

  @Test
  fun impulseContainersAreSharedByWasmAndIosProfiles() {
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val document =
      RcDocument(
        header,
        listOf(
          RcImpulseStart(RcFloatWord.literal(1f), RcFloatWord.literal(0f)),
          RcImpulseProcess,
          end,
          end,
        ),
      )

    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16).fullyRenderable)
    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).fullyRenderable)
  }

  @Test
  fun timeSchedulingIsSharedByWasmAndIosProfiles() {
    val document =
      RcDocument(
        header,
        listOf(
          RcTimeAttribute(20, 0, RcTimeAttributeType.Second),
          RcWakeIn(RcFloatWord.literal(.5f)),
        ),
      )

    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16).fullyRenderable)
    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).fullyRenderable)
  }

  @Test
  fun hapticFeedbackIsAnActionInBothCmpProfiles() {
    val document =
      RcDocument(
        header,
        listOf(
          RcMultiClickModifier(RcMultiClickType.SINGLE),
          RcHapticFeedback(RcHapticType.Confirm),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )

    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16).fullyRenderable)
    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).fullyRenderable)
  }

  @Test
  fun marqueeIsSharedByWasmAndIosProfiles() {
    val document =
      RcDocument(
        header,
        listOf(
          RcMarqueeModifier(
            iterations = 3,
            animationMode = 1,
            repeatDelayMillis = RcFloatWord.literal(250f),
            initialDelayMillis = RcFloatWord.literal(500f),
            spacing = RcFloatWord.literal(12f),
            velocity = RcFloatWord.literal(40f),
          )
        ),
      )

    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16).fullyRenderable)
    assertTrue(document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).fullyRenderable)
  }

  @Test
  fun rejectsInvalidMarqueeTimelineBeforeRendering() {
    val issues =
      RcDocument(
          header,
          listOf(
            RcMarqueeModifier(
              iterations = 1,
              animationMode = 0,
              repeatDelayMillis = RcFloatWord.literal(Float.NaN),
              initialDelayMillis = RcFloatWord.literal(0f),
              spacing = RcFloatWord.literal(0f),
              velocity = RcFloatWord.literal(0f),
            )
          ),
        )
        .composeSupportReport()
        .issues
        .map { it.detail }

    assertEquals(
      listOf("repeat delay must be finite", "velocity must be greater than zero"),
      issues,
    )
  }

  @Test
  fun actionSupportRejectsMissingNamesMetadataAndExpressionsPrecisely() {
    val issues =
      RcDocument(
          header,
          listOf(
            RcHostNamedAction(10, RcHostNamedActionValue.None),
            RcHostMetadataAction(77, 11),
            RcValueIntegerExpressionChangeAction(20L, 31L),
            RcValueFloatExpressionChangeAction(21, 32),
          ),
        )
        .composeSupportReport()
        .issues
        .map { it.detail }

    assertEquals(
      listOf(
        "name text id 10 is not declared",
        "metadata text id 11 is not declared",
        "integer expression id 31 is not declared",
        "float expression id 32 is not declared",
      ),
      issues,
    )
  }

  @Test
  fun mapsEveryAndroidXAccessibilityRoleToCompose() {
    assertEquals(
      listOf(
        Role.Button,
        Role.Checkbox,
        Role.Switch,
        Role.RadioButton,
        Role.Tab,
        Role.Image,
        Role.DropdownList,
        Role.ValuePicker,
        Role.Carousel,
        null,
      ),
      (0..RcAccessibilitySemantics.ROLE_UNKNOWN).map(::androidXSemanticsRole),
    )
  }

  @Test
  fun accessibilitySupportValidatesTextAndActionBoundaries() {
    val semantics =
      RcAccessibilitySemantics(
        contentDescriptionId = 10,
        role = RcAccessibilitySemantics.ROLE_BUTTON,
        textId = 11,
        stateDescriptionId = 12,
        mode = RcAccessibilitySemantics.MODE_MERGE,
        enabled = false,
        clickable = false,
      )
    val valid =
      RcDocument(
        header,
        listOf(
          RcTextData(10, "Submit"),
          RcTextData(11, "Send"),
          RcTextData(12, "Unavailable"),
          semantics,
        ),
      )
    assertTrue(valid.composeSupportReport().fullyRenderable)

    val issues =
      RcDocument(
          header,
          listOf(
            RcRootLayout(1),
            RcLayoutContent(2),
            RcCanvasLayout(3, 30),
            semantics.copy(textId = 99, clickable = true),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
          ),
        )
        .composeSupportReport()
        .issues
        .map { it.detail }
    assertEquals(
      listOf(
        "content description text id 10 is not declared",
        "text text id 99 is not declared",
        "state description text id 12 is not declared",
        "clickable semantics requires a ClickModifierOperation on the same component",
      ),
      issues,
    )
  }

  @Test
  fun rejectsUnsupportedLayoutComputeVariantsPrecisely() {
    val document =
      RcDocument(
        header,
        listOf(
          ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList(
            42,
            ee.schimke.composeai.rcplayer.protocol.RcFloatWord.literal(6f),
          ),
          ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute(
            type = 9,
            boundsId = 42,
            animateChanges = true,
          ),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )

    val details =
      document
        .composeSupportReport()
        .issues
        .filter { it.operation == "LayoutComputeOperation" }
        .map { it.detail }
    assertEquals(
      listOf("type 9 is not implemented", "animated measure transitions are not implemented"),
      details,
    )
  }

  @Test
  fun alignByUsesAndroidXMaximumAnchorForEveryRowChild() {
    assertContentEquals(
      intArrayOf(20, 0, 30),
      alignByCrossPositions(100, 40, 4, listOf(10f, 30f, 0f)),
    )
    assertContentEquals(
      intArrayOf(50, 30, 60),
      alignByCrossPositions(100, 40, 2, listOf(10f, 30f, 0f)),
    )
  }

  private val header = RcHeader(RcVersion(0, 1, 0), modern = false)

  @Test
  fun reportsPaintSubcommandsThatTheRendererCannotHonor() {
    val document = RcDocument(header, listOf(RcPaintData(listOf(11))))

    val support = document.composeSupportReport()

    assertFalse(support.fullyRenderable)
    assertEquals("paint command 11 is not implemented", support.issues.single().detail)
  }

  @Test
  fun wasmProfileRejectsGraphicsLayersBeforeRendering() {
    val document =
      RcDocument(
        header,
        listOf(
          RcGraphicsLayerModifier(
            listOf(
              RcGraphicsLayerAttribute.FloatValue(
                RcGraphicsLayerModifier.ROTATION_Z,
                RcFloatWord.literal(-8f),
              )
            )
          )
        ),
      )

    val issue = document.composeSupportReport(RcOperationProfiles.CMP_WASM_ALPHA16).issues.single()

    assertEquals("ModifierGraphicsLayer", issue.operation)
    assertEquals("operation is excluded from the cmp-wasm-alpha16 profile", issue.detail)
    assertTrue(
      document.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).fullyRenderable,
      "The shared iOS renderer supports graphics layers even though the Wasm backend does not",
    )
  }

  @Test
  fun rejectsExtendedCoreTextFieldsUntilTheyAreBrowserVerified() {
    val document =
      RcDocument(
        header,
        listOf(
          RcTextStyle(
            listOf(
              RcTextStyleProperty.IntValue(1, 100),
              RcTextStyleProperty.FloatValue(12, RcFloatWord.literal(1f)),
            )
          )
        ),
      )

    val issue = document.composeSupportReport().issues.single()

    assertEquals("TextStyle", issue.operation)
    assertEquals("letter spacing is not implemented", issue.detail)
  }

  @Test
  fun rejectsLayoutComponentsOutsideARoot() {
    val document = RcDocument(header, listOf(RcCanvasContent(3), RcNoArg(RcOpcodes.CONTAINER_END)))

    val support = document.composeSupportReport()

    assertFalse(support.fullyRenderable)
    assertEquals("LayoutStructure", support.issues.single().operation)
    assertEquals(
      "Layout component appears outside a RootLayoutComponent",
      support.issues.single().detail,
    )
  }

  @Test
  fun acceptsCanvasContentWithinCanvasLayout() {
    val operations =
      listOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        RcLayoutContent(4),
        RcCanvasContent(5),
      ) + List(5) { RcNoArg(RcOpcodes.CONTAINER_END) }

    assertTrue(RcDocument(header, operations).composeSupportReport().fullyRenderable)
  }

  @Test
  fun acceptsDrawContentOnlyWhenAttachedToAComponentCanvasBlock() {
    val valid =
      listOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcBoxLayout(3, 30, 1, 4),
        RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
        RcNoArg(RcOpcodes.DRAW_CONTENT),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcLayoutContent(4),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    assertTrue(RcDocument(header, valid).composeSupportReport().fullyRenderable)

    val issue =
      RcDocument(header, listOf(RcNoArg(RcOpcodes.DRAW_CONTENT)))
        .composeSupportReport()
        .issues
        .single()
    assertEquals("DrawContent", issue.operation)
  }

  @Test
  fun acceptsTheImplementedBaselinePaintDelta() {
    val colorCommand = 4
    val strokeStyleCommand = 8 or (1 shl 16)
    val document =
      RcDocument(
        header,
        listOf(RcPaintData(listOf(colorCommand, 0xff123456.toInt(), strokeStyleCommand))),
      )

    assertTrue(document.composeSupportReport().fullyRenderable)
  }

  @Test
  fun acceptsRootContentCanvasLayoutWithImplementedDimensions() {
    val document =
      RcDocument(
        header,
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(100f)),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )

    assertTrue(document.composeSupportReport().fullyRenderable)
  }

  @Test
  fun rejectsDimensionModesWithoutComposeSemantics() {
    val issue =
      RcDocument(header, listOf(RcWidthModifier(RcDimensionType.WEIGHT, RcFloatWord.literal(1f))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("WidthModifier", issue.operation)
    assertEquals("dimension type 3 is not implemented", issue.detail)
  }

  @Test
  fun mapsEveryAndroidxBoxAlignment() {
    val expected =
      mapOf(
        (1 to 4) to Alignment.TopStart,
        (2 to 4) to Alignment.TopCenter,
        (3 to 4) to Alignment.TopEnd,
        (1 to 2) to Alignment.CenterStart,
        (2 to 2) to Alignment.Center,
        (3 to 2) to Alignment.CenterEnd,
        (1 to 5) to Alignment.BottomStart,
        (2 to 5) to Alignment.BottomCenter,
        (3 to 5) to Alignment.BottomEnd,
      )

    expected.forEach { (positions, alignment) ->
      assertEquals(alignment, boxAlignment(positions.first, positions.second))
    }
  }

  @Test
  fun rejectsUnknownBoxAlignmentBeforeRendering() {
    val issue =
      RcDocument(header, listOf(RcBoxLayout(2, 20, 8, 4))).composeSupportReport().issues.first {
        it.operation == "BoxLayout"
      }

    assertEquals("horizontal position 8 is not implemented", issue.detail)
  }

  @Test
  fun matchesAndroidxLinearPositioningIncludingAdditiveSpacing() {
    val sizes = intArrayOf(10, 20)

    assertContentEquals(intArrayOf(0, 15), arrangeLinear(100, sizes, 1, 5, false))
    assertContentEquals(intArrayOf(33, 48), arrangeLinear(100, sizes, 2, 5, false))
    assertContentEquals(intArrayOf(65, 80), arrangeLinear(100, sizes, 3, 5, false))
    assertContentEquals(intArrayOf(0, 85), arrangeLinear(100, sizes, 6, 5, false))
    assertContentEquals(intArrayOf(23, 62), arrangeLinear(100, sizes, 7, 5, false))
    assertContentEquals(intArrayOf(18, 68), arrangeLinear(100, sizes, 8, 5, false))
    assertContentEquals(intArrayOf(90, 65), arrangeLinear(100, sizes, 1, 5, true))
  }

  @Test
  fun validatesRowAndColumnAxesBeforeRendering() {
    val issues =
      RcDocument(
          header,
          listOf(
            RcRowLayout(2, 20, 4, 1, RcFloatWord.literal(0f)),
            RcColumnLayout(3, 30, 4, 1, RcFloatWord.literal(0f)),
          ),
        )
        .composeSupportReport()
        .issues

    assertTrue(issues.any { it.operation == "RowLayout" && "horizontal" in it.detail })
    assertTrue(issues.any { it.operation == "RowLayout" && "vertical" in it.detail })
    assertTrue(issues.any { it.operation == "ColumnLayout" && "horizontal" in it.detail })
    assertTrue(issues.any { it.operation == "ColumnLayout" && "vertical" in it.detail })
  }

  @Test
  fun acceptsBuiltInTypefaceAndRejectsUnmappedAndroidFontIds() {
    val typeface = 16 or (600 shl 16)

    assertTrue(
      RcDocument(header, listOf(RcPaintData(listOf(typeface, 3))))
        .composeSupportReport()
        .fullyRenderable
    )
    val issue =
      RcDocument(header, listOf(RcPaintData(listOf(typeface, 42))))
        .composeSupportReport()
        .issues
        .single()
    assertEquals("font id 42 is not implemented", issue.detail)
  }

  @Test
  fun rejectsUnknownTextAttributeModeBeforeRendering() {
    val issue =
      RcDocument(header, listOf(RcTextAttribute(3, 4, 99))).composeSupportReport().issues.single()

    assertEquals("TextMeasurement", issue.operation)
    assertEquals("type 99 is not implemented", issue.detail)
  }

  @Test
  fun rejectsBitmapSourcesThatNeedAnUnconfiguredHost() {
    val issue =
      RcDocument(header, listOf(RcBitmapData(1, 1, 1, RcBitmapData.TYPE_PNG, 1, byteArrayOf(1))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("encoding 1 requires an image host", issue.detail)
  }

  @Test
  fun rejectsTruncatedRawBitmapBeforeRendering() {
    val issue =
      RcDocument(header, listOf(RcBitmapData(1, 2, 2, RcBitmapData.TYPE_RAW8888, 0, ByteArray(15))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("raw RGBA payload is truncated", issue.detail)
  }

  @Test
  fun rejectsUnknownImageAttributeMode() {
    val issue =
      RcDocument(header, listOf(RcImageAttribute(2, 1, 7, emptyList())))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("type 7 is not implemented", issue.detail)
  }

  @Test
  fun rejectsUnknownColorAttributeMode() {
    val issue =
      RcDocument(header, listOf(RcColorAttribute(2, 1, 9))).composeSupportReport().issues.single()

    assertEquals("type 9 is not implemented", issue.detail)
  }

  @Test
  fun rejectsUnknownColorExpressionMode() {
    val issue =
      RcDocument(header, listOf(RcColorExpression(2, 9, 0, 0, 0)))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("mode 9 is not implemented", issue.detail)
  }

  @Test
  fun rejectsIntegerVariablesThatRequireCallArguments() {
    val issue =
      RcDocument(header, listOf(RcIntegerExpression(2, 1, listOf(RcIntegerExpression.VAR1))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("IntegerExpression", issue.operation)
    assertEquals("variable token 24 has no standalone arguments", issue.detail)
  }

  @Test
  fun rejectsMalformedIntegerExpressionStacks() {
    val issue =
      RcDocument(header, listOf(RcIntegerExpression(2, 1, listOf(RcIntegerExpression.ADD))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("stack underflow at value 0", issue.detail)
  }

  @Test
  fun rejectsMissingAndOverAppliedFloatFunctions() {
    val missing =
      RcDocument(header, listOf(RcFloatFunctionCall(40, emptyList())))
        .composeSupportReport()
        .issues
        .single()
    assertEquals("function 40 is not defined", missing.detail)

    val overApplied =
      RcDocument(
          header,
          listOf(
            RcFloatFunctionDefine(40, listOf(7)),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcFloatFunctionCall(40, listOf(RcFloatWord.literal(1f), RcFloatWord.literal(2f))),
          ),
        )
        .composeSupportReport()
        .issues
        .single()
    assertEquals("2 arguments exceed 1 parameters", overApplied.detail)
  }

  private fun animationSpec() =
    RcAnimationSpec(
      animationId = 1,
      motionDurationMillis = RcFloatWord.literal(300f),
      motionEasingType = 1,
      visibilityDurationMillis = RcFloatWord.literal(300f),
      visibilityEasingType = 1,
      enterAnimation = RcLayoutAnimation.FadeIn,
      exitAnimation = RcLayoutAnimation.FadeOut,
    )
}
