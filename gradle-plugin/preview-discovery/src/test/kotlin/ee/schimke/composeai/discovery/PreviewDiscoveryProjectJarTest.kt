package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Regression coverage for issue #1924: discovery must find the consumer module's own `@Preview`
 * functions when they arrive packaged as a project JAR (AGP's scoped `PROJECT` `CLASSES` artifact)
 * rather than laid out in a [PreviewDiscovery.Input.classDirs] directory.
 *
 * Under AGP 9.x built-in Kotlin (`built_in_kotlinc`) the module's classes never land in the legacy
 * `build/tmp/kotlin-classes/<variant>` directory the directory scan reads, so the Android backend
 * now feeds them in via [PreviewDiscovery.Input.projectClassJars]. Unlike
 * [PreviewDiscovery.Input.dependencyJars] those jars are method-walked as project classes.
 *
 * The hand-rolled class uses a `RuntimeInvisibleAnnotations`-encoded `@Preview` (ASM `visible =
 * false`) to mirror the real bytecode: `androidx.compose.ui.tooling.preview.Preview` has `CLASS`
 * retention, so the compiler emits it as invisible — exactly what `javap -v` showed in the issue.
 */
class PreviewDiscoveryProjectJarTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `previews in a project class jar are discovered and method-walked`() {
    val jar = File(tempDir.root, "classes.jar")
    writePreviewClassJar(
      jar,
      internalName = "test/ZzDiagDirectPreviewKt",
      methodName = "ZzDiagDirectPreview",
    )

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          // No classDirs at all — exactly the built-in-Kotlin shape where the module's own
          // classes are only reachable through the scoped PROJECT CLASSES jar.
          classDirs = emptyList(),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":remote-material3-samples",
          variantName = "debug",
          projectDirectory = tempDir.root,
          failOnEmpty = true,
          projectClassJars = listOf(jar),
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    val success = outcome as PreviewDiscovery.Outcome.Success
    val preview = success.manifest.previews.single()
    assertThat(preview.className).isEqualTo("test.ZzDiagDirectPreviewKt")
    assertThat(preview.functionName).isEqualTo("ZzDiagDirectPreview")
  }

  @Test
  fun `a dependency jar with the same shape is NOT method-walked`() {
    // The dual to the test above: an identical jar handed in as a dependency (not a project jar)
    // must NOT surface previews — dependency classes stay on the ClassGraph classpath for
    // multi-preview resolution but are never walked for their own @Preview methods (issue #1039).
    // Name the jar so it survives the preview-relevance filter (path must contain one of
    // preview|tooling|compose|annotation) — otherwise it'd be dropped before the walk and the
    // test wouldn't actually exercise the project-vs-dependency distinction.
    val jar = File(tempDir.root, "compose-classes.jar")
    writePreviewClassJar(
      jar,
      internalName = "test/ZzDiagDirectPreviewKt",
      methodName = "ZzDiagDirectPreview",
    )

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = emptyList(),
          dependencyJars = listOf(jar),
          sourceFiles = emptyList(),
          moduleName = ":remote-material3-samples",
          variantName = "debug",
          projectDirectory = tempDir.root,
          failOnEmpty = false,
        )
      )

    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    assertThat((outcome as PreviewDiscovery.Outcome.Success).manifest.previews).isEmpty()
  }

  /**
   * Writes a JAR containing a single class with one parameterless `public static` method annotated
   * with `androidx.compose.ui.tooling.preview.Preview`. The annotation is emitted as a
   * `RuntimeInvisibleAnnotations` entry (`visible = false`) to match the CLASS-retention bytecode
   * the Compose compiler produces. A parameterless `()V` method is the simplest shape discovery
   * accepts as a preview (no `@PreviewParameter` wiring needed).
   */
  private fun writePreviewClassJar(jar: File, internalName: String, methodName: String) {
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

    jar.parentFile.mkdirs()
    JarOutputStream(jar.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry("$internalName.class"))
      jos.write(cw.toByteArray())
      jos.closeEntry()
    }
  }
}
