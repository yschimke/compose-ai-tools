package ee.schimke.composeai.discovery

import io.github.classgraph.ClassGraph
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PreviewDiscovery.widgetOf]: the `widget` a manifest entry carries, which is what a
 * design-guidelines check asks widget rules of. remote-m3-catalog#72's Wear widget stickers pin
 * `widthDp`/`heightDp` and name no device — exactly what a component sticker does — so without it
 * every one was judged as a component, and a catalog whose rules are all widget rules asked them
 * nothing.
 */
class PreviewWidgetTest {
  private fun preview(
    kind: PreviewKind = PreviewKind.COMPOSE,
    provider: String? = null,
    captures: List<Capture> = listOf(Capture()),
  ) =
    PreviewInfo(
      id = "p",
      functionName = "p",
      className = "com.example.WidgetsKt",
      params =
        PreviewParams(
          widthDp = 216,
          heightDp = 124,
          kind = kind,
          previewParameterProviderClassName = provider,
        ),
      captures = captures,
    )

  /** The calls [fixture]'s body renders through, read from its bytecode as discovery reads them. */
  private fun callsOf(fixture: String): List<PreviewTargetInference.Invocation> =
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .acceptPackages("ee.schimke.composeai.discovery", "ee.schimke.composeai.wear.preview")
      .scan()
      .use { scan ->
        val classInfo = scan.getClassInfo("ee.schimke.composeai.discovery.WidgetFixturesKt")!!
        val method = classInfo.methodInfo.first { it.name == fixture }
        PreviewTargetInference.renderedCalls(
          classInfo,
          method,
          scan,
          projectClassFqns = setOf("ee.schimke.composeai.discovery.WidgetFixturesKt"),
        )
      }

  @Test
  fun `a preview drawing through the Wear widget entry point is a Wear widget`() {
    val widget = PreviewDiscovery.widgetOf(preview(), lazyOf(callsOf("WearWidgetStickerFixture")))
    assertEquals(PreviewWidget(PreviewWidget.HOST_WEAR, PreviewWidget.PROFILE_WEAR_WIDGETS), widget)
  }

  @Test
  fun `a project wrapper around the entry point still makes it a Wear widget`() {
    val calls = callsOf("WrappedWearWidgetStickerFixture")
    assertTrue(PreviewTargetInference.drawsWearWidget(calls))
  }

  @Test
  fun `a component sticker of the same size is not a widget`() {
    val calls = callsOf("ComponentStickerFixture")
    assertFalse(PreviewTargetInference.drawsWearWidget(calls))
    assertNull(PreviewDiscovery.widgetOf(preview(), lazyOf(calls)))
  }

  @Test
  fun `a glance-wear parameter provider makes a Wear widget without a walk`() {
    val widget =
      PreviewDiscovery.widgetOf(
        preview(provider = "androidx.glance.wear.tooling.preview.SquircleAllWidgetPreviewParams"),
        lazy { error("the bytecode walk is not needed") },
      )
    assertEquals(PreviewWidget.HOST_WEAR, widget?.host)
  }

  @Test
  fun `a Glance app widget or a launcher capture is a launcher widget with no profile`() {
    val notNeeded = lazy<List<PreviewTargetInference.Invocation>> { error("not needed") }
    assertEquals(
      PreviewWidget(PreviewWidget.HOST_LAUNCHER),
      PreviewDiscovery.widgetOf(preview(kind = PreviewKind.GLANCE_APPWIDGET), notNeeded),
    )
    assertEquals(
      PreviewWidget(PreviewWidget.HOST_LAUNCHER),
      PreviewDiscovery.widgetOf(
        preview(captures = listOf(Capture(launcherWidget = LauncherWidgetCapture(2, 1)))),
        notNeeded,
      ),
    )
  }

  @Test
  fun `a preview that is not composed is never walked`() {
    assertNull(
      PreviewDiscovery.widgetOf(
        preview(kind = PreviewKind.TILE),
        lazy { error("a tile preview has no composition to walk") },
      )
    )
  }

  @Test
  fun `the manifest carries the widget, and omits it for everything else`() {
    val json = Json { encodeDefaults = false }
    val widget =
      preview()
        .copy(widget = PreviewWidget(PreviewWidget.HOST_WEAR, PreviewWidget.PROFILE_WEAR_WIDGETS))
    assertTrue(
      json
        .encodeToString(PreviewInfo.serializer(), widget)
        .contains(""""widget":{"host":"wear","profile":"wear-widgets"}""")
    )
    assertFalse(json.encodeToString(PreviewInfo.serializer(), preview()).contains("\"widget\""))
  }
}
