package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorCurvedText
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.render.PreviewContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Wear `TimeText` clock is captured by reflecting on `CurvedTextChild`, whose resolved style
 * lives on a **private `actualStyle` field** — the merged `DefaultCurvedTextStyles + style()`. The
 * `style` lambda beside it carries only the caller's overrides, so reading that instead would
 * report nothing for a clock that inherits the theme's face.
 *
 * Without the family the figma-svg export emitted a `<textPath>` with no `font-family` at all, so
 * the clock inherited the document default while every straight run named the theme's face — the
 * SVG drew the time in the wrong font (yschimke/compose-preview-server#201).
 *
 * Like `ComposeInternalFieldContractTest`, this is a **canary over a reflective read**: the read is
 * wrapped in `runCatching`, so an androidx rename doesn't throw — the family silently goes back to
 * null and the bug returns with nothing else failing. A generic family is used deliberately: it
 * needs no font file, and it round-trips through `fontFamilyLabel` as a stable CSS-ish name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearCurvedTextFontFamilyTest {

  @Test
  fun `a curved clock reports the family its resolved style names`() {
    val curved = captureClock()

    assertNotNull("the clock was captured as a curved run", curved)
    assertEquals("10:10", curved!!.text)
    assertEquals("monospace", curved.fontFamily)
  }

  /** The single curved run of a `TimeText` whose style pins a distinctive family. */
  private fun captureClock(): LayoutInspectorCurvedText? {
    RuntimeEnvironment.setQualifiers("w384dp-h384dp-round-xhdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var captured: LayoutInspectorCurvedText? = null
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent {
            InspectableCurvedContent(slots) {
              TimeText {
                timeTextCurvedText("10:10", CurvedTextStyle(fontFamily = FontFamily.Monospace))
              }
            }
          }
          rule.waitForIdle()
          val semRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val ctx =
            PreviewContext.Builder("wear-curved-font", null, null, "wear-curved-font")
              .rootForTest(semRoot.root as RootForTest)
              .addSlotTables(slots.toList())
              .parameterInformationCollected()
              .build()
          val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = 2f)!!
          captured = firstCurvedRun(layout.root)
        }
      }
    rule
      .apply(statement, Description.createTestDescription(javaClass, "wear-curved-font"))
      .evaluate()
    return captured
  }

  private fun firstCurvedRun(node: LayoutInspectorNode): LayoutInspectorCurvedText? =
    node.curvedTexts.firstOrNull() ?: node.children.firstNotNullOfOrNull(::firstCurvedRun)
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableCurvedContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
