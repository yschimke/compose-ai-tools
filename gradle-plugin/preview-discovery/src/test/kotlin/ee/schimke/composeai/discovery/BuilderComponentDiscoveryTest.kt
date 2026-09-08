package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * `@BuilderComponent` — per-component UI builder policy — is read off the compiled class and
 * attached to the preview, then carried onto the component record.
 *
 * The annotation is written straight into the class file with ASM, the way the sibling catalog
 * tests do, because that is the shape ClassGraph actually reads: `BINARY` retention lands in
 * `RuntimeInvisibleAnnotations`, and a test that constructed the data class directly would prove
 * nothing about the scan.
 */
class BuilderComponentDiscoveryTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `builder policy is read off the annotation and split into pairs`() {
    val jar = File(tempDir.root, "builder-classes.jar")
    writeBuilderPreviewClassJar(jar)

    val outcome = discover(jar)

    val policy = outcome.manifest.previews.single().builder!!
    assertThat(policy.id).isEqualTo("wear-m3/checkbox-button")
    assertThat(policy.canvas).isEqualTo("placeholder")
    assertThat(policy.stateCallbacks)
      .containsExactly(BuilderPair("onCheckedChange", "checked:boolean"))
    assertThat(policy.starter).containsExactly(BuilderPair("label", "Checkbox"))
    assertThat(policy.traits).containsExactly("Action")
    assertThat(policy.nativeOnly).isTrue()
    // Blank arguments record null rather than the value a generator would pick: "the catalog did
    // not say" has to survive the scan or no report can tell it from "the catalog said this".
    assertThat(policy.group).isNull()
    assertThat(policy.displayName).isNull()
    assertThat(policy.variantProperty).isNull()
    assertThat(policy.exclude).isNull()
  }

  @Test
  fun `a value containing an equals sign survives the split`() {
    val jar = File(tempDir.root, "builder-equals-classes.jar")
    writeBuilderPreviewClassJar(jar, starter = arrayOf("query=a=b", "=dropped", "noSeparator"))

    val policy = discover(jar).manifest.previews.single().builder!!

    // Split on the FIRST `=`, so a value may contain one; an entry with a blank key or no
    // separator at all costs that entry rather than the build.
    assertThat(policy.starter).containsExactly(BuilderPair("query", "a=b"))
  }

  @Test
  fun `an all-defaults annotation still records a policy`() {
    val jar = File(tempDir.root, "builder-empty-classes.jar")
    writeBuilderPreviewClassJar(jar, id = null, canvas = null, starter = emptyArray())

    // Not folded to null. Writing @BuilderComponent is a statement that somebody considered this
    // component's builder policy, and "nobody has looked at this one" is only reportable if the
    // record can tell that apart from silence.
    assertThat(discover(jar).manifest.previews.single().builder).isEqualTo(BuilderPolicy())
  }

  @Test
  fun `a preview with no annotation records no policy`() {
    val jar = File(tempDir.root, "no-builder-classes.jar")
    writeBuilderPreviewClassJar(jar, annotate = false)

    assertThat(discover(jar).manifest.previews.single().builder).isNull()
  }

  private fun discover(jar: File): PreviewDiscovery.Outcome.Success =
    PreviewDiscovery.discover(
      PreviewDiscovery.Input(
        classDirs = emptyList(),
        dependencyJars = emptyList(),
        sourceFiles = emptyList(),
        moduleName = ":catalog",
        variantName = "debug",
        projectDirectory = tempDir.root,
        failOnEmpty = true,
        projectClassJars = listOf(jar),
      )
    ) as PreviewDiscovery.Outcome.Success

  private fun writeBuilderPreviewClassJar(
    jar: File,
    annotate: Boolean = true,
    id: String? = "wear-m3/checkbox-button",
    canvas: String? = "placeholder",
    starter: Array<String> = arrayOf("label=Checkbox"),
  ) {
    val internalName = "test/BuilderPreviewKt"
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
      cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "CheckboxButton", "()V", null, null)
    mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", false).visitEnd()
    mv.visitAnnotation("Lee/schimke/composeai/preview/CatalogComponent;", false).apply {
      visit("id", "Toggles/CheckboxButton")
      visitEnd()
    }
    if (annotate) {
      mv.visitAnnotation("Lee/schimke/composeai/preview/BuilderComponent;", false).apply {
        id?.let { visit("id", it) }
        canvas?.let { visit("canvas", it) }
        if (starter.isNotEmpty()) {
          visitArray("starter").apply {
            starter.forEach { visit(null, it) }
            visitEnd()
          }
        }
        if (id != null) {
          visitArray("stateCallbacks").apply {
            visit(null, "onCheckedChange=checked:boolean")
            visitEnd()
          }
          visitArray("traits").apply {
            visit(null, "Action")
            visitEnd()
          }
          visit("nativeOnly", true)
        }
        visitEnd()
      }
    }
    mv.visitCode()
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()

    JarOutputStream(jar.outputStream()).use { jos ->
      jos.putNextEntry(JarEntry("$internalName.class"))
      jos.write(cw.toByteArray())
      jos.closeEntry()
    }
  }
}
