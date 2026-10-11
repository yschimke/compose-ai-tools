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
 * [PreviewDiscovery.Outcome.Failure] carries per-method `warnings` like
 * [PreviewDiscovery.Outcome.Success], so with `failOnEmpty=true` and every candidate skipped the
 * reasons aren't lost (#1364). Hand-rolls `.class` files with ASM to exercise the real scan; the
 * skip trigger is an unsupported parameter (private previews are no longer skipped).
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
    // A non-empty module whose dep-jar filter dropped the @Preview annotation: a WARN with
    // diagnostics on Success, not a hard failure, since zero previews in one module is normal.
    // `failOnEmpty=true` opts into failing.
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
    // #2613: a preview annotated only with a multi-preview annotation whose class is off the
    // classpath must produce an actionable WARN naming method and annotation, not vanish.
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
    // #2613 follow-up: a well-known AndroidX annotation off the classpath is expanded from the
    // built-in table instead.
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
   * A minimal class with one static `@Preview` method taking an un-annotated `int` (like `@Preview
   * fun Hidden(x: Int)`), which discovery skips with a warning. Only the signature is inspected.
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
   * A minimal class with one static method carrying [annotationDescriptor], whose own class is NOT
   * written, so `getClassInfo` returns null (#2613).
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

  @Test
  fun `sources declaring @Preview with zero discovered previews warns instead of saying nothing`() {
    // #4890: annotation reachable, outputs non-empty, yet sources declare @Preview and none were
    // found. Previously silent until a later bundle failure; reproduced by joreilly/BikeShare's
    // `:common`.
    val classDir = tempDir.newFolder("classes")
    // Puts the @Preview annotation class itself on the scan classpath, so previewAnnotationsMissing
    // is false and the OTHER soft-warning branch cannot account for the message.
    writeAnnotationClass(classDir, internalName = "androidx/compose/ui/tooling/preview/Preview")
    writeEmptyClass(classDir, internalName = "test/NoPreviewsHere")

    val sourceFile = tempDir.newFile("StationListUI.kt")
    sourceFile.writeText(
      """
      package test

      @Preview
      @Composable
      fun StationViewPreview() {}
      """
        .trimIndent()
    )

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = emptyList(),
          sourceFiles = listOf(sourceFile),
          moduleName = ":common",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = false,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val success = outcome as PreviewDiscovery.Outcome.Success
    assertThat(success.manifest.previews).isEmpty()
    val joined = success.warnings.joinToString("\n")
    assertThat(joined).contains("discovered 0 previews in module ':common'")
    // Names the source file, so the reader knows the previews were authored and where.
    assertThat(joined).contains("StationListUI.kt")
    // And carries the same diagnostic dump the other zero-preview paths emit.
    assertThat(joined).contains("0-previews diagnostics for module ':common'")
    assertThat(joined).contains("classDirs")
  }

  @Test
  fun `a module with no @Preview in its sources stays silent`() {
    // Zero previews with no @Preview in sources is normal and must stay quiet.
    val classDir = tempDir.newFolder("classes")
    writeAnnotationClass(classDir, internalName = "androidx/compose/ui/tooling/preview/Preview")
    writeEmptyClass(classDir, internalName = "test/DataLayer")

    val sourceFile = tempDir.newFile("Repository.kt")
    sourceFile.writeText("package test\n\nclass Repository\n")

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = emptyList(),
          sourceFiles = listOf(sourceFile),
          moduleName = ":data",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = false,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val joined = (outcome as PreviewDiscovery.Outcome.Success).warnings.joinToString("\n")
    assertThat(joined).doesNotContain("0-previews diagnostics")
  }

  /** A minimal annotation class so `getClassInfo(fqn)` resolves; no members needed. */
  private fun writeAnnotationClass(outDir: File, internalName: String) {
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION,
      internalName,
      null,
      "java/lang/Object",
      arrayOf("java/lang/annotation/Annotation"),
    )
    cw.visitEnd()
    val classFile = File(outDir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(cw.toByteArray())
  }

  /**
   * An empty class: `scanClassCount > 0` with no previews, for the `previewAnnotationsMissing`
   * branch.
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
