package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Regression coverage for issue #1924: discovery must find the consumer module's own `@Preview`
 * functions when its class directory is reached through a **symlink** — the shape produced by
 * overlay build trees such as the AndroidX "androidchka" overlay.
 *
 * ClassGraph canonicalises the classpath element it reports for each class (resolving the symlink),
 * while Gradle/AGP hand discovery the un-resolved `classDirs` location. Matching project classes on
 * the raw `absolutePath` therefore missed every class on a symlinked tree, leaving
 * `projectClassFqns` empty so the method-walk skipped everything and discovery reported `Discovered
 * 0 preview(s)` even though the annotated classes were present (see
 * [PreviewDiscovery.pathMatchKeys]).
 *
 * The hand-rolled class mirrors real bytecode: `androidx.compose.ui.tooling.preview.Preview` has
 * `CLASS` retention, so it's emitted as a `RuntimeInvisibleAnnotations` entry (ASM `visible =
 * false`).
 */
class PreviewDiscoverySymlinkedClassDirTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `previews under a symlinked class dir are discovered and method-walked`() {
    val realClasses = File(tempDir.root, "real/classes")
    writePreviewClass(
      classesDir = realClasses,
      internalName = "test/ZzDiagDirectPreviewKt",
      methodName = "ZzDiagDirectPreview",
    )

    // The path discovery is actually handed points at the class dir through a symlink, exactly as a
    // symlinked / overlay build tree exposes `build/.../classes`. ClassGraph resolves it to the
    // real
    // path; discovery must still recognise the classes as the project's own.
    val linkRoot = File(tempDir.root, "link").toPath()
    try {
      Files.createSymbolicLink(linkRoot, File(tempDir.root, "real").toPath())
    } catch (e: Exception) {
      // Some CI filesystems / Windows without privilege can't create symlinks — skip rather than
      // fail; the production fix is exercised wherever symlinks are supported.
      assumeTrue("symlinks unsupported on this filesystem: ${e.message}", false)
    }
    val symlinkedClasses = File(linkRoot.toFile(), "classes")
    assertThat(symlinkedClasses.canonicalPath).isEqualTo(realClasses.canonicalPath)
    assertThat(symlinkedClasses.absolutePath).isNotEqualTo(realClasses.canonicalPath)

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(symlinkedClasses),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":overlay-samples",
          variantName = "debug",
          projectDirectory = tempDir.root,
          failOnEmpty = true,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val preview = (outcome as PreviewDiscovery.Outcome.Success).manifest.previews.single()
    assertThat(preview.className).isEqualTo("test.ZzDiagDirectPreviewKt")
    assertThat(preview.functionName).isEqualTo("ZzDiagDirectPreview")
  }

  /**
   * Writes a single class with one parameterless `public static` method annotated with
   * `androidx.compose.ui.tooling.preview.Preview` (emitted as `RuntimeInvisibleAnnotations` to
   * match the CLASS-retention bytecode the Compose compiler produces) into [classesDir], laid out
   * by package like a real compiler output directory.
   */
  private fun writePreviewClass(classesDir: File, internalName: String, methodName: String) {
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
    val av: AnnotationVisitor =
      mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", false)
    av.visitEnd()
    mv.visitCode()
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()

    val classFile = File(classesDir, "$internalName.class")
    classFile.parentFile.mkdirs()
    classFile.writeBytes(cw.toByteArray())
  }
}
