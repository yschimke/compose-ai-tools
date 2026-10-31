package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import io.github.classgraph.ClassGraph
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Test

/** [ComposableSignature.isDeprecated] against what kotlinc actually writes for each declaration. */
class ComposableSignatureDeprecationTest {

  @Test
  fun `only a real deprecation counts, not the JVM synthetic flag alone`() {
    val root = Files.createTempDirectory("deprecation-fixture")
    try {
      val source = root.resolve("Fixture.kt")
      Files.writeString(
        source,
        """
        package fixture

        fun Plain() {}

        @JvmSynthetic fun KotlinOnly() {}

        @Deprecated("use Plain") fun Warned() {}

        @Deprecated("use Plain", level = DeprecationLevel.ERROR) fun Errored() {}

        @Deprecated("use Plain", level = DeprecationLevel.HIDDEN) fun Hidden() {}
        """
          .trimIndent(),
      )
      val classes = root.resolve("classes")
      val diagnostics = ByteArrayOutputStream()
      val exit =
        PrintStream(diagnostics).use { stream ->
          K2JVMCompiler()
            .exec(
              stream,
              "-no-stdlib",
              "-no-reflect",
              "-jvm-target",
              "17",
              "-classpath",
              requireNotNull(System.getProperty("typedAdapterCompileClasspath")),
              "-d",
              classes.toString(),
              source.toString(),
            )
        }
      assertThat(exit).isEqualTo(ExitCode.OK)

      val deprecated =
        ClassGraph()
          .enableClasspathEntries(classes.toFile())
          .enableClassInfo()
          .enableMethodInfo()
          .enableAnnotationInfo()
          .scan()
          .use { scan ->
            val facade = requireNotNull(scan.getClassInfo("fixture.FixtureKt"))
            facade.methodInfo.associate { it.name to ComposableSignature.isDeprecated(it) }
          }

      assertThat(deprecated)
        .containsExactly(
          "Plain",
          false,
          "KotlinOnly",
          false,
          "Warned",
          true,
          "Errored",
          true,
          "Hidden",
          true,
        )
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
