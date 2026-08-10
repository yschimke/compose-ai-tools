package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InspectionSidecarCanonicalizerTest {
  @Test
  fun `same inspection tree from different renderer processes has identical bytes`() {
    val first =
      InspectionSidecarCanonicalizer.canonicalize(
        semanticsById = mapOf("preview" to semantics(rootId = "651", buttonId = "655")),
        layoutById =
          mapOf(
            "preview" to
              layout(
                rootId = "651",
                wrapperId = "androidx.compose.ui.node.LayoutNode@1234abcd",
                buttonId = "655",
                runtimeHash = "4c3cfed3",
                lambdaAddress = "00007f6b7824d4b8",
              )
          ),
      )
    val second =
      InspectionSidecarCanonicalizer.canonicalize(
        semanticsById = mapOf("preview" to semantics(rootId = "658", buttonId = "662")),
        layoutById =
          mapOf(
            "preview" to
              layout(
                rootId = "658",
                wrapperId = "androidx.compose.ui.node.LayoutNode@8765dcba",
                buttonId = "662",
                runtimeHash = "4fbd2fbd",
                lambdaAddress = "00007f75302494f8",
              )
          ),
      )

    assertContentEquals(
      first.semanticsById.getValue("preview"),
      second.semanticsById.getValue("preview"),
    )
    assertContentEquals(first.layoutById.getValue("preview"), second.layoutById.getValue("preview"))

    val semanticsRoot =
      Json.parseToJsonElement(first.semanticsById.getValue("preview").decodeToString())
        .jsonObject
        .getValue("root")
        .jsonObject
    assertEquals("r", semanticsRoot.getValue("nodeId").jsonPrimitive.content)
    val semanticsButton = semanticsRoot.getValue("children").jsonObjectArray().single()
    assertEquals("r/role:Button", semanticsButton.getValue("nodeId").jsonPrimitive.content)

    val layoutText = first.layoutById.getValue("preview").decodeToString()
    assertTrue("@<identity>" in layoutText)
    assertTrue("${'$'}${'$'}Lambda/<address>@<identity>" in layoutText)
    assertTrue("layout:r/0" in layoutText)
  }

  @Test
  fun `malformed future sidecars pass through unchanged`() {
    val semantics = "not-json".encodeToByteArray()
    val layout = "[]".encodeToByteArray()

    val result =
      InspectionSidecarCanonicalizer.canonicalize(
        semanticsById = mapOf("preview" to semantics),
        layoutById = mapOf("preview" to layout),
      )

    assertContentEquals(semantics, result.semanticsById.getValue("preview"))
    assertContentEquals(layout, result.layoutById.getValue("preview"))
  }

  private fun semantics(rootId: String, buttonId: String): ByteArray =
    """
    {"root":{"nodeId":"$rootId","ref":"r","boundsInRoot":"0,0,100,100","children":[
      {"nodeId":"$buttonId","ref":"r/role:Button","boundsInRoot":"10,10,90,60","role":"Button"}
    ]}}
    """
      .trimIndent()
      .encodeToByteArray()

  private fun layout(
    rootId: String,
    wrapperId: String,
    buttonId: String,
    runtimeHash: String,
    lambdaAddress: String,
  ): ByteArray =
    """
    {"root":{"nodeId":"$rootId","component":"Root","bounds":{"left":0,"top":0,"right":100,"bottom":100},"modifiers":[
      {"name":"insets","properties":{"windowInsets":"androidx.compose.ui.platform.EmptyPlatformWindowInsets@$runtimeHash"}}
    ],"children":[
      {"nodeId":"$wrapperId","component":"Box","bounds":{"left":0,"top":0,"right":100,"bottom":100},"children":[
        {"nodeId":"$buttonId","component":"Button","bounds":{"left":10,"top":10,"right":90,"bottom":60},"modifiers":[
          {"name":"clickable","properties":{"onClick":"example.ButtonKt${'$'}${'$'}Lambda/0x$lambdaAddress@1234abcd"}}
        ]}
      ]}
    ]}}
    """
      .trimIndent()
      .encodeToByteArray()

  private fun kotlinx.serialization.json.JsonElement.jsonObjectArray() =
    (this as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
}
