package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.AccessibilityTouchTargetsPayload
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D2 — pins the producer/registry contract for the daemon's a11y data products. Producer
 * writes to `<rootDir>/<previewId>/a11y-{atf,hierarchy}.json`; registry reads back what's
 * there and surfaces it as inline (`a11y/atf`) or path (`a11y/hierarchy`). Unknown kinds
 * route to `Outcome.Unknown`; missing files route to `Outcome.NotAvailable`.
 */
class AccessibilityDataProductRegistryTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("a11y-data-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capabilities advertise a11y atf hierarchy touch targets and overlay with the documented transports`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val byKind = registry.capabilities.associateBy { it.kind }
    assertEquals(setOf("a11y/atf", "a11y/hierarchy", "a11y/touchTargets", "a11y/overlay"), byKind.keys)
    assertEquals(DataProductTransport.INLINE, byKind.getValue("a11y/atf").transport)
    assertEquals(DataProductTransport.PATH, byKind.getValue("a11y/hierarchy").transport)
    assertEquals(DataProductTransport.INLINE, byKind.getValue("a11y/touchTargets").transport)
    assertEquals(DataProductTransport.PATH, byKind.getValue("a11y/overlay").transport)
    for (cap in registry.capabilities) {
      assertTrue(cap.attachable)
      assertTrue(cap.fetchable)
      // D2.2 — a11y mode is paid per-subscription. When no artefact exists yet, fetch returns
      // RequiresRerender and the dispatcher queues a `mode=a11y` re-render.
      assertTrue("${cap.kind}: requiresRerender should be true", cap.requiresRerender)
    }
  }

  @Test
  fun `attachmentsFor returns inline a11y atf payload and path-only a11y hierarchy entry`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val finding =
      AccessibilityFinding(
        level = "WARNING",
        type = "TouchTargetSizeCheck",
        message = "below 48dp",
        viewDescription = "Button",
        boundsInScreen = "0,0,32,32",
      )
    val node =
      AccessibilityNode(
        label = "Submit",
        role = "Button",
        states = listOf("clickable"),
        merged = true,
        boundsInScreen = "10,20,200,80",
      )
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = "com.example.HomeKt#HomePreview",
      findings = listOf(finding),
      nodes = listOf(node),
    )

    val attachments =
      registry.attachmentsFor(
        previewId = "com.example.HomeKt#HomePreview",
        kinds = setOf("a11y/atf", "a11y/hierarchy"),
      )
    assertEquals(2, attachments.size)
    val byKind = attachments.associateBy { it.kind }

    val atf = byKind.getValue("a11y/atf")
    assertNotNull("a11y/atf should travel inline", atf.payload)
    assertNull("a11y/atf must not also carry a path", atf.path)
    val findings = (atf.payload as JsonObject)["findings"]?.jsonArray
    assertNotNull(findings)
    assertEquals(1, findings!!.size)
    assertEquals(
      "TouchTargetSizeCheck",
      findings[0].jsonObject["type"]!!.toString().trim('"'),
    )

    val hierarchy = byKind.getValue("a11y/hierarchy")
    assertNull("a11y/hierarchy travels by path, not inline", hierarchy.payload)
    assertNotNull("a11y/hierarchy must point at the JSON file", hierarchy.path)
    assertTrue(File(hierarchy.path!!).exists())
  }

  @Test
  fun `touch targets derive clickable sizes and non-containment overlaps from hierarchy`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.TouchTargets"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes =
        listOf(
          AccessibilityNode(
            label = "Small",
            role = "Button",
            states = listOf("clickable"),
            boundsInScreen = "0,0,80,80",
          ),
          AccessibilityNode(
            label = "Neighbor",
            role = "Button",
            states = listOf("clickable"),
            boundsInScreen = "60,60,180,180",
          ),
          AccessibilityNode(
            label = "Containing card",
            role = "Button",
            states = listOf("clickable"),
            boundsInScreen = "0,0,240,240",
          ),
          AccessibilityNode(label = "Static", boundsInScreen = "250,0,300,50"),
        ),
      density = 2f,
    )

    val touchFile = File(rootDir, "$previewId/${AccessibilityDataProducer.FILE_TOUCH_TARGETS}")
    val payload =
      Json.decodeFromString(
        AccessibilityTouchTargetsPayload.serializer(),
        touchFile.readText(),
      )
    assertEquals(3, payload.targets.size)

    val small = payload.targets[0]
    assertEquals("node-0", small.nodeId)
    assertEquals(40f, small.widthDp)
    assertEquals(40f, small.heightDp)
    assertEquals(listOf("belowMinimum", "overlapping"), small.findings)
    assertEquals(listOf("node-1"), small.overlappingNodeIds)

    val neighbor = payload.targets[1]
    assertEquals(60f, neighbor.widthDp)
    assertEquals(60f, neighbor.heightDp)
    assertEquals(listOf("overlapping"), neighbor.findings)
    assertEquals(listOf("node-0"), neighbor.overlappingNodeIds)

    val parent = payload.targets[2]
    assertEquals(emptyList<String>(), parent.findings)
    assertNull("parent-child containment should not be reported as overlap", parent.overlappingNodeIds)

    val attachment =
      registry
        .attachmentsFor(previewId = previewId, kinds = setOf("a11y/touchTargets"))
        .single()
    assertNotNull("a11y/touchTargets should travel inline", attachment.payload)
    assertNull("a11y/touchTargets must not also carry a path", attachment.path)

    val outcome =
      registry.fetch(previewId = previewId, kind = "a11y/touchTargets", params = null, inline = false)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertNotNull(result.payload)
    assertNull(result.path)
  }

  @Test
  fun `attachmentsFor skips kinds with no on-disk file rather than emitting an empty entry`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    // No producer has run for this preview yet — neither file exists.
    val attachments =
      registry.attachmentsFor(
        previewId = "com.example.NoRender",
        kinds = setOf("a11y/atf", "a11y/hierarchy", "a11y/touchTargets"),
      )
    assertEquals(emptyList<Any>(), attachments)
  }

  @Test
  fun `fetch on a missing artefact returns RequiresRerender so the dispatcher can re-run in a11y mode`() {
    // D2.2 — a11y artefacts only land when the render ran in a11y mode. A previously-untouched
    // preview (or one rendered in the default fast path) has no `a11y-*.json` on disk; the
    // dispatcher (`JsonRpcServer.handleDataFetchWithRerender`) reacts to RequiresRerender by
    // queueing a `mode=a11y` re-render and re-invoking fetch.
    val registry = AccessibilityDataProductRegistry(rootDir)
    for (kind in listOf("a11y/atf", "a11y/hierarchy", "a11y/touchTargets", "a11y/overlay")) {
      val outcome =
        registry.fetch(
          previewId = "com.example.NoRender",
          kind = kind,
          params = null,
          inline = false,
        )
      assertEquals(
        "$kind: missing artefact should trigger a re-render in a11y mode",
        DataProductRegistry.Outcome.RequiresRerender("a11y"),
        outcome,
      )
    }
  }

  @Test
  fun `fetch on a fresh artefact returns Ok after a previous RequiresRerender produced it`() {
    // Mirrors the dispatcher's two-step round-trip: first fetch returns RequiresRerender, the
    // re-render writes the artefact, the dispatcher re-invokes fetch and now sees Ok.
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.HomeKt#HomePreview"

    // Step 1 — pre-render. Artefact missing, dispatcher would re-render.
    val pre =
      registry.fetch(previewId = previewId, kind = "a11y/atf", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.RequiresRerender("a11y"), pre)

    // Step 2 — re-render in a11y mode lands the artefacts.
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )

    // Step 3 — dispatcher re-invokes fetch; now Ok.
    val post =
      registry.fetch(previewId = previewId, kind = "a11y/atf", params = null, inline = true)
    assertTrue("post-rerender fetch should be Ok, was $post", post is DataProductRegistry.Outcome.Ok)
    val result = (post as DataProductRegistry.Outcome.Ok).result
    assertNotNull(result.payload)
  }

  @Test
  fun `fetch returns Unknown for a kind the registry does not advertise`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = "com.example.HomeKt#HomePreview",
        kind = "compose/recomposition",
        params = null,
        inline = false,
      )
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun `fetch on a11y hierarchy returns the absolute path when caller did not request inline`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = "com.example.X",
      findings = emptyList(),
      nodes = emptyList(),
    )
    val outcome =
      registry.fetch(
        previewId = "com.example.X",
        kind = "a11y/hierarchy",
        params = null,
        inline = false,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertNotNull(result.path)
    assertNull(result.payload)
    assertTrue(File(result.path!!).exists())
  }

  @Test
  fun `attachmentsFor surfaces overlay PNG as an extra under a11y JSON kinds when present`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.HomeKt#HomePreview"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )
    // Simulate the AccessibilityImageProcessor having written the overlay PNG into the
    // per-preview data dir alongside the JSON artefacts. The registry doesn't care how the
    // bytes got there; it just surfaces the file as an extra.
    val previewDir = File(rootDir, previewId).also { it.mkdirs() }
    val overlay = File(previewDir, AccessibilityDataProducer.FILE_OVERLAY)
    overlay.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    val attachments =
      registry.attachmentsFor(
        previewId = previewId,
        kinds = setOf("a11y/atf", "a11y/hierarchy", "a11y/touchTargets", "a11y/overlay"),
      )
    val byKind = attachments.associateBy { it.kind }
    val atfExtras = byKind.getValue("a11y/atf").extras
    assertNotNull("extras must populate when overlay PNG is on disk", atfExtras)
    assertEquals(1, atfExtras!!.size)
    assertEquals("overlay", atfExtras[0].name)
    assertEquals("image/png", atfExtras[0].mediaType)
    assertEquals(overlay.absolutePath, atfExtras[0].path)
    val touchExtras = byKind.getValue("a11y/touchTargets").extras
    assertNotNull("touch target extras must populate when overlay PNG is on disk", touchExtras)
    assertEquals(overlay.absolutePath, touchExtras!![0].path)

    val overlayKind = byKind.getValue("a11y/overlay")
    assertNotNull(overlayKind.path)
    assertEquals(overlay.absolutePath, overlayKind.path)
    assertNull(overlayKind.payload)
  }

  @Test
  fun `fetch on a11y overlay returns the path and the same extra as the JSON kinds`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.X"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )
    val previewDir = File(rootDir, previewId).also { it.mkdirs() }
    val overlay = File(previewDir, AccessibilityDataProducer.FILE_OVERLAY)
    overlay.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    val outcome =
      registry.fetch(previewId = previewId, kind = "a11y/overlay", params = null, inline = false)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(overlay.absolutePath, result.path)
    assertNull("a11y/overlay must not be parsed as JSON", result.payload)
    val extras = result.extras
    assertNotNull(extras)
    assertEquals(1, extras!!.size)
    assertEquals(overlay.absolutePath, extras[0].path)
  }

  @Test
  fun `isPreviewSubscribed flips after onSubscribe and clears after onUnsubscribe`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.HomeKt#HomePreview"
    assertFalse(registry.isPreviewSubscribed(previewId))

    registry.onSubscribe(previewId, "a11y/atf", params = null)
    assertTrue(registry.isPreviewSubscribed(previewId))

    registry.onUnsubscribe(previewId, "a11y/atf")
    assertFalse(registry.isPreviewSubscribed(previewId))
  }

  @Test
  fun `isPreviewSubscribed stays true while any a11y kind remains subscribed`() {
    // Focus inspector subscribes to a11y/atf + a11y/hierarchy as a pair (A11Y_OVERLAY_KINDS).
    // Unsubscribing one shouldn't drop us out of a11y render mode while the other is still
    // live — otherwise the next render strips ATF/hierarchy capture mid-overlay.
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.HomeKt#HomePreview"
    registry.onSubscribe(previewId, "a11y/atf", params = null)
    registry.onSubscribe(previewId, "a11y/hierarchy", params = null)

    registry.onUnsubscribe(previewId, "a11y/atf")
    assertTrue(
      "still subscribed via a11y/hierarchy",
      registry.isPreviewSubscribed(previewId),
    )

    registry.onUnsubscribe(previewId, "a11y/hierarchy")
    assertFalse(registry.isPreviewSubscribed(previewId))
  }

  @Test
  fun `onSubscribe is idempotent so a re-subscribe does not double-count the pair`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.HomeKt#HomePreview"
    registry.onSubscribe(previewId, "a11y/atf", params = null)
    registry.onSubscribe(previewId, "a11y/atf", params = null)
    // One unsubscribe should clear it — if onSubscribe had double-counted, isPreviewSubscribed
    // would still be true here.
    registry.onUnsubscribe(previewId, "a11y/atf")
    assertFalse(registry.isPreviewSubscribed(previewId))
  }

  @Test
  fun `subscriptions are scoped per previewId`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    registry.onSubscribe("com.example.A", "a11y/atf", params = null)
    assertTrue(registry.isPreviewSubscribed("com.example.A"))
    assertFalse(registry.isPreviewSubscribed("com.example.B"))
  }

  @Test
  fun `non-accessibility kinds passed to onSubscribe are ignored`() {
    // The dispatcher only routes here after capability matching, but the registry stays
    // defensive — a misrouted call must not poison the predicate.
    val registry = AccessibilityDataProductRegistry(rootDir)
    registry.onSubscribe("com.example.A", "compose/recomposition", params = null)
    assertFalse(registry.isPreviewSubscribed("com.example.A"))
  }

  @Test
  fun `attachmentsFor omits extras when the overlay PNG never landed`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.NoOverlay"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )
    val attachments =
      registry.attachmentsFor(previewId = previewId, kinds = setOf("a11y/atf", "a11y/hierarchy"))
    for (att in attachments) {
      assertNull("extras must be absent when overlay PNG is missing", att.extras)
    }
  }
}
