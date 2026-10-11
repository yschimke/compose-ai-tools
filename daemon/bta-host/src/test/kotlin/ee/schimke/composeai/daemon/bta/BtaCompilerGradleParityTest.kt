@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Gradle vs BTA bytecode parity. `:daemon:bta-host-fixture`'s `fixture/Greeting.kt` is compiled by
 * Gradle's `compileKotlin` and, here, by BTA with the same `MODULE_NAME`; both `.class` files are
 * compared.
 *
 * Asserts structural equivalence — the Compose-transformed descriptor and `kotlin.Metadata` the
 * daemon's hot-swap depends on. Byte equality is only logged (`[parity] ok=…`), so KGP flag drift
 * doesn't flake the test.
 */
class BtaCompilerGradleParityTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `BTA output structurally matches Gradle output for the same source`() {
    val gradleClass = locateGradleCompiledFixture()
    val (implClasspath, composePluginJar, compileClasspath) = splitRuntimeClasspath()
    val source = copyFixtureSource()

    val compiler = BtaCompiler(implClasspath = implClasspath)
    val out = tmp.newFolder("out").toPath()
    val produced =
      compiler.compile(
        sources = listOf(source),
        compileClasspath = compileClasspath,
        outputDir = out,
        compilerPlugins =
          listOf(
            CompilerPlugin(
              "androidx.compose.compiler.plugins.kotlin",
              listOf(composePluginJar),
              emptyList(),
              emptySet(),
            )
          ),
        // Gradle uses the project name as the module name; checked below via `kotlin.Metadata`, so
        // a fixture rename shows up as a mismatch.
        moduleName = "bta-host-fixture",
      )
    val btaClass = produced.first { it.fileName.toString() == "GreetingKt.class" }
    val gradleBytes = Files.readAllBytes(gradleClass)
    val btaBytes = Files.readAllBytes(btaClass)

    // (1) Logged: byte-level parity. If equal, great. If not, where do they first diverge?
    val firstDiff = firstDiffOffset(gradleBytes, btaBytes)
    val byteEqual = firstDiff < 0 && gradleBytes.size == btaBytes.size
    println(
      "[parity] ok=$byteEqual gradleSize=${gradleBytes.size} btaSize=${btaBytes.size} " +
        "sizeDelta=${btaBytes.size - gradleBytes.size} firstDiffOffset=$firstDiff"
    )

    // (2) Hard requirement: both must carry the Compose-transformed descriptor. The
    // daemon's hot-swap relies on reflective method lookup against this exact descriptor.
    val composeDescriptor =
      "(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Ljava/lang/String;"
        .toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Gradle output missing the Compose-transformed descriptor — fixture build is wrong",
      indexOf(gradleBytes, composeDescriptor) >= 0,
    )
    assertTrue(
      "BTA output missing the Compose-transformed descriptor — daemon hot-swap would fail",
      indexOf(btaBytes, composeDescriptor) >= 0,
    )

    // (3) Hard requirement: both must declare `kotlin.Metadata`. Without it the daemon's
    // ClassGraph discovery would mark them as non-Kotlin and the @Preview scan would skip.
    val kotlinMetadata = "Lkotlin/Metadata;".toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Gradle output missing kotlin.Metadata — fixture build is wrong",
      indexOf(gradleBytes, kotlinMetadata) >= 0,
    )
    assertTrue(
      "BTA output missing kotlin.Metadata — class invisible to Kotlin reflection",
      indexOf(btaBytes, kotlinMetadata) >= 0,
    )

    // (4) Hard requirement: both declare the same class FQN. A different class name would
    // make hot-swap target the wrong slot in the child classloader.
    val classFqnBytes = "fixture/GreetingKt".toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Gradle output doesn't declare `fixture/GreetingKt`",
      indexOf(gradleBytes, classFqnBytes) >= 0,
    )
    assertTrue(
      "BTA output doesn't declare `fixture/GreetingKt` — different class name from Gradle's",
      indexOf(btaBytes, classFqnBytes) >= 0,
    )

    // Both should carry "bta-host-fixture" in `kotlin.Metadata` d2, proving `moduleName` took
    // effect (otherwise a module-name mismatch would dominate any byte diff).
    val moduleNameBytes = "bta-host-fixture".toByteArray(Charsets.US_ASCII)
    assertEquals(
      "BTA and Gradle should both embed the matching module name in kotlin.Metadata",
      indexOf(gradleBytes, moduleNameBytes) >= 0,
      indexOf(btaBytes, moduleNameBytes) >= 0,
    )
  }

  // --- fixture plumbing ----------------------------------------------------------------------

  private fun locateGradleCompiledFixture(): Path {
    val dir =
      System.getProperty("composeai.bta.fixtureGradleClassesDir")
        ?: error(
          "Gradle-parity test needs the fixture-compiled classes dir; " +
            "build.gradle.kts populates the system property via the test task."
        )
    val cls = Path.of(dir, "fixture", "GreetingKt.class")
    assertTrue(
      "Gradle hasn't compiled `:daemon:bta-host-fixture` yet — expected $cls. Run via " +
        "`./gradlew :daemon:bta-host:test`; the test task depends on the fixture's classes.",
      cls.toFile().exists(),
    )
    return cls
  }

  private fun copyFixtureSource(): Path {
    val srcDir =
      System.getProperty("composeai.bta.fixtureSourceDir")
        ?: error("composeai.bta.fixtureSourceDir system property is missing")
    val srcFile = Path.of(srcDir, "fixture", "Greeting.kt")
    val dest = tmp.newFolder("src").toPath().resolve("Greeting.kt")
    Files.copy(srcFile, dest)
    return dest
  }

  private data class ClasspathSplit(
    val implClasspath: List<Path>,
    val composePluginJar: Path,
    val compileClasspath: List<Path>,
  )

  private fun splitRuntimeClasspath(): ClasspathSplit {
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
    return ClasspathSplit(implClasspath, composePluginJar, compileClasspath)
  }

  private fun firstDiffOffset(a: ByteArray, b: ByteArray): Int {
    val limit = minOf(a.size, b.size)
    for (i in 0 until limit) {
      if (a[i] != b[i]) return i
    }
    return if (a.size != b.size) limit else -1
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
