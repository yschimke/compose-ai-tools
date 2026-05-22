@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
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
    val runtimeClasspath = parseRuntimeClasspath()

    // Split the runtime classpath into:
    //   - implClasspath:    kotlin-build-tools-impl + everything it pulls in
    //                       transitively (compiler-embeddable, daemon-embeddable,
    //                       compiler-runner, daemon-client, jna, …). The impl
    //                       won't load without its full compiler stack on its
    //                       isolated classloader's URLs — the SharedApiClassesClassLoader
    //                       parent only exposes `org.jetbrains.kotlin.buildtools.api.*`,
    //                       not the compiler internals.
    //   - composePluginJar: kotlin-compose-compiler-plugin-embeddable (becomes
    //                       the [CompilerPlugin]'s `classpath`).
    //   - compileClasspath: everything else (Compose runtime + kotlin-stdlib),
    //                       fed to BTA as the source's classpath.
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

    val compiler = BtaCompiler(implClasspath = implClasspath)
    val produced =
      compiler.compile(
        sources = listOf(greeting),
        compileClasspath = compileClasspath,
        outputDir = out,
        compilerPlugins =
          listOf(
            CompilerPlugin(
              "androidx.compose.compiler.plugins.kotlin",
              listOf(composePluginJar!!),
              emptyList(),
              emptySet(),
            )
          ),
      )

    // (2) — a .class landed on disk.
    val greetingClass = produced.firstOrNull { it.fileName.toString() == "GreetingKt.class" }
    assertNotNull("Expected GreetingKt.class in $out, produced=$produced", greetingClass)

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
}
