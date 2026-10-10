package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Test

/** Compile a consumer against the real API, then break one contract at a time. */
class TypedAdapterCompileTest {
  private fun compile(
    model: String,
    codec: String = "String",
    callbackType: String = "String",
    method: String = "String",
  ): Pair<ExitCode, String> {
    val root = Files.createTempDirectory("typed-adapter-compile")
    try {
      val source = root.resolve("Consumer.kt")
      Files.writeString(
        source,
        """
        import ee.schimke.composeai.discovery.*
        data class Props($model, val onChange: ($callbackType) -> Unit)
        class Adapter(record: ComponentRecord) : TypedComponentAdapter<Props>("acme/component", record) {
          val label = property(Props::label, AdapterValueCodecs.$codec, ${if (codec == "String") "\"Label\"" else "1"})
          val change = stateChange(Props::onChange, label)
        }
        class SDK { fun component(label: $method) {} }
        val callable: (SDK, String) -> Unit = SDK::component
      """
          .trimIndent(),
      )
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
              root.resolve("classes").toString(),
              source.toString(),
            )
        }
      return exit to diagnostics.toString()
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `an external consumer compiles with referenced properties callbacks and methods`() {
    val result = compile("val label: String")
    assertThat(result.first).isEqualTo(ExitCode.OK)
  }

  @Test
  fun `renaming a referenced property fails compilation`() {
    val result = compile("val title: String")
    assertThat(result.first).isEqualTo(ExitCode.COMPILATION_ERROR)
    assertThat(result.second).contains("label")
  }

  @Test
  fun `changing a property type cannot widen the codec to Any`() {
    assertThat(compile("val label: Int").first).isEqualTo(ExitCode.COMPILATION_ERROR)
  }

  @Test
  fun `an incompatible codec fails compilation`() {
    assertThat(compile("val label: String", codec = "Int").first)
      .isEqualTo(ExitCode.COMPILATION_ERROR)
  }

  @Test
  fun `changing a callback payload fails compilation`() {
    assertThat(compile("val label: String", callbackType = "Boolean").first)
      .isEqualTo(ExitCode.COMPILATION_ERROR)
  }

  @Test
  fun `changing a referenced SDK method fails compilation`() {
    assertThat(compile("val label: String", method = "Int").first)
      .isEqualTo(ExitCode.COMPILATION_ERROR)
  }
}
