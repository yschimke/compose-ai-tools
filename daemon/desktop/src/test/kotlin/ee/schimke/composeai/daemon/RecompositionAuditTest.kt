@file:OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Audit-shaped end-to-end test for the `compose/recomposition` data product. Pairs with
 * [RecompositionDataProductRegistryTest] (which pins the producer's wire contract) by exercising
 * the use case an *agent* drives when reviewing a PR: "did this change introduce extra
 * recompositions?"
 *
 * The test runs both halves of the canonical audit narrative from
 * `skills/compose-preview-review/design/AGENT_AUDITS.md` § "Runtime and recomposition audit"
 * against a real [DesktopHost] + [DesktopInteractiveSession]:
 *
 * 1. **Report the problem.** Drive [BadCounterRecompositionFixture], dispatch one click, render.
 *    The post-click delta carries ≥ 3 distinct scopes — the parent (which reads `clicks` in order
 *    to forward it) plus the three children whose `Int` parameter changed. That's the audit signal:
 *    a single click invalidated a whole subtree.
 * 2. **Investigate.** With the bad signal in hand, the audit reviewer reads source and infers the
 *    fix from the recomposition shape: hoist the read into a [androidx.compose.runtime.State]
 *    holder, drop the unused parameters from header / footer. See KDoc on
 *    [BetterCounterRecompositionFixture] for the rationale.
 * 3. **Confirm the fix.** Drive [BetterCounterRecompositionFixture], same click sequence. The
 *    post-click delta carries at most two scopes — the consumer child plus at most one runtime-
 *    internal lambda scope (Compose Foundation invalidates a `Box`-content lambda even when the
 *    parent fixture function itself does not read the snapshot state, so the audit's "narrowed"
 *    signal is "≤ 2 and strictly less than the bad case", not "exactly 1"). A second flush without
 *    further input drains to `nodes: []`, confirming the producer reset counters between flushes
 *    (not "the fix masked the producer").
 *
 * ## Data shape gap — what visualisation needs that the v1 payload does not expose
 *
 * The test uses the existing [ee.schimke.composeai.data.recomposition.RecompositionPayload] which
 * carries `{nodeId: hexhash, count: int}` per recomposed scope. That's enough for "did N scopes
 * recompose?" assertions like this test makes, but a *user-facing* visualisation needs more:
 *
 * - **PNG heat-map overlay.** Needs per-scope screen bounds (x, y, width, height in the rendered
 *   image's pixel space) so a colour-shaded region can be drawn over the captured PNG. The current
 *   `CompositionObserver` fires `onScopeExit` before layout publishes node positions, so a v2
 *   producer would need to join the scope id with the post-layout `LayoutInfo` tree (or with
 *   semantics bounds) before emitting the payload.
 * - **VS Code source overlay.** Needs `sourceFile`, `sourceLine`, `sourceColumn`, and
 *   `functionName`. The Compose compiler already emits `sourceInformation()` markers in the
 *   bytecode; the same path Layout Inspector reads. A v2 producer would parse those markers off the
 *   scope's anchor in the slot table — the same `(file:line:column)` key the producer KDoc already
 *   flags as a v2 followup for stable cross-session ids.
 * - **Investigate-the-fix diagnostic.** Needs a reason field per scope: parameter-change vs
 *   snapshot-state read vs both. [androidx.compose.runtime.tooling.CompositionObserver] surfaces
 *   `onScopeInvalidated(scope, value)` carrying the value that triggered invalidation; the v1
 *   producer ignores it. DejaVu's `$dirty`-bit reflection is the next rung up: it can flag a
 *   parameter as dirty-but-equal, which is exactly the surprising-recomposition signal.
 *
 * Without those fields the audit *test* still works — it asserts on a count of distinct opaque ids
 * — but a human or agent reading the JSON payload sees `[{"nodeId": "1a2b3c4d", "count": 1}, …]`
 * with no way to tell which composable each entry is. The fix path is the v2 schema bump outlined
 * in [RecompositionDataProductRegistry]'s KDoc; this test exists in part to make that gap concrete
 * and testable.
 */
class RecompositionAuditTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun bad_fixture_invalidates_whole_subtree_then_fix_narrows_invalidation_footprint() {
    val nodeCountsBad = clickAndCaptureDeltaNodeIds(FIXTURES.first)
    val nodeCountsBetter = clickAndCaptureDeltaNodeIds(FIXTURES.second)

    assertTrue(
      "Bad fixture: parent + 3 children should each invalidate (got '$nodeCountsBad')",
      nodeCountsBad.size >= 3,
    )

    // The audit's "fix worked" signal: a narrow invalidation footprint, not necessarily exactly
    // one scope. Compose Foundation invalidates a small ring of internal lambda scopes around the
    // reading child even when the parent fixture function body never reads the snapshot state —
    // empirically two scopes for this fixture shape. The hard floor is "strictly fewer than the
    // bad case" (asserted below); the explicit cap of 2 catches future regressions that would
    // smuggle the fix back into a whole-subtree invalidation while still being numerically less
    // than the bad case.
    //
    // We deliberately do NOT assert on per-scope counts (e.g. `counts.all { it == 1 }`). The
    // producer increments on every `onScopeExit`, not once-per-scope-per-input, so a single
    // click can legitimately produce a count > 1 if Compose runtime/foundation scheduling drains
    // invalidations in a different pattern across versions. The audit signal that survives those
    // runtime changes is cardinality + relative reduction — that's what we check.
    assertTrue(
      "Better fixture: invalidation footprint should narrow to ≤ 2 scopes after the fix " +
        "(got '$nodeCountsBetter')",
      nodeCountsBetter.size <= 2,
    )

    assertTrue(
      "Audit signal: better fixture must report strictly fewer recomposed scopes than the " +
        "bad fixture (bad=${nodeCountsBad.size}, better=${nodeCountsBetter.size})",
      nodeCountsBetter.size < nodeCountsBad.size,
    )
  }

  private fun clickAndCaptureDeltaNodeIds(fixture: Fixture): Map<String, Int> {
    val outputDir = tempFolder.newFolder("renders-${fixture.previewId}")
    val engine = RenderEngine(outputDir = outputDir)
    val registry = RecompositionDataProductRegistry()
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { id ->
          if (id == fixture.previewId) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = fixture.functionName,
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = fixture.previewId,
            )
          } else null
        },
        interactiveSessionListener =
          DesktopHost.InteractiveSessionListener { id, scene ->
            registry.onSessionLifecycle(id, scene)
          },
      )
    host.start()
    try {
      val classLoader =
        RecompositionAuditTest::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
      val session = host.acquireInteractiveSession(fixture.previewId, classLoader)
      try {
        registry.onSubscribe(
          fixture.previewId,
          "compose/recomposition",
          buildJsonObject {
            put("frameStreamId", JsonPrimitive(fixture.streamId))
            put("mode", JsonPrimitive("delta"))
          },
        )

        // Bootstrap render + drain — we don't care about initial-composition counts for the
        // audit signal; only what changed after the click.
        session.render(requestId = RenderHost.nextRequestId())
        registry.attachmentsFor(fixture.previewId, setOf("compose/recomposition"))

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = fixture.streamId,
            kind = InteractiveInputKind.CLICK,
            pixelX = 32,
            pixelY = 32,
          )
        )
        session.render(requestId = RenderHost.nextRequestId())
        val postClick =
          registry.attachmentsFor(fixture.previewId, setOf("compose/recomposition")).single()
        val payload = postClick.payload!!.jsonObject
        val nodes = payload["nodes"]!!.jsonArray
        val counts = nodes.associate { node ->
          val obj = node.jsonObject
          obj["nodeId"]!!.jsonPrimitive.content to obj["count"]!!.jsonPrimitive.content.toInt()
        }

        // The "confirm a fix" rung needs the producer to actually reset between flushes — render
        // again with no input and verify the next delta is empty. If this drifts, an apparent
        // "fix" might just be the producer dropping counts on the floor.
        session.render(requestId = RenderHost.nextRequestId())
        val idle =
          registry.attachmentsFor(fixture.previewId, setOf("compose/recomposition")).single()
        val idleNodes = idle.payload!!.jsonObject["nodes"]!!.jsonArray
        assertEquals(
          "Producer must reset delta counters between flushes for ${fixture.previewId}",
          0,
          idleNodes.size,
        )

        return counts
      } finally {
        registry.onUnsubscribe(fixture.previewId, "compose/recomposition")
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private data class Fixture(val previewId: String, val functionName: String, val streamId: String)

  companion object {
    private val FIXTURES =
      Fixture(
        previewId = "bad-counter-recomposition",
        functionName = "BadCounterRecompositionFixture",
        streamId = "audit-bad-1",
      ) to
        Fixture(
          previewId = "better-counter-recomposition",
          functionName = "BetterCounterRecompositionFixture",
          streamId = "audit-better-1",
        )
  }
}
