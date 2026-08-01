package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * The compile half of the sandbox (issue #3090): the snippet's `kotlinc` runs in the same jail its
 * render does, and every way that subprocess can go wrong still answers the route's JSON contract.
 */
class PlaygroundJailedCompilerTest {

  private val tempDir: File =
    java.nio.file.Files.createTempDirectory("playground-jailed-compile-test").toFile()

  @AfterTest
  fun cleanUp() {
    tempDir.deleteRecursively()
  }

  private val workDir = File(tempDir, "snippet-1").apply { mkdirs() }
  private val outputDir = File(workDir, "classes").apply { mkdirs() }

  private fun compiler(
    sandbox: PlaygroundSandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP),
    launcher: (List<String>, File) -> PlaygroundSandboxProbe.Launch,
  ) =
    PlaygroundJailedCompiler(
      sandbox = sandbox,
      javaHome = File("/opt/jdk17"),
      cliClasspath = listOf("/install/lib/cli.jar"),
      btaImplJars = listOf("/install/lib-bta/kotlin-build-tools-impl.jar"),
      compilerPluginJars = listOf("/install/lib-bta/kotlin-compose-compiler-plugin.jar"),
      launcher = launcher,
    )

  private fun report(vararg diagnostics: PlaygroundDiagnostic) =
    PlaygroundSandboxProbe.Launch(
      exitCode = 0,
      stdout =
        "Picked up JAVA_TOOL_OPTIONS: -Dx\n" +
          PlaygroundJailedCompiler.REPORT_PREFIX +
          Json.encodeToString(
            PlaygroundCompileReport.serializer(),
            PlaygroundCompileReport(diagnostics.toList()),
          ),
      stderr = "",
    )

  private fun compile(compiler: PlaygroundJailedCompiler) =
    compiler.compile(
      sources = listOf(File(workDir, "src/Snippet.kt").absolutePath.toPath()),
      classpath = listOf("/catalog/app.jar".toPath()),
      outputDir = outputDir.absolutePath.toPath(),
    )

  @Test
  fun `a clean compile reports no diagnostics`() {
    val diags = compile(compiler { _, _ -> report() })

    assertEquals(emptyList(), diags)
  }

  @Test
  fun `the child's diagnostics come back verbatim`() {
    val diags =
      compile(
        compiler { _, _ ->
          report(
            PlaygroundDiagnostic(
              severity = PlaygroundSeverity.ERROR,
              message = "unresolved reference: Bttn",
              file = "Snippet.kt",
              line = 4,
              ch = 8,
            )
          )
        }
      )

    assertEquals(1, diags.size)
    assertEquals("unresolved reference: Bttn", diags.single().message)
    assertEquals("Snippet.kt", diags.single().file)
    assertEquals(4, diags.single().line)
  }

  @Test
  fun `the request rides a file in the snippet's own work dir, and is cleaned up`() {
    var seen: PlaygroundCompileRequest? = null
    val diags =
      compile(
        compiler { argv, _ ->
          val requestFile = File(argv.last())
          // The jail leaves exactly one path writable — the snippet work dir — so the request has
          // to live there, not in the parent's temp dir the child can't see.
          assertEquals(workDir.absolutePath, requestFile.parentFile.absolutePath)
          seen =
            Json.decodeFromString(PlaygroundCompileRequest.serializer(), requestFile.readText())
          report()
        }
      )

    assertEquals(emptyList(), diags)
    val request = requireNotNull(seen)
    assertEquals(listOf("/catalog/app.jar"), request.classpath)
    assertEquals(outputDir.absolutePath, request.outputDir)
    assertTrue(request.icWorkingDir.startsWith(workDir.absolutePath))
    assertFalse(
      File(workDir, "compile-request.json").exists(),
      "the request file is removed once the compile returns",
    )
  }

  @Test
  fun `the child launches behind the jail, on the same toolchain the in-process path uses`() {
    var argv: List<String> = emptyList()
    compile(
      compiler { command, _ ->
        argv = command
        report()
      }
    )

    assertEquals("bwrap", argv.first(), "the compile runs inside the jail, not beside it")
    assertTrue("/opt/jdk17/bin/java" in argv)
    assertTrue(PlaygroundJailedCompiler.PLAYGROUND_COMPILE_MAIN in argv)
    // The catalog classpath and the BTA toolchain are bound read-only; the work dir is the only
    // writable bind (asserted in PlaygroundSandboxTest) and carries the request + class output.
    listOf(
        "/catalog/app.jar",
        "/install/lib-bta/kotlin-build-tools-impl.jar",
        "/install/lib/cli.jar",
      )
      .forEach { path ->
        assertTrue(
          argv.windowed(3).any { it == listOf("--ro-bind-try", path, path) },
          "$path should be bound read-only: $argv",
        )
      }
    // The sandbox's JVM caps apply to the compiler too — that is the point of the issue.
    assertTrue(argv.any { it.startsWith("-Xmx") })
  }

  @Test
  fun `a jail that produces no report is a diagnostic, not a throw`() {
    val diags =
      compile(
        compiler { _, _ ->
          PlaygroundSandboxProbe.Launch(
            exitCode = 137,
            stdout = "",
            stderr = "the compile exceeded its 180s budget",
          )
        }
      )

    val only = diags.single()
    assertEquals(PlaygroundSeverity.ERROR, only.severity)
    assertTrue("compile sandbox produced no result" in only.message, only.message)
    assertTrue("180s budget" in only.message, only.message)
  }

  @Test
  fun `garbled child output is a diagnostic too`() {
    val diags =
      compile(
        compiler { _, _ ->
          PlaygroundSandboxProbe.Launch(
            exitCode = 0,
            stdout = PlaygroundJailedCompiler.REPORT_PREFIX + "{not json",
            stderr = "",
          )
        }
      )

    assertTrue("unreadable compile report" in diags.single().message, diags.single().message)
  }

  @Test
  fun `a launcher that throws is reported against the jail, not swallowed`() {
    val diags = compile(compiler { _, _ -> throw java.io.IOException("bwrap: not found") })

    assertTrue("could not launch the compile sandbox" in diags.single().message)
    assertTrue("bwrap: not found" in diags.single().message)
  }

  @Test
  fun `concurrent compiles are bounded, and the overflow is told so`() {
    // The in-process compiler this replaces serialized compiles behind one BtaCompileSession, so
    // concurrency was implicitly 1. A subprocess per request removes that: without a bound, N
    // parallel POSTs are N whole JVMs each holding the full per-process memory budget.
    val inFlight = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val compiler =
      PlaygroundJailedCompiler(
        sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP),
        javaHome = File("/opt/jdk17"),
        cliClasspath = listOf("/install/lib/cli.jar"),
        btaImplJars = listOf("/install/lib-bta/impl.jar"),
        compilerPluginJars = emptyList(),
        slots = 1,
        slotWaitSeconds = 1,
      ) { _, _ ->
        inFlight.countDown()
        release.await(10, java.util.concurrent.TimeUnit.SECONDS)
        report()
      }

    val first = Thread { compile(compiler) }.apply { start() }
    assertTrue(inFlight.await(10, java.util.concurrent.TimeUnit.SECONDS), "first compile started")

    // The second request finds the only slot taken and is refused *quickly*, with a diagnostic
    // rather than an unbounded queue.
    val second = compile(compiler)
    assertTrue("busy compiling" in second.single().message, second.single().message)
    assertEquals(PlaygroundSeverity.ERROR, second.single().severity)

    release.countDown()
    first.join(10_000)

    // …and the slot is handed back, so the next request compiles normally.
    assertEquals(emptyList(), compile(compiler))
  }

  @Test
  fun `a compile never outlives a shortened sandbox TTL`() {
    fun budget(ttl: Long, compileTimeout: Long) =
      PlaygroundJailedCompiler(
          sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP, ttlSeconds = ttl),
          javaHome = File("/opt/jdk17"),
          cliClasspath = listOf("/install/lib/cli.jar"),
          btaImplJars = listOf("/install/lib-bta/impl.jar"),
          compilerPluginJars = emptyList(),
          timeoutSeconds = compileTimeout,
        ) { _, _ ->
          report()
        }
        .effectiveTimeoutSeconds

    // An operator who shortens --playground-sandbox-ttl means everything snippet-related is
    // reclaimed by then — not just the render, which honours it via the descriptor's hardTtl.
    assertEquals(45L, budget(ttl = 45, compileTimeout = 180), "the tighter budget wins")
    assertEquals(
      180L,
      budget(ttl = 900, compileTimeout = 180),
      "…and a generous TTL leaves the compile budget in charge",
    )
  }

  @Test
  fun `wrap leaves an unsandboxed host on the in-process compiler`() {
    val inProcess = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() }

    val wrapped =
      PlaygroundJailedCompiler.wrap(
        sandbox = PlaygroundSandbox.NONE,
        inProcess = inProcess,
        btaImplJars = listOf(File("/install/lib-bta/impl.jar")),
        compilerPluginJars = emptyList(),
      ) {}

    assertTrue(wrapped === inProcess, "no sandbox ⇒ nothing to jail the compiler with")
  }

  @Test
  fun `wrap falls back loudly when the toolchain to jail is missing`() {
    val inProcess = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() }
    val logs = mutableListOf<String>()

    val wrapped =
      PlaygroundJailedCompiler.wrap(
        sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.BWRAP),
        inProcess = inProcess,
        btaImplJars = emptyList(),
        compilerPluginJars = emptyList(),
      ) {
        logs += it
      }

    assertTrue(wrapped === inProcess)
    assertTrue(
      logs.single().contains("cannot jail the compiler"),
      "an operator who asked for a sandbox must be told the compiler isn't in it: $logs",
    )
    assertTrue(
      logs.single().contains("snippet execution is still sandboxed"),
      "…and told what IS still contained, so the message isn't alarming beyond its scope",
    )
  }

  @Test
  fun `wrap honours the operator's slot count, and says so`() {
    val logs = mutableListOf<String>()

    PlaygroundJailedCompiler.wrap(
      sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT, memoryMb = 1024),
      inProcess = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
      btaImplJars = listOf(File("/install/lib-bta/impl.jar")),
      compilerPluginJars = emptyList(),
      slots = 1,
    ) {
      logs += it
    }

    // The host budget an operator reasons about is slots × memoryMb, so a `--playground-compile-
    // slots 1` host must see 1024MB — not the default-2 arithmetic it would print if the flag
    // were parsed but never forwarded (which is exactly how it shipped in #3105).
    assertTrue(logs.single().contains("1 at a time"), logs.toString())
    assertTrue(logs.single().contains("peak 1024MB"), logs.toString())
  }

  @Test
  fun `wrap jails the compiler when the sandbox and toolchain are both there`() {
    val logs = mutableListOf<String>()

    val wrapped =
      PlaygroundJailedCompiler.wrap(
        sandbox = PlaygroundSandbox(profile = PlaygroundSandbox.Profile.STRICT),
        inProcess = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        btaImplJars = listOf(File("/install/lib-bta/impl.jar")),
        compilerPluginJars = listOf(File("/install/lib-bta/compose.jar")),
      ) {
        logs += it
      }

    assertTrue(wrapped is PlaygroundJailedCompiler)
    assertTrue(logs.single().contains("strict"), logs.toString())
  }
}
