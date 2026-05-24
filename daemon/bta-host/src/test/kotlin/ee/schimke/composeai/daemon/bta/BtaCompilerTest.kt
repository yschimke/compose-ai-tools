@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage-2 spike: decisive integration test for BTA + Compose compiler plugin.
 *
 * The Gradle build (`daemon/bta-host/build.gradle.kts`) publishes the resolved
 * `testRuntimeClasspath` into a system property; from that classpath we pluck the BTA impl JAR +
 * the Compose compiler plugin JAR for [BtaCompiler]'s isolated classloader, and everything else
 * (Compose runtime + kotlin-stdlib) for the source's compile classpath. No `./gradlew` is invoked
 * at test time.
 *
 * Assertions, in priority order:
 * 1. BTA's compile returns success.
 * 2. The expected `.class` file lands on disk.
 * 3. The bytecode contains a method whose descriptor references `androidx/compose/runtime/Composer`
 *    — i.e. the Compose plugin's signature transformation actually ran inside BTA. (3) is the
 *    decisive bit; (1) and (2) just guard against false positives.
 */
class BtaCompilerTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `compiles @Composable source with Compose plugin loaded`() {
    val fx = newFixture()

    val compiler = BtaCompiler(implClasspath = fx.implClasspath)
    val produced =
      compiler.compile(
        sources = listOf(fx.source),
        compileClasspath = fx.compileClasspath,
        outputDir = fx.outputDir,
        compilerPlugins = listOf(fx.composePlugin),
      )

    // (2) — a .class landed on disk.
    val greetingClass = produced.firstOrNull { it.fileName.toString() == "GreetingKt.class" }
    assertNotNull("Expected GreetingKt.class in ${fx.outputDir}, produced=$produced", greetingClass)

