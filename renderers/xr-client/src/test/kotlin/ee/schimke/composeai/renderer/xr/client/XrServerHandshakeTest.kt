package ee.schimke.composeai.renderer.xr.client

import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compatibility rules the `initialize` handshake enforces.
 *
 * These matter because the compositor is provisioned at a pinned version that deliberately lags
 * this repository, so a client and server built from different commits is the NORMAL case. Before
 * the handshake was verified, a mismatch produced no error at all — the composite simply never
 * appeared, because every provisioning and render failure downstream is a graceful skip.
 *
 * [XrServerHandshake.parse] is pure, so every rule is testable without spawning a process.
 */
class XrServerHandshakeTest {

  private fun result(
    version: Int? = XrRenderService.XR_RENDER_SERVICE_VERSION,
    name: String? = XrRenderService.SERVER_NAME,
    capabilities: JsonObject = buildJsonObject { put("render", true) },
    includeServerInfo: Boolean = true,
  ) = buildJsonObject {
    if (includeServerInfo) {
      put(
        "serverInfo",
        buildJsonObject {
          if (name != null) put("name", name)
          if (version != null) put("version", version)
        },
      )
    }
    put("capabilities", capabilities)
  }

  @Test
  fun `a current server parses`() {
    val h = XrServerHandshake.parse(result())
    assertEquals(XrRenderService.SERVER_NAME, h.serverName)
    assertEquals(XrRenderService.XR_RENDER_SERVICE_VERSION, h.serviceVersion)
    assertTrue(h.has(XrRenderService.Capability.RENDER))
  }

  @Test
  fun `a server newer than this client is refused`() {
    // We cannot know a future version's semantics, so this direction is the hard failure.
    val e =
      assertFailsWith<XrServerException> {
        XrServerHandshake.parse(result(version = XrRenderService.XR_RENDER_SERVICE_VERSION + 1))
      }
    assertTrue(e.message!!, e.message!!.contains("version mismatch"))
    assertTrue(e.message!!, e.message!!.contains("xr-composite"))
  }

  @Test
  fun `a server below the supported floor is refused`() {
    val e =
      assertFailsWith<XrServerException> {
        XrServerHandshake.parse(
          result(version = XrRenderService.MIN_SUPPORTED_XR_RENDER_SERVICE_VERSION - 1)
        )
      }
    assertTrue(e.message!!, e.message!!.contains("version mismatch"))
  }

  @Test
  fun `an older but still supported server is accepted`() {
    // The pin makes the server normally OLDER than the client. Requiring equality would turn every
    // service bump into a flag day, so anything inside the window must pass.
    val oldest = XrRenderService.MIN_SUPPORTED_XR_RENDER_SERVICE_VERSION
    assertEquals(oldest, XrServerHandshake.parse(result(version = oldest)).serviceVersion)
  }

  @Test
  fun `a result without serverInfo version is refused`() {
    val e = assertFailsWith<XrServerException> { XrServerHandshake.parse(result(version = null)) }
    assertTrue(e.message!!, e.message!!.contains("serverInfo.version"))
  }

  @Test
  fun `a result with no serverInfo at all is refused`() {
    assertFailsWith<XrServerException> {
      XrServerHandshake.parse(result(includeServerInfo = false))
    }
  }

  @Test
  fun `a result without capabilities is refused`() {
    val e =
      assertFailsWith<XrServerException> {
        XrServerHandshake.parse(buildJsonObject { put("x", 1) })
      }
    assertTrue(e.message!!, e.message!!.contains("capabilities"))
  }

  @Test
  fun `an unreported scene version is null rather than an error`() {
    // Absence is not evidence of a mismatch; the render-time check stays silent on it.
    assertNull(XrServerHandshake.parse(result()).spatialSceneVersion)
  }

  @Test
  fun `the real server's handshake is one this client accepts`() {
    // Captured verbatim from `xr-composite --serve` built at this commit. The generated mirror
    // already keeps the two vocabularies in step; this pins the actual production PAYLOAD, so a
    // server that stopped reporting its version or dropped a capability fails here rather than in
    // a render nobody is watching.
    val real =
      Json.parseToJsonElement(
          """
          {
            "capabilities": {
              "dataProducts": ["xr/composite"],
              "multiSession": true,
              "render": true,
              "spatialSceneVersion": 1,
              "streamFrame": true,
              "updatePanels": true
            },
            "serverInfo": { "name": "xr-composite", "version": 1 }
          }
          """
        )
        .jsonObject
    val h = XrServerHandshake.parse(real)
    assertEquals(XrRenderService.SERVER_NAME, h.serverName)
    assertEquals(XrRenderService.XR_RENDER_SERVICE_VERSION, h.serviceVersion)
    assertEquals(1, h.spatialSceneVersion)
    assertTrue(h.has(XrRenderService.Capability.RENDER))
    assertTrue(h.has(XrRenderService.Capability.UPDATE_PANELS))
    assertTrue(h.has(XrRenderService.Capability.STREAM_FRAME))
    assertTrue(h.has(XrRenderService.Capability.MULTI_SESSION))
    // `dataProducts` is an ARRAY in the real handshake. Asking about it must answer false, not
    // throw — `jsonPrimitive` raises on a structured value, so this went unnoticed until a review
    // pointed at the one capability whose real value is not a scalar.
    assertFalse(h.has(XrRenderService.Capability.DATA_PRODUCTS))
  }

  @Test
  fun `capability lookup is false for absent and non-boolean values`() {
    val h =
      XrServerHandshake.parse(
        result(
          capabilities =
            buildJsonObject {
              put("render", true)
              put("multiSession", false)
              put("spatialSceneVersion", 1)
            }
        )
      )
    assertTrue(h.has(XrRenderService.Capability.RENDER))
    assertFalse(h.has(XrRenderService.Capability.MULTI_SESSION))
    assertFalse("absent capability must not read as advertised", h.has("updatePanels"))
    // A non-boolean value is not an advertisement of support — scalar or structured.
    assertFalse(h.has(XrRenderService.Capability.SPATIAL_SCENE_VERSION))
    assertFalse(
      XrServerHandshake.parse(
          result(
            capabilities =
              buildJsonObject {
                put("render", true)
                put("dataProducts", buildJsonArray { add("xr/composite") })
              }
          )
        )
        .has(XrRenderService.Capability.DATA_PRODUCTS)
    )
    assertEquals(1, h.spatialSceneVersion)
  }
}
