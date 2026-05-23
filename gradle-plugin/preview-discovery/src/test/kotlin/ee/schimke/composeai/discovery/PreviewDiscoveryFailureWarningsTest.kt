package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Regression coverage for [PreviewDiscovery.Outcome.Failure] carrying per-method `warnings`
 * symmetrically with [PreviewDiscovery.Outcome.Success]. The historical bug: when
 * `failOnEmpty=true` and every candidate method got filtered out (private `@Preview`, unsupported
 * parameter shapes), discovery returned `Failure` with `diagnostics` only — the skip reasons
 * collected during the scan were silently dropped, leaving the consumer with no actionable signal
 * for "why didn't my preview show up". See issue #1364.
 *
 * Hand-rolls a `.class` file via ASM so the test exercises the real ClassGraph scan against a
 * private-method-with-`@Preview`-annotation candidate, without round-tripping through Kotlin
 * compilation.
 */
class PreviewDiscoveryFailureWarningsTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `failure path carries per-method skip-reason warnings`() {
    val classDir = tempDir.newFolder("classes")
    writePrivatePreviewClass(classDir, internalName = "test/PrivatePreviewKt", methodName = "Hidden")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":app",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = true,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Failure::class.java)
    val failure = outcome as PreviewDiscovery.Outcome.Failure
    assertThat(failure.warnings).isNotEmpty()
    // The actionable signal: name the method and explain WHY it was skipped.
    val joined = failure.warnings.joinToString("\n")
    assertThat(joined).contains("test.PrivatePreviewKt.Hidden")
    assertThat(joined).contains("private")
  }

  @Test
  fun `Failure warnings default to empty for source-compatibility`() {
    // Existing callers constructing Failure positionally (reason, diagnostics) must keep
    // working — `warnings` is a new optional field with an empty default.
    val failure = PreviewDiscovery.Outcome.Failure(reason = "nope", diagnostics = listOf("d"))
    assertThat(failure.warnings).isEmpty()
  }

  /**
   * Writes a minimal `.class` file containing one private static method annotated with
   * `androidx.compose.ui.tooling.preview.Preview`. Mirrors the JVM shape Kotlin's compiler
   * produces for a top-level `private fun @Preview Hidden()` — file-class with a private static
   * method, single annotation entry. The method body is a no-op (`RETURN`); discovery only
   * inspects the annotation + visibility, never invokes the method.
   */
  private fun writePrivatePreviewClass(outDir: File, internalName: String, methodName: String) {
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      internalName,
      null,
      "java/lang/Object",
      null,
    )
    val mv =
      cw.visitMethod(
        Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
        methodName,
        "()V",
        null,
        null,
      )
    val av: AnnotationVisitor =
      mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", true)
    av.visitEnd()
    mv.visitCode()
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()

    val classFile = File(outDir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(cw.toByteArray())
  }
}
