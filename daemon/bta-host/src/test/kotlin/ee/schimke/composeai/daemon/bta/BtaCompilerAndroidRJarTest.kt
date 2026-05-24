@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage-2 checkpoint #5 — Android variants, distilled to the only piece of compiler input that
 * isn't "plain JVM Kotlin against a list of JARs".
 *
 * Why this is the right framing: BTA never sees Android-specific *inputs* directly. AGP turns
 * resources, manifest, AIDL, BuildConfig, and the R table into ordinary `.class` / `.jar` artefacts
 * BEFORE Gradle's `compileKotlin*` runs. The kotlinc step downstream of AGP is plain JVM Kotlin
 * compilation against a classpath that happens to include those AGP-generated jars. So the
 * Android-specific question reduces to: **can BTA compile Kotlin source that references a synthetic
 * Android R class through a JAR on its compile classpath**, with the Compose plugin still active?
 *
 * If yes, the daemon's stage-2 wire-up needs to assemble the right classpath for an Android variant
 * — same plumbing the existing daemon already does for the test sandbox — but the BTA side is
 * unchanged from the desktop case. Stage 1's `gradle --continuous` fallback remains the safety net
 * for modules that pull in source-generating tooling BTA doesn't model (KSP, KAPT, AGP-generated
 * *.kt sources for synthetic accessors).
 *
 * If no, we have a concrete blocker to feed back upstream and Android stays exclusively on stage 1.
 *
 * Synthetic R.jar is built at test time via `javax.tools.ToolProvider.getSystemJavaCompiler()`. If
 * running on a JRE without javac, the test is `assumeNotNull`-skipped — same shape the project uses
 * for other JDK-feature-gated assertions. JDK 17 (our toolchain) ships javac, so CI will always run
 * it.
 */
class BtaCompilerAndroidRJarTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `BTA compiles a @Composable that references a synthetic Android R class`() {
    val javac = ToolProvider.getSystemJavaCompiler()
    assumeNotNull(
      "No javac on this JRE — synthetic R.jar can't be built. Re-run under a JDK.",
      javac,
    )

    val (implClasspath, composePluginJar, baseCompileClasspath) = splitRuntimeClasspath()

    // Synthesize a minimal `fixture.R` with a single nested `string` table — same shape AGP
    // emits, just trimmed to one field. The compile classpath gets the jar appended; the
    // Compose plugin sees it as any other dependency.
    val rJar = buildSyntheticRJar(tmp.newFolder("rjar").toPath(), javac)
    // Wrap in `listOf` — `List<Path>.plus(Path)` resolves to the `Iterable<Path>` overload
    // because `Path` itself implements `Iterable<Path>` (yielding its name components). That
    // would silently expand `/tmp/.../R.jar` into four single-component entries on the
    // classpath instead of appending the JAR file itself.
    val compileClasspath = baseCompileClasspath + listOf(rJar)

    val src = tmp.newFolder("src").toPath()
    val source = src.resolve("Hi.kt")
    source.writeText(
      """
      package fixture
      import androidx.compose.runtime.Composable
      @Composable
      fun Hi(): Int = R.string.app_name
      """
        .trimIndent()
    )

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
      )

    val hiClass = produced.firstOrNull { it.fileName.toString() == "HiKt.class" }
    assertTrue(
      "BTA compile produced no HiKt.class — Android-style R-jar classpath wiring failed. " +
        "produced=$produced",
      hiClass != null,
    )

    // Sanity: the Compose plugin still ran (compose-aware compile against an Android-style
    // synthetic R). Same byte-search shape as the structural test.
    val bytes = Files.readAllBytes(hiClass!!)
    val composeDescriptor = "(Landroidx/compose/runtime/Composer;I)I".toByteArray(Charsets.US_ASCII)
    assertTrue(
      "Hi bytecode missing the Compose-transformed `Composer + int` trailing args — Compose " +
        "plugin didn't run against the R-jar classpath",
      indexOf(bytes, composeDescriptor) >= 0,
    )

    // Sanity: the inlined constant value (0x7f100000 = 2_131_755_008) appears in the
    // bytecode. Kotlin's compiler inlines `public static final int` references — this is
    // the same behaviour `compileDebugKotlin` produces against AGP's R.jar, so a literal
    // search for `fixture/R$string` would actually be wrong (it would fail in production
    // builds for the same reason). We check for the inlined value instead, which is the
    // actual proof that the R reference was resolved at compile time.
    val expectedValue = 0x7f100000
    val expectedBytes =
      byteArrayOf(
        (expectedValue ushr 24).toByte(),
        ((expectedValue ushr 16) and 0xff).toByte(),
        ((expectedValue ushr 8) and 0xff).toByte(),
        (expectedValue and 0xff).toByte(),
      )
    assertTrue(
      "Hi bytecode missing the inlined R.string.app_name value (0x7f100000) — the synthetic " +
        "R.jar wasn't resolved or Kotlin failed to inline the constant",
      indexOf(bytes, expectedBytes) >= 0,
    )
  }

  /** Compiles a one-field `fixture.R` via javac and bundles the result into a single jar. */
  private fun buildSyntheticRJar(workDir: Path, javac: javax.tools.JavaCompiler): Path {
    val javaDir = workDir.resolve("java/fixture").also { it.toFile().mkdirs() }
    val javaSrc = javaDir.resolve("R.java")
    javaSrc.writeText(
      """
      package fixture;
      public final class R {
        public static final class string {
          public static final int app_name = 0x7f100000;
        }
      }
      """
        .trimIndent()
    )

    val classesDir = workDir.resolve("classes").also { it.toFile().mkdirs() }
    val rc = javac.run(null, null, null, "-d", classesDir.toString(), javaSrc.toString())
    check(rc == 0) { "javac exit=$rc compiling synthetic R.java" }

    val jar = workDir.resolve("R.jar")
    JarOutputStream(jar.toFile().outputStream()).use { jos ->
      classesDir
        .toFile()
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .forEach { f ->
          val entryName = f.toRelativeString(classesDir.toFile()).replace(File.separatorChar, '/')
          jos.putNextEntry(JarEntry(entryName))
          jos.write(f.readBytes())
          jos.closeEntry()
        }
    }
    return jar
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
