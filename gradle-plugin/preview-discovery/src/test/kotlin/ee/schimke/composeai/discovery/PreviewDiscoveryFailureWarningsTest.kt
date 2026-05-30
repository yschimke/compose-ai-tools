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
 * `failOnEmpty=true` and every candidate method got filtered out (unsupported parameter shapes,
 * etc.), discovery returned `Failure` with `diagnostics` only — the skip reasons collected during
 * the scan were silently dropped, leaving the consumer with no actionable signal for "why didn't my
 * preview show up". See issue #1364.
 *
 * Hand-rolls a `.class` file via ASM so the test exercises the real ClassGraph scan against a
 * skipped `@Preview` candidate, without round-tripping through Kotlin compilation. We use an
 * unsupported-parameter method (one non-`@PreviewParameter` arg) as the skip trigger — `private`
 * previews are no longer dropped, they're surfaced and invoked with `setAccessible(true)`.
 */
class PreviewDiscoveryFailureWarningsTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `failure path carries per-method skip-reason warnings`() {
    val classDir = tempDir.newFolder("classes")
    writeUnsupportedParamPreviewClass(
      classDir,
      internalName = "test/UnsupportedParamPreviewKt",
      methodName = "Hidden",
    )

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
    assertThat(joined).contains("test.UnsupportedParamPreviewKt.Hidden")
    assertThat(joined).contains("@PreviewParameter")
  }

  @Test
  fun `missing @Preview annotation classpath is a soft warning when failOnEmpty is false`() {
    // Reproduces the strictness bug: a non-empty module whose dependency-jar filter dropped
    // the @Preview annotation class. Without `failOnEmpty=true` this used to hard-fail
    // discovery; that broke real consumers (e.g. homeassistant-remotecompose `:demo-app`)
    // where zero previews in one module is perfectly normal. The fix downgrades this to a
    // WARN-level message + diagnostic dump on the Success branch — the build keeps going,
    // the user sees the cause, and `composePreview.failOnEmpty=true` is still the opt-in
    // for the hard-error behaviour.
    val classDir = tempDir.newFolder("classes")
    writeEmptyClass(classDir, internalName = "test/Empty")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":demo-app",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = false,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val success = outcome as PreviewDiscovery.Outcome.Success
    assertThat(success.manifest.previews).isEmpty()
    val joined = success.warnings.joinToString("\n")
    assertThat(joined).contains("discovered 0 previews in module ':demo-app'")
    assertThat(joined).contains("@Preview annotation class is not on the ClassGraph classpath")
    assertThat(joined).contains("composePreview.failOnEmpty=true")
  }

  @Test
  fun `missing @Preview annotation classpath still hard-fails when failOnEmpty is true`() {
    // Symmetric to the soft-warning test: `failOnEmpty=true` keeps the historical hard-fail
    // behaviour for consumers that explicitly opted in.
    val classDir = tempDir.newFolder("classes")
    writeEmptyClass(classDir, internalName = "test/Empty")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":demo-app",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = true,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Failure::class.java)
    val failure = outcome as PreviewDiscovery.Outcome.Failure
    assertThat(failure.reason).contains("@Preview annotation class is not on the ClassGraph")
  }

  @Test
  fun `Failure warnings default to empty for source-compatibility`() {
    // Existing callers constructing Failure positionally (reason, diagnostics) must keep
    // working — `warnings` is a new optional field with an empty default.
    val failure = PreviewDiscovery.Outcome.Failure(reason = "nope", diagnostics = listOf("d"))
    assertThat(failure.warnings).isEmpty()
  }

  /**
   * Writes a minimal `.class` file containing one public static method annotated with
   * `androidx.compose.ui.tooling.preview.Preview` that takes a single `int` parameter with no
   * `@PreviewParameter` wiring. Mirrors the JVM shape Kotlin's compiler produces for a top-level
   * `@Preview fun Hidden(x: Int)` — discovery flags it as an unsupported-parameter preview and
   * skips it with a warning. The method body is a no-op (`RETURN`); discovery only inspects the
   * annotation + signature, never invokes the method.
   */
  private fun writeUnsupportedParamPreviewClass(
    outDir: File,
    internalName: String,
    methodName: String,
  ) {
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
      cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, "(I)V", null, null)
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

  /**
   * Writes a minimal empty `.class` file (no methods, no annotations). Used by the soft-warning
   * tests where we want `scanClassCount > 0` but no preview-able methods so discovery returns zero
   * previews and falls into the `previewAnnotationsMissing` diagnostic branch.
   */
  private fun writeEmptyClass(outDir: File, internalName: String) {
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      internalName,
      null,
      "java/lang/Object",
      null,
    )
    cw.visitEnd()
    val classFile = File(outDir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(cw.toByteArray())
  }
}
