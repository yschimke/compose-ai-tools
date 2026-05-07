package ee.schimke.composeai.renderer.uiautomator

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.provides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Producer-level tests for the `uia/hierarchy` extension (#874). Drives the extractor against
 * real Compose `SemanticsNode` trees through `ComposeContentTestRule` — same path the daemon
 * will use post-capture, so anything that passes here matches what an agent's `uia.click`
 * dispatch sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class UiAutomatorHierarchyExtensionTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `default filter drops layout wrappers and decorative text but keeps actionables`() {
    rule.setContent {
      Column {
        Box { Button(onClick = {}) { Text("Submit") } }
        Text("Just a label")
      }
    }

    val payload = extractMerged()

    val texts = payload.nodes.map { it.text }
    assertTrue(
      "expected the Submit button among emitted nodes, got $texts",
      texts.any { it == "Submit" },
    )
    assertFalse(
      "decorative 'Just a label' Text must not be emitted in the default filter, got $texts",
      texts.contains("Just a label"),
    )
    // Every emitted node must carry at least one supported uia.* action — that's the contract
    // of the default filter and what makes the snapshot useful as a dispatch target list.
    for (node in payload.nodes) {
      assertTrue(
        "default-filtered node carried no actions: $node",
        node.actions.isNotEmpty(),
      )
      assertTrue(
        "node action '${node.actions}' contains an unsupported value",
        node.actions.all { it in UiAutomatorDataProducts.SUPPORTED_ACTIONS },
      )
    }
  }

  @Test
  fun `deeply nested clickable stays reachable through filtered parents`() {
    rule.setContent {
      Box {
        Box {
          Box { Button(onClick = {}) { Text("Deep") } }
        }
      }
    }

    val payload = extractMerged()
    val deep = payload.nodes.firstOrNull { it.text == "Deep" }
    assertNotNull("Deep button must survive three layers of Box wrappers", deep)
    assertTrue(
      "Deep button must expose `click`, got ${deep!!.actions}",
      UiAutomatorDataProducts.ACTION_CLICK in deep.actions,
    )
  }

  @Test
  fun `includeNonActionable surfaces decorative text and contentDescription nodes`() {
    rule.setContent {
      Column {
        Button(onClick = {}) { Text("Primary") }
        Text("Just a label")
        Box(modifier = Modifier.semantics { contentDescription = "decoration" })
      }
    }

    val rootNode = rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
    val filtered = UiAutomatorHierarchyExtractor.extract(rootNode)
    val full = UiAutomatorHierarchyExtractor.extract(rootNode, includeNonActionable = true)

    val filteredTexts = filtered.nodes.map { it.text }
    val fullTexts = full.nodes.mapNotNull { it.text }
    val fullDescriptions = full.nodes.mapNotNull { it.contentDescription }

    assertFalse(
      "filtered view must not include decorative Text, got $filteredTexts",
      filteredTexts.contains("Just a label"),
    )
    assertTrue(
      "debug view must include decorative Text, got $fullTexts",
      fullTexts.contains("Just a label"),
    )
    assertTrue(
      "debug view must include contentDescription nodes, got $fullDescriptions",
      fullDescriptions.contains("decoration"),
    )
    assertTrue(
      "debug view must be at least as large as filtered view (${full.nodes.size} vs " +
        "${filtered.nodes.size})",
      full.nodes.size >= filtered.nodes.size,
    )
  }

  @Test
  fun `testTag ancestors propagate to actionable descendant`() {
    rule.setContent {
      Column(modifier = Modifier.testTag("list")) {
        Box(modifier = Modifier.testTag("row")) {
          Button(onClick = {}, modifier = Modifier.testTag("submit-button")) { Text("Submit") }
        }
      }
    }

    val payload = extractMerged()
    val submit = payload.nodes.firstOrNull { it.testTag == "submit-button" }
    assertNotNull(
      "expected the Submit button (testTag='submit-button') in the emitted nodes",
      submit,
    )
    // Compose's merged tree collapses Button { Text("Submit") } so the click-bearing node owns
    // the testTag we put on the Button. Ancestors `list` and `row` must travel along, root-most
    // first, so a `hasParent({testTag: 'row'})` selector chain stays resolvable from this
    // snapshot alone.
    assertTrue(
      "expected ancestor 'list' before 'row' in ${submit!!.testTagAncestors}",
      submit.testTagAncestors.indexOf("list") in 0..submit.testTagAncestors.indexOf("row"),
    )
    assertTrue(
      "expected 'row' among ancestors, got ${submit.testTagAncestors}",
      "row" in submit.testTagAncestors,
    )
    assertFalse(
      "node must not include its own testTag in testTagAncestors",
      "submit-button" in submit.testTagAncestors,
    )
  }

  @Test
  fun `extension declares hierarchy output and Android target and fails loudly without root`() {
    val extension = UiAutomatorHierarchyExtension()

    assertTrue(extension.inputs.isEmpty())
    assertEquals(setOf(UiAutomatorDataProducts.Hierarchy), extension.outputs)
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)

    // Missing SemanticsRoot context key must fail loudly with the key name in the message —
    // mirrors the `:data-a11y-hierarchy-android` contract so misconfigured hosts surface the
    // problem instead of silently emitting an empty hierarchy.
    val store = RecordingDataProductStore()
    val ex =
      assertThrows(IllegalStateException::class.java) {
        extension.process(
          ExtensionPostCaptureContext(
            extensionId = extension.id,
            previewId = "preview",
            renderMode = null,
            products = store.scopedFor(extension),
          )
        )
      }
    assertTrue(
      "missing-key error must mention the SemanticsRoot key name; was: ${ex.message}",
      ex.message!!.contains(UiAutomatorHierarchyContextKeys.SemanticsRoot.name),
    )
  }

  @Test
  fun `extension writes payload through context and respects options`() {
    rule.setContent {
      Column {
        Button(onClick = {}) { Text("Primary") }
        Text("Just a label")
      }
    }

    val rootNode = rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
    val extension = UiAutomatorHierarchyExtension()
    val store = RecordingDataProductStore()

    extension.process(
      ExtensionPostCaptureContext(
        extensionId = extension.id,
        previewId = "preview",
        renderMode = null,
        products = store.scopedFor(extension),
        data =
          ExtensionContextData.of(
            UiAutomatorHierarchyContextKeys.SemanticsRoot provides rootNode,
            UiAutomatorHierarchyContextKeys.Options provides
              UiAutomatorHierarchyOptions(includeNonActionable = false, merged = false),
          ),
      )
    )

    val payload = store.get(UiAutomatorDataProducts.Hierarchy)
    assertNotNull("extension must emit the Hierarchy product", payload)
    val texts = payload!!.nodes.map { it.text }
    assertTrue("expected Primary, got $texts", texts.any { it == "Primary" })
    assertFalse(
      "default filter (includeNonActionable=false) must not emit 'Just a label'",
      texts.contains("Just a label"),
    )
    // `merged=false` is descriptive — it must show up on every emitted node so a consumer
    // looking at two payloads knows which tree variant produced each.
    for (node in payload.nodes) {
      assertFalse("merged flag must round-trip onto emitted nodes", node.merged)
    }

    // Default options (no Options key provided) — verifies the extension falls back cleanly.
    val store2 = RecordingDataProductStore()
    extension.process(
      ExtensionPostCaptureContext(
        extensionId = extension.id,
        previewId = "preview",
        renderMode = null,
        products = store2.scopedFor(extension),
        data =
          ExtensionContextData.of(
            UiAutomatorHierarchyContextKeys.SemanticsRoot provides rootNode
          ),
      )
    )
    val defaulted = store2.get(UiAutomatorDataProducts.Hierarchy)
    assertNotNull(defaulted)
    assertTrue(
      "default options must produce at least one node",
      defaulted!!.nodes.isNotEmpty(),
    )
    assertTrue(
      "default options imply merged=true on emitted nodes",
      defaulted.nodes.all { it.merged },
    )
    assertNull(
      "default filter must not emit 'Just a label' Text node",
      defaulted.nodes.firstOrNull { it.text == "Just a label" },
    )
  }

  private fun extractMerged(): UiAutomatorHierarchyPayload {
    val root = rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
    return UiAutomatorHierarchyExtractor.extract(root)
  }
}
