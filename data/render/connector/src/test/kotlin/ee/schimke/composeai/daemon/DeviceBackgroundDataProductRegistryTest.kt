package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBackgroundDataProductRegistryTest {
  @Test
  fun `device background extension declares around composable hook`() {
    val extension = DeviceBackgroundExtension("#FFFFFBFE")
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId(DeviceBackgroundDataProductRegistry.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `capability advertises inline fetchable attachable device background`() {
    val registry = DeviceBackgroundDataProductRegistry(PreviewIndex.empty())

    val cap = registry.capabilities.single()
    assertEquals(DeviceBackgroundDataProductRegistry.KIND, cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch prefers preview background color`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "explicit" to
                PreviewInfoDto(
                  id = "explicit",
                  className = "com.example.PreviewKt",
                  methodName = "Explicit",
                  params = PreviewParamsDto(backgroundColor = 0xFF112233),
                )
            ),
        )
      )

    val payload = fetchPayload(registry, "explicit")

    assertEquals("#FF112233", payload["color"]!!.jsonPrimitive.content)
    assertEquals("preview.backgroundColor", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `fetch uses white for showBackground preview`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "show-background" to
                PreviewInfoDto(
                  id = "show-background",
                  className = "com.example.PreviewKt",
                  methodName = "ShowBackground",
                  params = PreviewParamsDto(showBackground = true),
                )
            ),
        )
      )

    val payload = fetchPayload(registry, "show-background")

    assertEquals("#FFFFFFFF", payload["background"]!!.jsonObject["color"]!!.jsonPrimitive.content)
    assertEquals("preview.showBackground", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `fetch falls back to material light background for transparent preview`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "plain" to
                PreviewInfoDto(
                  id = "plain",
                  className = "com.example.PreviewKt",
                  methodName = "Plain",
                )
            ),
        )
      )

    val payload = fetchPayload(registry, "plain")

    assertEquals("#FFFFFBFE", payload["color"]!!.jsonPrimitive.content)
    assertEquals("material3.lightBackgroundFallback", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `render context replaces fallback with captured Material3 background`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "themed" to
                PreviewInfoDto(
                  id = "themed",
                  className = "com.example.PreviewKt",
                  methodName = "Themed",
                )
            ),
        )
      )
    val context =
      PreviewContext.Builder(
          previewId = "themed",
          backend = null,
          renderMode = null,
          outputBaseName = null,
        )
        .putInspectionValue(
          "compose.material3.themePayload",
          FakeThemePayload(
            FakeResolvedTokens(mapOf("background" to "#FFABCDEF", "surface" to "#FF111111"))
          ),
        )
        .build()
    registry.onRender(
      "themed",
      RenderResult(
        id = 1,
        classLoaderHashCode = 1,
        classLoaderName = "loader",
        previewContext = context,
      ),
    )

    val payload = fetchPayload(registry, "themed")

    assertEquals("#FFABCDEF", payload["color"]!!.jsonPrimitive.content)
    assertEquals("material3.background", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `render context preserves explicit preview background color`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "explicit" to
                PreviewInfoDto(
                  id = "explicit",
                  className = "com.example.PreviewKt",
                  methodName = "Explicit",
                  params = PreviewParamsDto(backgroundColor = 0xFF112233),
                )
            ),
        )
      )
    registry.onRender(
      "explicit",
      RenderResult(
        id = 1,
        classLoaderHashCode = 1,
        classLoaderName = "loader",
        previewContext = themeContext("explicit", mapOf("background" to "#FFABCDEF")),
      ),
    )

    val payload = fetchPayload(registry, "explicit")

    assertEquals("#FF112233", payload["color"]!!.jsonPrimitive.content)
    assertEquals("preview.backgroundColor", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `render context preserves showBackground preview background`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "show-background" to
                PreviewInfoDto(
                  id = "show-background",
                  className = "com.example.PreviewKt",
                  methodName = "ShowBackground",
                  params = PreviewParamsDto(showBackground = true),
                )
            ),
        )
      )
    registry.onRender(
      "show-background",
      RenderResult(
        id = 1,
        classLoaderHashCode = 1,
        classLoaderName = "loader",
        previewContext = themeContext("show-background", mapOf("background" to "#FFABCDEF")),
      ),
    )

    val payload = fetchPayload(registry, "show-background")

    assertEquals("#FFFFFFFF", payload["color"]!!.jsonPrimitive.content)
    assertEquals("preview.showBackground", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `render context falls back to material surface when background is transparent`() {
    val registry =
      DeviceBackgroundDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "themed" to
                PreviewInfoDto(
                  id = "themed",
                  className = "com.example.PreviewKt",
                  methodName = "Themed",
                )
            ),
        )
      )
    registry.onRender(
      "themed",
      RenderResult(
        id = 1,
        classLoaderHashCode = 1,
        classLoaderName = "loader",
        previewContext =
          PreviewContext.Builder(
              previewId = "themed",
              backend = null,
              renderMode = null,
              outputBaseName = null,
            )
            .putInspectionValue(
              "compose.material3.themePayload",
              FakeThemePayload(
                FakeResolvedTokens(mapOf("background" to "#00123456", "surface" to "#FF654321"))
              ),
            )
            .build(),
      ),
    )

    val payload = fetchPayload(registry, "themed")

    assertEquals("#FF654321", payload["color"]!!.jsonPrimitive.content)
    assertEquals("material3.surface", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `unknown preview is not available`() {
    val registry = DeviceBackgroundDataProductRegistry(PreviewIndex.empty())

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "missing",
        kind = DeviceBackgroundDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
  }

  private fun fetchPayload(
    registry: DeviceBackgroundDataProductRegistry,
    previewId: String,
  ): kotlinx.serialization.json.JsonObject {
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = DeviceBackgroundDataProductRegistry.KIND,
        params = null,
        inline = true,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    return (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
  }

  private fun themeContext(previewId: String, colors: Map<String, String>): PreviewContext =
    PreviewContext.Builder(
        previewId = previewId,
        backend = null,
        renderMode = null,
        outputBaseName = null,
      )
      .putInspectionValue(
        "compose.material3.themePayload",
        FakeThemePayload(FakeResolvedTokens(colors)),
      )
      .build()

  private data class FakeThemePayload(val resolvedTokens: FakeResolvedTokens)

  private data class FakeResolvedTokens(val colorScheme: Map<String, String>)
}