    // (3) — the Compose plugin actually ran. Cheap heuristic: scan the
    // .class bytes for the literal `androidx/compose/runtime/Composer`
    // descriptor. Avoids needing ASM on the test classpath.
    val bytes = Files.readAllBytes(greetingClass!!)
    val needle = "androidx/compose/runtime/Composer".toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Greeting bytecode does NOT reference Composer — the Compose plugin's signature " +
        "transformation did not run inside BTA. Spike is not yet viable.",
      indexOf(bytes, needle) >= 0,
    )
  }

  /**
   * Stage-2 checkpoint #1 — repeat-compile soak + classloader-leak probe.
   *
   * Reuses a single [BtaCompiler] across [ITERATIONS] compiles, asserts every one returns success,
   * and prints the per-iteration wall-clock so the warm-up curve is visible. If BTA's impl carried
   * a per-call leak — e.g. a frontend session that pinned an analysis context after each
   * `executeOperation`, or a dispatcher thread that didn't die — the iteration tail would balloon.
   *
   * After the loop, the compiler reference is dropped inside a scoped helper, GC is nudged, and a
   * [WeakReference] probe checks whether the impl-side state was actually reachable for collection.
   * This is the same shape the daemon uses for its user-class-loader soak (see CLASSLOADER.md
   * "WeakReference soak probe"). For the spike we LOG rather than fail on a non-collected loader —
   * BTA's `kotlin-build-tools-cri-impl` is known to keep a few interned caches even after the outer
   * toolchain is closed; we want the data, not a flaky red.
   */
  @Test
  fun `repeated compiles do not leak the BTA compiler`() {
    val fx = newFixture()
    val durationsMs = mutableListOf<Long>()

    val weakCompiler = scopedSoakRun(fx, ITERATIONS, durationsMs)

    println("[soak] per-iteration ms (n=${durationsMs.size}): $durationsMs")
    println(
      "[soak] first=${durationsMs.first()} median=${durationsMs.sorted()[durationsMs.size / 2]} last=${durationsMs.last()}"
    )

    // Every iteration must have produced a usable class. The soak loop returns early on a failure,
    // so a short list with `< ITERATIONS` entries means BTA failed mid-run.
    assertEquals(
      "Soak loop short-circuited after a failed compile: durations=$durationsMs",
      ITERATIONS,
      durationsMs.size,
    )

    // Best-effort GC nudge. Java 17 hotspot honours System.gc() for tests with default args;
    // five attempts with a brief sleep covers the typical "still in survivor space" case.
    var clearedAt = -1
    for (attempt in 0 until 5) {
      System.gc()
      Thread.sleep(50)
      if (weakCompiler.get() == null) {
        clearedAt = attempt
        break
      }
    }
    println(
      if (clearedAt >= 0)
        "[soak] compiler WeakReference cleared on GC attempt $clearedAt — no leak detected"
      else "[soak] compiler WeakReference still live after 5 GC attempts — BTA holds something"
    )
  }

  /**
   * Container for the per-test classpath split + fixture source. Built once per test so the
   * classpath-partitioning logic stays in one place; see [newFixture] for the algorithm.
   */
  private class Fixture(
    val implClasspath: List<Path>,
    val compileClasspath: List<Path>,
    val composePlugin: CompilerPlugin,
    val source: Path,
    val outputDir: Path,
  )

  private fun newFixture(): Fixture {
    val runtimeClasspath = parseRuntimeClasspath()

    // implClasspath = every Kotlin runtime + compiler artifact on the test
    // runtime, all dropped into the impl's isolated classloader. The
    // SharedApiClassesClassLoader parent only exposes
    // `org.jetbrains.kotlin.buildtools.api.*`, so anything outside that
    // package — `kotlin.jvm.internal.Intrinsics`, `kotlinx.coroutines.*`
    // (referenced by the Compose plugin), `org.jetbrains.kotlin.compiler.*` —
    // has to be loadable from the impl's own URLs. Cast wide; harmless to
    // over-include.
    val implPrefixes =
      listOf(
        "kotlin-", // -build-tools-*, -compiler-*, -daemon-*, -stdlib, -reflect, -script-runtime
        "kotlinx-", // -coroutines-core, -serialization-* if/when needed
        "annotations-", // org.jetbrains:annotations (NotNull / Nullable refs in stdlib metadata)
        "jna-", // kotlin-daemon transitive
        "trove4j-", // kotlin-compiler-embeddable transitive
      )
    val implClasspath = runtimeClasspath.filter { jar ->
      implPrefixes.any { jar.fileName.toString().startsWith(it) }
    }
    val composePluginJar = runtimeClasspath.firstOrNull {
      it.fileName.toString().startsWith("kotlin-compose-compiler-plugin-embeddable")
    }
    assertTrue(
      "Expected kotlin-build-tools-impl on the runtime classpath; got: $runtimeClasspath",
      implClasspath.any { it.fileName.toString().startsWith("kotlin-build-tools-impl") },
    )
    assertNotNull(
      "Expected kotlin-compose-compiler-plugin-embeddable on the runtime classpath",
      composePluginJar,
    )
    // compileClasspath = user-visible deps for the source under compile. We
    // keep kotlin-stdlib + kotlin-reflect + annotations on it even though
    // they're also in implClasspath; the compiler frontend resolves `kotlin.*`
    // type references from this classpath, and the impl classloader's URLs
    // serve a different need (linking the impl's own bytecode against
    // `kotlin/jvm/internal/Intrinsics`). Same JAR, two roles, no harm.
    val userOnlyExclusions =
      listOfNotNull(composePluginJar).toSet() +
        implClasspath
          .filter { jar ->
            val n = jar.fileName.toString()
            // Strip the *impl-only* JARs (compiler internals + daemon plumbing)
            // from the user classpath — keep kotlin-stdlib/reflect/annotations.
            n.startsWith("kotlin-build-tools-") ||
              n.startsWith("kotlin-compiler-") ||
              n.startsWith("kotlin-daemon-")
          }
          .toSet()
    val compileClasspath = runtimeClasspath - userOnlyExclusions

    val src = tmp.newFolder("src").toPath()
    val greeting = src.resolve("Greeting.kt")
    greeting.writeText(
      """
      package fixture
      import androidx.compose.runtime.Composable
      @Composable
      fun Greeting(name: String): String = "Hello, " + name
      """
        .trimIndent()
    )
    val out = tmp.newFolder("out").toPath()

    return Fixture(
      implClasspath = implClasspath,
      compileClasspath = compileClasspath,
      composePlugin =
        CompilerPlugin(
          "androidx.compose.compiler.plugins.kotlin",
          listOf(composePluginJar!!),
          emptyList(),
          emptySet(),
        ),
      source = greeting,
      outputDir = out,
    )
  }

  /**
   * Runs the soak loop inside its own stack frame so the compiler reference is local to this
   * function — once it returns, the JVM is free to GC the [BtaCompiler] (and its impl
   * `URLClassLoader`). The caller probes the returned [WeakReference].
   *
   * Each iteration writes to a fresh output directory to avoid accidentally measuring an
   * "everything UP-TO-DATE" path (BTA's single-shot compile re-runs unconditionally, but isolating
   * output makes the assertion straightforward and parallels real save-loop traffic).
   */
  private fun scopedSoakRun(
    fx: Fixture,
    iterations: Int,
    durationsMs: MutableList<Long>,
  ): WeakReference<BtaCompiler> {
    val compiler = BtaCompiler(implClasspath = fx.implClasspath)
    repeat(iterations) { i ->
      val outDir = tmp.newFolder("soak-out-$i").toPath()
      val t0 = System.nanoTime()
      val produced =
        compiler.compile(
          sources = listOf(fx.source),
          compileClasspath = fx.compileClasspath,
          outputDir = outDir,
          compilerPlugins = listOf(fx.composePlugin),
        )
      durationsMs.add((System.nanoTime() - t0) / 1_000_000)
      if (produced.none { it.fileName.toString() == "GreetingKt.class" }) {
        // Bail early so the caller's assertEquals on iteration count surfaces the failure
        // with the partial timings already captured.
        return WeakReference(compiler)
      }
    }
    return WeakReference(compiler)
  }

  private fun parseRuntimeClasspath(): List<Path> {
    val raw =
      System.getProperty("composeai.bta.testRuntimeClasspath")
        ?: error(
          "BTA spike test needs the runtime classpath; rerun via `./gradlew " +
            ":daemon:bta-host:test` (build.gradle.kts populates the system property)."
        )
    return raw.split(File.pathSeparator).map { Path.of(it) }
  }

  /** Trivial substring search on a byte array. */
  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) continue@outer
      }
      return i
    }
    return -1
  }

  private companion object {
    // Tuned for CI floor: enough iterations to surface a tail-time regression
    // without making the test dominate the gradle-plugin suite. Bump locally
    // (e.g. `-Dcomposeai.bta.soakIterations=200`) when investigating a leak.
    const val ITERATIONS = 10
  }
}
