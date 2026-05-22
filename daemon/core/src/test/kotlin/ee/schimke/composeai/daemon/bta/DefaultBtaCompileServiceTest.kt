@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DefaultBtaCompileService]. Drives the three outcome paths the
 * `JsonRpcServer.compileSources` handler relies on:
 *
 * - **Eligibility gate** (`ineligibilityReason != null`) → every call returns
 *   [BtaCompileService.Outcome.Fallback] with the reason verbatim, the backend is never invoked.
 *   This is the daemon-warm-time predicate that keeps KSP/KAPT-tainted modules off stage 2.
 * - **Ok** → the backend ran without throwing; service returns Ok and the daemon's caller swaps the
 *   user classloader.
 * - **Backend throw → Fallback** → unrecoverable runtime error (BTA bootstrap fault, missing JAR,
 *   etc.). The exception message is folded into the Fallback reason for diagnostic surfacing in the
 *   panel log.
 *
 * The fourth outcome ([BtaCompileService.Outcome.CompileError]) isn't reachable from
 * [DefaultBtaCompileService] yet — that lands when the `KotlinLogger`-backed diagnostic collector
 * is wired through [BtaCompileSession]. Until then, diagnostic-bearing compile failures fall
 * through to stage 1's `KotlinCompileErrorDetector` via the editor's Fallback retry path. Tracked
 * in COMPILE-IN-PROCESS.md follow-ups.
 */
class DefaultBtaCompileServiceTest {

  @Test
  fun `ineligibility reason is returned verbatim and the backend never runs`() {
    var calls = 0
    val service =
      DefaultBtaCompileService(
        backend = DefaultBtaCompileService.CompileBackend { _, _ -> calls++ },
        ineligibilityReason = "KSP plugin applied to this module",
      )
    val outcome = service.compile(sources = listOf(Path.of("/tmp/Hi.kt")), changes = null)
    assertTrue("expected Fallback, got $outcome", outcome is BtaCompileService.Outcome.Fallback)
    assertEquals(
      "KSP plugin applied to this module",
      (outcome as BtaCompileService.Outcome.Fallback).reason,
    )
    assertEquals("backend must not run when ineligible", 0, calls)
  }

  @Test
  fun `eligible session returns Ok when the backend runs without throwing`() {
    var lastSources: List<Path> = emptyList()
    var lastChanges: SourcesChanges? = null
    val service =
      DefaultBtaCompileService(
        backend =
          DefaultBtaCompileService.CompileBackend { sources, changes ->
            lastSources = sources
            lastChanges = changes
          }
      )
    val outcome =
      service.compile(
        sources = listOf(Path.of("/tmp/Hi.kt"), Path.of("/tmp/There.kt")),
        changes = null,
      )
    assertSame(BtaCompileService.Outcome.Ok, outcome)
    assertEquals(listOf("/tmp/Hi.kt", "/tmp/There.kt"), lastSources.map { it.toString() })
    assertTrue(
      "null changes should translate to SourcesChanges.ToBeCalculated, got $lastChanges",
      lastChanges === SourcesChanges.ToBeCalculated,
    )
  }

  @Test
  fun `known dirty set translates to SourcesChanges_Known with the editor's modified + removed files`() {
    var lastChanges: SourcesChanges? = null
    val service =
      DefaultBtaCompileService(
        backend = DefaultBtaCompileService.CompileBackend { _, changes -> lastChanges = changes }
      )
    val outcome =
      service.compile(
        sources = listOf(Path.of("/tmp/Hi.kt")),
        changes =
          SourceChangeSet(
            modified = listOf("/tmp/Hi.kt", "/tmp/There.kt"),
            removed = listOf("/tmp/Gone.kt"),
          ),
      )
    assertSame(BtaCompileService.Outcome.Ok, outcome)
    val known = lastChanges as SourcesChanges.Known
    assertEquals(listOf("/tmp/Hi.kt", "/tmp/There.kt"), known.modifiedFiles.map { it.path })
    assertEquals(listOf("/tmp/Gone.kt"), known.removedFiles.map { it.path })
  }

  @Test
  fun `backend throw is downgraded to Fallback carrying the exception message`() {
    val service =
      DefaultBtaCompileService(
        backend =
          DefaultBtaCompileService.CompileBackend { _, _ ->
            error("kotlin-build-tools-impl missing from classpath")
          }
      )
    val outcome = service.compile(sources = listOf(Path.of("/tmp/Hi.kt")), changes = null)
    assertTrue("expected Fallback, got $outcome", outcome is BtaCompileService.Outcome.Fallback)
    val reason = (outcome as BtaCompileService.Outcome.Fallback).reason
    assertTrue(
      "expected reason to mention the BTA throw + the exception message; got: $reason",
      reason.startsWith("BTA compile threw:") && reason.contains("kotlin-build-tools-impl missing"),
    )
  }

  @Test
  fun `backend throw with no message falls back to the exception class name`() {
    val service =
      DefaultBtaCompileService(
        backend = DefaultBtaCompileService.CompileBackend { _, _ -> throw IllegalStateException() }
      )
    val outcome = service.compile(sources = listOf(Path.of("/tmp/Hi.kt")), changes = null)
    val reason = (outcome as BtaCompileService.Outcome.Fallback).reason
    assertTrue(
      "reason should fall back to the exception class name when message is null; got: $reason",
      reason.endsWith("IllegalStateException"),
    )
  }
}
