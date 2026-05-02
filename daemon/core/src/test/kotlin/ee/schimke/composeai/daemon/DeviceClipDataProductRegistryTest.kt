package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClipDataProductRegistryTest {
  @Test
  fun `capability advertises inline fetchable attachable device clip`() {
    val registry = DeviceClipDataProductRegistry(PreviewIndex.empty())

    val cap = registry.capabilities.single()
    assertEquals(DeviceClipDataProductRegistry.KIND, cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns circle for round wear device`() {
    val registry =
      DeviceClipDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "wear" to
                PreviewInfoDto(
                  id = "wear",
                  className = "com.example.WearKt",
                  methodName = "Wear",
                  params = PreviewParamsDto(device = "id:wearos_large_round"),
                )
            ),
        )
      )

    val outcome =
      registry.fetch(
        previewId = "wear",
        kind = DeviceClipDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    val clip = payload["clip"]!!.jsonObject
    assertEquals("circle", clip["shape"]!!.jsonPrimitive.content)
    assertEquals("113.5", clip["centerXDp"]!!.jsonPrimitive.content)
    assertEquals("113.5", clip["centerYDp"]!!.jsonPrimitive.content)
    assertEquals("113.5", clip["radiusDp"]!!.jsonPrimitive.content)
  }

  @Test
  fun `fetch returns null clip for rectangular device`() {
    val registry =
      DeviceClipDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "phone" to
                PreviewInfoDto(
                  id = "phone",
                  className = "com.example.PhoneKt",
                  methodName = "Phone",
                  params = PreviewParamsDto(device = "id:pixel_5"),
                )
            ),
        )
      )

    val outcome =
      registry.fetch(
        previewId = "phone",
        kind = DeviceClipDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals(JsonNull, payload["clip"])
  }

  @Test
  fun `fetch returns null clip for indexed preview with no params`() {
    val registry =
      DeviceClipDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "plain" to
                PreviewInfoDto(
                  id = "plain",
                  className = "com.example.PlainKt",
                  methodName = "Plain",
                )
            ),
        )
      )

    val outcome =
      registry.fetch(
        previewId = "plain",
        kind = DeviceClipDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals(JsonNull, payload["clip"])
  }

  @Test
  fun `attachments mirror fetch payload when subscribed`() {
    val registry =
      DeviceClipDataProductRegistry(
        PreviewIndex.fromMap(
          path = null,
          byId =
            mapOf(
              "round-spec" to
                PreviewInfoDto(
                  id = "round-spec",
                  className = "com.example.RoundKt",
                  methodName = "Round",
                  params =
                    PreviewParamsDto(device = "spec:width=300dp,height=300dp,dpi=320,isRound=true"),
                )
            ),
        )
      )

    val attachment =
      registry.attachmentsFor("round-spec", setOf(DeviceClipDataProductRegistry.KIND)).single()

    assertEquals(DeviceClipDataProductRegistry.KIND, attachment.kind)
    assertEquals(1, attachment.schemaVersion)
    assertNull(attachment.path)
    assertEquals(
      "circle",
      attachment.payload!!.jsonObject["clip"]!!.jsonObject["shape"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `unknown preview is not available`() {
    val registry = DeviceClipDataProductRegistry(PreviewIndex.empty())

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "missing",
        kind = DeviceClipDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
    assertTrue(
      registry.attachmentsFor("missing", setOf(DeviceClipDataProductRegistry.KIND)).isEmpty()
    )
  }
}
