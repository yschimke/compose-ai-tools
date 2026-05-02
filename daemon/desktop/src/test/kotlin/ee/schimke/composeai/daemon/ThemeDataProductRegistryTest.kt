package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeDataProductRegistryTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun capabilities_advertise_compose_theme_as_medium_cost_inline_product() {
    val registry = ThemeDataProductRegistry()
    val cap = registry.capabilities.single()
    assertEquals("compose/theme", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(cap.requiresRerender)
  }

  @Test
  fun fetch_without_captured_payload_requires_theme_rerender() {
    val registry = ThemeDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/theme", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.RequiresRerender)
    assertEquals("theme", (outcome as DataProductRegistry.Outcome.RequiresRerender).mode)
  }

  @Test
  fun theme_mode_render_captures_material_tokens_for_fetch_and_attachments() {
    val outputDir = tempFolder.newFolder("renders")
    val registry = ThemeDataProductRegistry()
    val engine =
      RenderEngine(
        outputDir = outputDir,
        themeCapture =
          object : RenderEngine.ThemeCapture {
            override fun shouldCapture(previewId: String?, renderMode: String?): Boolean =
              registry.shouldCapture(previewId, renderMode)

            override fun capture(previewId: String?, payload: ThemePayload) {
              registry.capture(previewId, payload)
            }
          },
      )

    engine.render(
      spec =
        RenderSpec(
          previewId = "preview-1",
          renderMode = "theme",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "RedSquare",
          widthPx = 32,
          heightPx = 32,
          density = 1.0f,
          outputBaseName = "theme-red-square",
        ),
      requestId = RenderHost.nextRequestId(),
    )

    val fetch =
      registry.fetch("preview-1", "compose/theme", params = null, inline = true)
        as DataProductRegistry.Outcome.Ok
    assertEquals("compose/theme", fetch.result.kind)
    val payload = fetch.result.payload!!.jsonObject
    val tokens = payload["resolvedTokens"]!!.jsonObject
    val colorScheme = tokens["colorScheme"]!!.jsonObject
    assertTrue(colorScheme["primary"]!!.jsonPrimitive.content.matches(Regex("#[0-9A-F]{8}")))
    assertNotNull(tokens["typography"]!!.jsonObject["titleMedium"])
    assertNotNull(tokens["shapes"]!!.jsonObject["medium"])
    assertEquals(0, payload["consumers"]!!.jsonArray.size)

    val attachments = registry.attachmentsFor("preview-1", setOf("compose/theme"))
    assertEquals(1, attachments.size)
    assertEquals("compose/theme", attachments.single().kind)
    assertEquals(
      colorScheme["primary"],
      attachments
        .single()
        .payload!!
        .jsonObject["resolvedTokens"]!!
        .jsonObject["colorScheme"]!!
        .jsonObject["primary"],
    )
  }

  @Test
  fun subscribed_preview_captures_on_default_render() {
    val outputDir = tempFolder.newFolder("renders")
    val registry = ThemeDataProductRegistry()
    registry.onSubscribe("preview-2", "compose/theme", JsonPrimitive("ignored"))
    val engine =
      RenderEngine(
        outputDir = outputDir,
        themeCapture =
          object : RenderEngine.ThemeCapture {
            override fun shouldCapture(previewId: String?, renderMode: String?): Boolean =
              registry.shouldCapture(previewId, renderMode)

            override fun capture(previewId: String?, payload: ThemePayload) {
              registry.capture(previewId, payload)
            }
          },
      )

    engine.render(
      spec =
        RenderSpec(
          previewId = "preview-2",
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "RedSquare",
          widthPx = 32,
          heightPx = 32,
          density = 1.0f,
          outputBaseName = "subscribed-theme-red-square",
        ),
      requestId = RenderHost.nextRequestId(),
    )

    val outcome = registry.fetch("preview-2", "compose/theme", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
  }

  @Test
  fun desktop_host_resolves_preview_id_payload_and_theme_mode() {
    val outputDir = tempFolder.newFolder("renders")
    val registry = ThemeDataProductRegistry()
    val engine =
      RenderEngine(
        outputDir = outputDir,
        themeCapture =
          object : RenderEngine.ThemeCapture {
            override fun shouldCapture(previewId: String?, renderMode: String?): Boolean =
              registry.shouldCapture(previewId, renderMode)

            override fun capture(previewId: String?, payload: ThemePayload) {
              registry.capture(previewId, payload)
            }
          },
      )
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == "preview-3") {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "host-theme-red-square",
            )
          } else null
        },
      )
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            id = RenderHost.nextRequestId(),
            payload = "previewId=preview-3;mode=theme",
          )
        )
      registry.onRender("preview-3", result)
      val outcome = registry.fetch("preview-3", "compose/theme", params = null, inline = true)
      assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun non_theme_render_clears_stale_payload_for_preview() {
    val registry = ThemeDataProductRegistry()
    registry.capture(
      "preview-3",
      ThemePayload(
        resolvedTokens =
          ResolvedThemeTokens(
            colorScheme = emptyMap(),
            typography = emptyMap(),
            shapes = emptyMap(),
          )
      ),
    )

    registry.onRender(
      "preview-3",
      RenderResult(id = 1, classLoaderHashCode = 1, classLoaderName = "test"),
    )
    val freshFetch = registry.fetch("preview-3", "compose/theme", params = null, inline = true)
    assertTrue(freshFetch is DataProductRegistry.Outcome.Ok)

    registry.onRender(
      "preview-3",
      RenderResult(id = 2, classLoaderHashCode = 1, classLoaderName = "test"),
    )
    val staleFetch = registry.fetch("preview-3", "compose/theme", params = null, inline = true)
    assertTrue(staleFetch is DataProductRegistry.Outcome.RequiresRerender)
  }
}
