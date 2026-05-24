@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage-2 checkpoint #3 — bytecode validation.
 *
 * Two cheap-but-decisive checks on what BTA emits, both required before the daemon's child
 * classloader hot-swap path can rely on BTA output in place of Gradle's `compileKotlin` artefacts:
 *
 * 1. **Determinism.** Same source + same inputs → byte-identical `.class`. If BTA's emission
 *    depends on iteration order in a `HashMap`, or stamps the output with a timestamp, the daemon's
 *    child-loader rotation would see the class "change" on every save even when the
 *    semantically-meaningful behaviour didn't, churning Compose state for no reason.
 *
 * 2. **Structural fingerprint.** The bytecode of an `@Composable` source must carry:
 *     - the Compose plugin's signature transformation
 *       (`(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Ljava/lang/String;`), and
 *     - a `kotlin.Metadata` annotation — same shape Gradle's output uses, decodable by anything
 *       that reads Kotlin reflection (e.g. ClassGraph + DiscoverPreviewsTask).
 *
 * What this checkpoint deliberately does NOT cover:
 *
 * - Byte-by-byte parity with Gradle's `compileKotlin`. That requires a parallel Gradle invocation
 *   and a structural diff (constant-pool reordering would otherwise drown out real divergences).
 *   Tracked as the next-checkpoint item.
 * - Mangled function name parity (Compose's signature hashes). Same blocker as above.
 *
 * The structural fingerprint here is enough to answer "would the daemon's `Class.forName(...)`
 * + reflective Composer-parameter invocation succeed against BTA's output?" — which is the decisive
 *   question for the hot-swap path.
 */
class BtaCompilerBytecodeTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `BTA emits deterministic bytecode for the same source`() {
    val fx = newFixture()
    val compiler = BtaCompiler(implClasspath = fx.implClasspath)

    val out1 = tmp.newFolder("out-1").toPath()
    val out2 = tmp.newFolder("out-2").toPath()

    val produced1 =
      compiler.compile(listOf(fx.source), fx.compileClasspath, out1, listOf(fx.composePlugin))
    val produced2 =
      compiler.compile(listOf(fx.source), fx.compileClasspath, out2, listOf(fx.composePlugin))

    val c1 = produced1.first { it.fileName.toString() == "GreetingKt.class" }
    val c2 = produced2.first { it.fileName.toString() == "GreetingKt.class" }
    val b1 = Files.readAllBytes(c1)
    val b2 = Files.readAllBytes(c2)
    assertArrayEquals(
      "Two BTA compiles of the same source produced different bytecode — non-determinism " +
        "would force the daemon's child classloader to churn on every save. b1.size=${b1.size} b2.size=${b2.size}",
      b1,
      b2,
    )
  }

  @Test
  fun `Compose plugin's signature transformation lands in the emitted bytecode`() {
    val fx = newFixture()
    val compiler = BtaCompiler(implClasspath = fx.implClasspath)
    val out = tmp.newFolder("out").toPath()

    val produced =
      compiler.compile(listOf(fx.source), fx.compileClasspath, out, listOf(fx.composePlugin))
    val greetingClass = produced.first { it.fileName.toString() == "GreetingKt.class" }
    val bytes = Files.readAllBytes(greetingClass)

    // (a) Compose's injected signature. The plugin rewrites
    //     `fun Greeting(name: String): String`
    // to add the `Composer $composer, int $changed` trailing parameters, with the descriptor
    // `(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Ljava/lang/String;`. That exact
    // sequence lands in the constant pool's UTF-8 entries — substring search is enough.
    val composeDescriptor =
      "(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Ljava/lang/String;"
        .toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Greeting bytecode missing the Compose-transformed descriptor " +
        "`$composeDescriptor` — daemon's hot-swap reflection lookup would fail",
      indexOf(bytes, composeDescriptor) >= 0,
    )

    // (b) kotlin.Metadata annotation. Every Kotlin-compiled class carries one; without it the
    // daemon's existing ClassGraph-based discovery (DiscoverPreviewsTask) wouldn't see this
    // class as Kotlin-emitted, and the Compose @Preview annotation wouldn't be detectable.
    val kotlinMetadata = "Lkotlin/Metadata;".toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Greeting bytecode missing the kotlin.Metadata annotation — class would be invisible " +
        "to Kotlin reflection / ClassGraph",
      indexOf(bytes, kotlinMetadata) >= 0,
    )
  }

  // --- fixture plumbing ----------------------------------------------------------------------

  private class Fixture(
    val implClasspath: List<Path>,
    val compileClasspath: List<Path>,
    val composePlugin: CompilerPlugin,
    val source: Path,
  )

  private fun newFixture(): Fixture {
    val raw =
      System.getProperty("composeai.bta.testRuntimeClasspath")
        ?: error("rerun via `./gradlew :daemon:bta-host:test`")
    val runtimeClasspath = raw.split(File.pathSeparator).map { Path.of(it) }
    val implPrefixes = listOf("kotlin-", "kotlinx-", "annotations-", "jna-", "trove4j-")
    val implClasspath = runtimeClasspath.filter { jar ->
      implPrefixes.any { jar.fileName.toString().startsWith(it) }
    }
    val composePluginJar = runtimeClasspath.first {
      it.fileName.toString().startsWith("kotlin-compose-compiler-plugin-embeddable")
    }
    val userOnlyExclusions =
      setOf(composePluginJar) +
        implClasspath
          .filter { jar ->
            val n = jar.fileName.toString()
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
    return Fixture(
      implClasspath = implClasspath,
      compileClasspath = compileClasspath,
      composePlugin =
        CompilerPlugin(
          "androidx.compose.compiler.plugins.kotlin",
          listOf(composePluginJar),
          emptyList(),
          emptySet(),
        ),
      source = greeting,
    )
  }

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
