@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
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
 * - **CompileError** → [forSession]'s backend routes BTA's diagnostics through a
 *   [DiagnosticCollector] and re-throws a [BtaCompileDiagnosticException] on `COMPILATION_ERROR`;
 *   the service maps that to [BtaCompileService.Outcome.CompileError] so the editor's diagnostic
 *   banner renders the Kotlin source errors from stage 2 directly.
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

  @Test
  fun `fromSysprops returns null when stage-2 sysprops are absent`() {
    val service = DefaultBtaCompileService.fromSysprops { null }
    assertEquals(null, service)
  }

  @Test
  fun `fromSysprops returns null when implClasspath sysprop is empty`() {
    // Partial wiring — moduleName + outputDir + icWorkingDir set but no impl classpath.
    // The factory must short-circuit; the JSON-RPC handler falls back to stage 1.
    val sysprops =
      mapOf(
        "composeai.daemon.bta.implClasspath" to "",
        "composeai.daemon.bta.moduleName" to "samples-cmp",
        "composeai.daemon.bta.outputDir" to "/abs/out",
        "composeai.daemon.bta.icWorkingDir" to "/abs/ic",
      )
    val service = DefaultBtaCompileService.fromSysprops { sysprops[it] }
    assertEquals(null, service)
  }

  @Test
  fun `fromSysprops builds a service when every required sysprop is populated`() {
    val sysprops =
      mapOf(
        "composeai.daemon.bta.implClasspath" to
          "/abs/kotlin-build-tools-impl.jar${java.io.File.pathSeparator}/abs/kotlin-stdlib.jar",
        "composeai.daemon.bta.compileClasspath" to "/abs/compose-runtime.jar",
        "composeai.daemon.bta.compilerPlugins" to
          "/abs/kotlin-compose-compiler-plugin-embeddable.jar",
        "composeai.daemon.bta.moduleName" to "samples-cmp",
        "composeai.daemon.bta.outputDir" to "/abs/build/classes/kotlin/main",
        "composeai.daemon.bta.icWorkingDir" to "/abs/build/compose-previews/daemon-state/bta-ic",
      )
    val service = DefaultBtaCompileService.fromSysprops { sysprops[it] }
    assertTrue("expected non-null service, got null", service != null)
    // We don't drive a real compile here — that would bootstrap BTA's impl classloader,
    // which the synthetic /abs/... paths can't satisfy. Constructing the service is enough
    // to prove the sysprop wiring; the lazy `KotlinToolchains` doesn't fire until the
    // first `compile()` call.
  }

  @Test
  fun `backend throwing BtaCompileDiagnosticException becomes Outcome_CompileError`() {
    // Production backend path: the session's compileIncremental throws on
    // COMPILATION_ERROR; forSession's wrapper checks the collector and re-throws as the
    // typed BtaCompileDiagnosticException. The service maps that to CompileError so the
    // editor's existing diagnostic-banner UI surfaces them.
    val errors =
      listOf(
        CompileErrorDetail(
          file = "/abs/path/Foo.kt",
          line = 4,
          column = 17,
          message = "Unresolved reference 'R'",
        ),
        CompileErrorDetail(
          file = "/abs/path/Bar.kt",
          line = 7,
          column = 3,
          message = "Type mismatch.",
        ),
      )
    val service =
      DefaultBtaCompileService(
        backend =
          DefaultBtaCompileService.CompileBackend { _, _ ->
            throw BtaCompileDiagnosticException(errors)
          }
      )
    val outcome = service.compile(sources = listOf(Path.of("/tmp/Hi.kt")), changes = null)
    assertTrue(
      "expected CompileError, got $outcome",
      outcome is BtaCompileService.Outcome.CompileError,
    )
    assertEquals(errors, (outcome as BtaCompileService.Outcome.CompileError).errors)
  }

  @Test
  fun `composeCompilerPlugins passes sourceInformation=true to the Compose plugin`() {
    val jars = listOf(Path.of("/abs/kotlin-compose-compiler-plugin-embeddable.jar"))
    val plugins = DefaultBtaCompileService.composeCompilerPlugins(jars)
    assertEquals(1, plugins.size)
    val plugin = plugins.single()
    assertEquals("androidx.compose.compiler.plugins.kotlin", plugin.pluginId)
    assertEquals(jars, plugin.classpath)
    val sourceInformation = plugin.rawArguments.single { it.key == "sourceInformation" }
    assertEquals(
      "sourceInformation must be true so BTA bytecode stays byte-parity with Gradle's output",
      "true",
      sourceInformation.value,
    )
  }

  @Test
  fun `composeCompilerPlugins is empty when no plugin JARs were resolved`() {
    assertTrue(DefaultBtaCompileService.composeCompilerPlugins(emptyList()).isEmpty())
  }

  @Test
  fun `fromSysprops propagates ineligibilityReason verbatim into the service`() {
    val sysprops =
      mapOf(
        "composeai.daemon.bta.implClasspath" to "/abs/kotlin-build-tools-impl.jar",
        "composeai.daemon.bta.moduleName" to "samples-cmp",
        "composeai.daemon.bta.outputDir" to "/abs/out",
        "composeai.daemon.bta.icWorkingDir" to "/abs/ic",
        "composeai.daemon.bta.ineligibilityReason" to
          "com.google.devtools.ksp plugin applied (stage 2 doesn't drive KSP yet)",
      )
    val service = DefaultBtaCompileService.fromSysprops { sysprops[it] }!!
    val outcome = service.compile(sources = listOf(Path.of("/tmp/Hi.kt")), changes = null)
    assertTrue("expected Fallback, got $outcome", outcome is BtaCompileService.Outcome.Fallback)
    assertEquals(
      "com.google.devtools.ksp plugin applied (stage 2 doesn't drive KSP yet)",
      (outcome as BtaCompileService.Outcome.Fallback).reason,
    )
  }
}
