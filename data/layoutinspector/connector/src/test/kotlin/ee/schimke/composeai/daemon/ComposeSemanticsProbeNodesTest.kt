package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingProbeNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeSemanticsProbeNodesTest {

  private fun node(
    nodeId: String,
    testTag: String? = null,
    text: String? = null,
    label: String? = null,
    role: String? = null,
    clickable: Boolean = false,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = nodeId,
      boundsInRoot = "0,0,0,0",
      testTag = testTag,
      text = text,
      label = label,
      role = role,
      clickable = clickable,
      children = children,
    )

  @Test
  fun flattensOnlyNodesWithAStableFinder() {
    // A clickable test-tagged button wrapping a text label, plus a structural container with no
    // finder. The container is dropped; the button keeps testTag + role + clickable, the label
    // keeps its text.
    val root =
      node(
        "root",
        children =
          listOf(
            node(
              "button",
              testTag = "addItem",
              role = "Button",
              clickable = true,
              children = listOf(node("label", text = "Add", label = "Add")),
            )
          ),
      )

    assertEquals(
      listOf(
        RecordingProbeNode(testTag = "addItem", role = "Button", clickable = true),
        RecordingProbeNode(text = "Add"),
      ),
      root.toProbeNodes(),
    )
  }

  @Test
  fun decodeProbeNodesParsesTheBridgedJsonPayload() {
    // Android captures probe semantics inside the Robolectric sandbox and ships them across the
    // bridge as a JSON string (a typed list would arrive as sandbox-classloader objects and throw
    // ClassCastException host-side). This is the host-side re-parse — defaults (clickable=false)
    // must be restored for fields the compact encoding omits.
    val decoded =
      ComposeSemanticsDataProducer.decodeProbeNodes(
        """[{"testTag":"cart","clickable":true},{"text":"1 item"},{"contentDescription":"Settings"}]"""
      )

    assertEquals(
      listOf(
        RecordingProbeNode(testTag = "cart", clickable = true),
        RecordingProbeNode(text = "1 item"),
        RecordingProbeNode(contentDescription = "Settings"),
      ),
      decoded,
    )
  }

  @Test
  fun recoversContentDescriptionFromLabelWhenItIsNotJustEchoingText() {
    // The projection collapses content-description-or-text into `label`. An icon with only a
    // content description (no text) surfaces it; a node whose label merely echoes its text does
    // not double-report a content description.
    val root =
      node(
        "root",
        children =
          listOf(
            node("icon", label = "Settings", role = "Button", clickable = true),
            node("title", text = "Home", label = "Home"),
          ),
      )

    assertEquals(
      listOf(
        RecordingProbeNode(contentDescription = "Settings", role = "Button", clickable = true),
        RecordingProbeNode(text = "Home"),
      ),
      root.toProbeNodes(),
    )
  }
}
