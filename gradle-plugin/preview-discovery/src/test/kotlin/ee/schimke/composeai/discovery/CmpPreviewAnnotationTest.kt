package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Compose Multiplatform's own `@Preview` — `org.jetbrains.compose.ui.tooling.preview.Preview`, from
 * `compose.components.uiToolingPreview` — must be discovered like the androidx one.
 *
 * It was not, and the failure was invisible: the plugin already keeps
 * `components-ui-tooling-preview` on the discovery classpath, so the annotation class resolved,
 * `previewAnnotationsMissing` stayed false, and discovery wrote an empty manifest without a word.
 * joreilly/BikeShare's `:common` declares eleven previews against it and discovery reported zero.
 *
 * Hand-rolls `.class` files via ASM rather than round-tripping through a Kotlin compile, matching
 * the other discovery unit tests: the annotation's BINARY retention means it lands in
 * `RuntimeInvisibleAnnotations`, which is exactly what `visible = false` writes here.
 */
class CmpPreviewAnnotationTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `a CMP @Preview is discovered`() {
    val classDir = tempDir.newFolder("classes")
    writeAnnotationClass(classDir, "org/jetbrains/compose/ui/tooling/preview/Preview")
    writePreviewMethodClass(
      classDir,
      internalName = "dev/johnoreilly/common/ui/BikeSharePreviewsKt",
      methodName = "StationViewPreview",
      annotationDescriptor = "Lorg/jetbrains/compose/ui/tooling/preview/Preview;",
    )

    val outcome = discover(classDir, moduleName = ":common")

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val previews = (outcome as PreviewDiscovery.Outcome.Success).manifest.previews
    assertThat(previews.map { it.functionName }).containsExactly("StationViewPreview")
  }

  @Test
  fun `the androidx @Preview still is`() {
    // Control for the test above: the same fixture shape with the androidx annotation. If this
    // one ever fails, the CMP result above says nothing about FQN recognition.
    val classDir = tempDir.newFolder("classes")
    writeAnnotationClass(classDir, "androidx/compose/ui/tooling/preview/Preview")
    writePreviewMethodClass(
      classDir,
      internalName = "test/AndroidPreviewsKt",
      methodName = "AndroidPreview",
      annotationDescriptor = "Landroidx/compose/ui/tooling/preview/Preview;",
    )

    val outcome = discover(classDir, moduleName = ":app")

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val previews = (outcome as PreviewDiscovery.Outcome.Success).manifest.previews
    assertThat(previews.map { it.functionName }).containsExactly("AndroidPreview")
  }

  private fun discover(classDir: File, moduleName: String): PreviewDiscovery.Outcome =
    PreviewDiscovery.discover(
      PreviewDiscovery.Input(
        classDirs = listOf(classDir),
        dependencyJars = emptyList(),
        sourceFiles = emptyList(),
        moduleName = moduleName,
        variantName = "debug",
        projectDirectory = classDir,
        failOnEmpty = false,
      )
    )

  /** A public static parameterless method carrying [annotationDescriptor]. */
  private fun writePreviewMethodClass(
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
    // `visible = false` mirrors BINARY retention — both @Preview annotations declare it, so both
    // land in RuntimeInvisibleAnnotations. ClassGraph reads visible and invisible alike.
    mv.visitAnnotation(annotationDescriptor, false).visitEnd()
    mv.visitCode()
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()
    write(outDir, internalName, cw)
  }

  /** The annotation class itself, so `scanResult.getClassInfo(fqn)` resolves it. */
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
    write(outDir, internalName, cw)
  }

  private fun write(outDir: File, internalName: String, cw: ClassWriter) {
    val classFile = File(outDir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(cw.toByteArray())
  }
}
