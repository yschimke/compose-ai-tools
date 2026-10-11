@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Incremental compilation through BTA: compiles a three-file fixture with
 * [BtaCompiler.compileIncremental], mutates one source and recompiles, asserting both succeed and
 * an IC working directory is populated.
 *
 * Not asserted: that IC is faster (the fixture is too small) or that only the changed file
 * recompiled (the API doesn't expose the recompile set).
 */
class BtaCompilerIncrementalTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `incremental compile survives a source mutation`() {
    val fx = newMultiFileFixture()

    val workingDir = tmp.newFolder("ic-work").toPath()
    val outputDir1 = tmp.newFolder("out-pass-1").toPath()
    val outputDir2 = tmp.newFolder("out-pass-2").toPath()
    // Served catalogs lead with an extracted classes directory rather than a JAR. Even empty, this
    // pins the BTA API shape and our directory content-hashing path on both passes.
    val catalogClasses = tmp.newFolder("catalog-classes").toPath()
    val compileClasspath = listOf(catalogClasses) + fx.compileClasspath

    val compiler = BtaCompiler(implClasspath = fx.implClasspath)

    // Pass 1 — cold. SourcesChanges.Unknown so BTA treats every source as new
    // and populates its IC caches end-to-end.
    val cold0 = System.nanoTime()
    val pass1 =
      compiler.compileIncremental(
        sources = fx.sources,
        compileClasspath = compileClasspath,
        outputDir = outputDir1,
        workingDir = workingDir,
        compilerPlugins = listOf(fx.composePlugin),
        sourcesChanges = SourcesChanges.Unknown,
      )
    val coldMs = (System.nanoTime() - cold0) / 1_000_000
    assertTrue(
      "Pass-1 produced no .class output: $pass1",
      pass1.any { it.fileName.toString() == "GreetingKt.class" },
    )

    // IC working dir must have been written to — otherwise BTA silently fell back to
    // non-incremental, and we'd be measuring the wrong thing on pass 2.
    val icDir = workingDir.resolve("ic")
    assertTrue(
      "BTA's IC working directory $icDir wasn't populated; classpath-snapshot wiring is wrong",
      icDir.toFile().exists() && icDir.toFile().list().orEmpty().isNotEmpty(),
    )
    // Modify one source — flip the greeting prefix. BTA's IC should pick this up via mtime.
    val modified = fx.sources.first { it.fileName.toString() == "Greeting.kt" }
    modified.writeText(
      """
      package fixture
      import androidx.compose.runtime.Composable
      @Composable
      fun Greeting(name: String): String = "Hi, " + name
      """
        .trimIndent()
    )

    val warm0 = System.nanoTime()
    val pass2 =
      compiler.compileIncremental(
        sources = fx.sources,
        compileClasspath = compileClasspath,
        outputDir = outputDir2,
        workingDir = workingDir,
        compilerPlugins = listOf(fx.composePlugin),
        sourcesChanges = SourcesChanges.ToBeCalculated,
      )
    val warmMs = (System.nanoTime() - warm0) / 1_000_000
    assertTrue(
      "Pass-2 produced no .class output: $pass2",
      pass2.any { it.fileName.toString() == "GreetingKt.class" },
    )

    println("[ic] pass1 (cold, SourcesChanges.Unknown) ms=$coldMs class-count=${pass1.size}")
    println("[ic] pass2 (warm, ToBeCalculated)        ms=$warmMs class-count=${pass2.size}")

    // Cross-check: the second pass's GreetingKt.class must reflect the edit — the "Hi, " bytes
    // are in the source's constant pool. Cheap byte-search avoids needing ASM.
    val greeting2 = pass2.first { it.fileName.toString() == "GreetingKt.class" }
    val bytes = java.nio.file.Files.readAllBytes(greeting2)
    val needle = "Hi, ".toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Pass-2 GreetingKt.class doesn't carry the edited string — IC didn't pick up the source change",
      indexOf(bytes, needle) >= 0,
    )
  }

  // --- fixture plumbing ----------------------------------------------------------------------

  private class Fixture(
    val implClasspath: List<Path>,
    val compileClasspath: List<Path>,
    val composePlugin: CompilerPlugin,
    val sources: List<Path>,
  )

  /**
   * Three sources in one package; two unchanged siblings give IC something to skip on pass 2.
   */
  private fun newMultiFileFixture(): Fixture {
    val (implClasspath, composePluginJar, compileClasspath) = splitRuntimeClasspath()
    val src = tmp.newFolder("src").toPath()

    src
      .resolve("Greeting.kt")
      .writeText(
        """
        package fixture
        import androidx.compose.runtime.Composable
        @Composable
        fun Greeting(name: String): String = "Hello, " + name
        """
          .trimIndent()
      )
    src
      .resolve("Farewell.kt")
      .writeText(
        """
        package fixture
        import androidx.compose.runtime.Composable
        @Composable
        fun Farewell(name: String): String = "Bye, " + name
        """
          .trimIndent()
      )
    src
      .resolve("Names.kt")
      .writeText(
        """
        package fixture
        object Names {
          const val DEFAULT: String = "stranger"
        }
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
      sources =
        listOf(src.resolve("Greeting.kt"), src.resolve("Farewell.kt"), src.resolve("Names.kt")),
    )
  }

  private data class ClasspathSplit(
    val implClasspath: List<Path>,
    val composePluginJar: Path,
    val compileClasspath: List<Path>,
  )

  /**
   * Same classpath partition as [BtaCompilerTest.newFixture], duplicated so each test reads on its
   * own.
   */
  private fun splitRuntimeClasspath(): ClasspathSplit {
    val raw =
      System.getProperty("composeai.bta.testRuntimeClasspath")
        ?: error(
          "BTA spike test needs the runtime classpath; rerun via `./gradlew :daemon:bta-host:test`."
        )
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
    return ClasspathSplit(implClasspath, composePluginJar, compileClasspath)
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
