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

  /** Runs discovery over a single class dir with no dependency jars (`failOnEmpty = false`). */
  private fun discover(classDir: File): PreviewDiscovery.Outcome =
    PreviewDiscovery.discover(
      PreviewDiscovery.Input(
        classDirs = listOf(classDir),
        dependencyJars = emptyList(),
        sourceFiles = emptyList(),
        moduleName = ":wearApp",
        variantName = "debug",
        projectDirectory = classDir,
        failOnEmpty = false,
      )
    )

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
  fun `an unexpandable preview-family annotation warns instead of vanishing silently`() {
    // Issue #2613: a method whose only preview annotation is a multi-preview meta-annotation
    // (`@WearPreviewLargeRound`) whose annotation class is NOT on the discovery classpath — the
    // classic shape of wear tooling wired only into `screenshotTest`. `resolveMultiPreview` can't
    // see the `@Preview` inside it (its `getClassInfo` returns null) so the preview used to vanish
    // with no diagnostic. Discovery must now emit an actionable WARN naming the method +
    // annotation.
    val classDir = tempDir.newFolder("classes")
    writeMethodWithAnnotationClass(
      classDir,
      internalName = "test/SessionDetailsViewKt",
      methodName = "SessionDetailViewPreview",
      annotationDescriptor = "Lcom/example/wear/WearPreviewLargeRound;",
    )

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":wearApp",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = false,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val success = outcome as PreviewDiscovery.Outcome.Success
    assertThat(success.manifest.previews).isEmpty()
    val joined = success.warnings.joinToString("\n")
    assertThat(joined).contains("test.SessionDetailsViewKt.SessionDetailViewPreview")
    assertThat(joined).contains("@WearPreviewLargeRound")
    assertThat(joined).contains("com.example.wear.WearPreviewLargeRound")
    assertThat(joined).contains("#2613")
  }

  @Test
  fun `a known off-classpath wear device multi-preview is expanded from the built-in table`() {
    // Issue #2613 follow-up: the reported `@WearPreviewLargeRound` on a `main` method, wear tooling
    // wired only into screenshotTest so the annotation class is off the discovery classpath. It's a
    // well-known AndroidX annotation, so discovery expands it from the built-in spec table (one
    // large-round device variant) instead of dropping it or merely warning.
    val classDir = tempDir.newFolder("classes")
    writeMethodWithAnnotationClass(
      classDir,
      internalName = "test/SessionDetailsViewKt",
      methodName = "SessionDetailViewPreview",
      annotationDescriptor = "Landroidx/wear/compose/ui/tooling/preview/WearPreviewLargeRound;",
    )

    val outcome = discover(classDir)
    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val success = outcome as PreviewDiscovery.Outcome.Success
    val previews = success.manifest.previews
    assertThat(previews).hasSize(1)
    val preview = previews.single()
    assertThat(preview.functionName).isEqualTo("SessionDetailViewPreview")
    assertThat(preview.params.device).isEqualTo("id:wearos_large_round")
    assertThat(preview.params.group).isEqualTo("Devices - Large Round")
    // A known, now-expanded annotation must NOT trip the off-classpath warning.
    assertThat(success.warnings.joinToString("\n")).doesNotContain("WearPreviewLargeRound")
  }

  @Test
  fun `a known off-classpath wear font-scale multi-preview fans out to all six variants`() {
    val classDir = tempDir.newFolder("classes")
    writeMethodWithAnnotationClass(
      classDir,
      internalName = "test/HomeKt",
      methodName = "HomePreview",
      annotationDescriptor = "Landroidx/wear/compose/ui/tooling/preview/WearPreviewFontScales;",
    )

    val success = discover(classDir) as PreviewDiscovery.Outcome.Success
    val previews = success.manifest.previews
    assertThat(previews).hasSize(6)
    assertThat(previews.map { it.params.fontScale })
      .containsExactly(0.94f, 1.0f, 1.06f, 1.12f, 1.18f, 1.24f)
    // Every wear font-scale variant renders on the small-round device.
    assertThat(previews.map { it.params.device }.toSet()).containsExactly("id:wearos_small_round")
  }

  @Test
  fun `a known off-classpath compose PreviewFontScale multi-preview fans out to all seven variants`() {
    val classDir = tempDir.newFolder("classes")
    writeMethodWithAnnotationClass(
      classDir,
      internalName = "test/ScreenKt",
      methodName = "ScreenPreview",
      annotationDescriptor = "Landroidx/compose/ui/tooling/preview/PreviewFontScale;",
    )

    val success = discover(classDir) as PreviewDiscovery.Outcome.Success
    val previews = success.manifest.previews
    assertThat(previews).hasSize(7)
    assertThat(previews.map { it.params.name })
      .containsExactly("85%", "100%", "115%", "130%", "150%", "180%", "200%")
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
   * Writes a minimal `.class` file with one public static parameterless method carrying
   * [annotationDescriptor] — an annotation whose own class is deliberately NOT written to [outDir],
   * so the ClassGraph scan records the annotation on the method (parsed from the method's own
   * bytecode) but `getClassInfo` for the annotation returns null. Mirrors an app `main` method
   * tagged with a wear multi-preview annotation whose tooling artifact is absent from the discovery
   * classpath (issue #2613).
   */
  private fun writeMethodWithAnnotationClass(
    outDir: File,
    internalName: String,
    methodName: String,
    annotationDescriptor: String,
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
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, "()V", null, null)
    // `visible = false` mirrors the wear preview annotations' BINARY retention (they land in
    // RuntimeInvisibleAnnotations); ClassGraph reads both visible and invisible annotations.
    mv.visitAnnotation(annotationDescriptor, false).visitEnd()
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
