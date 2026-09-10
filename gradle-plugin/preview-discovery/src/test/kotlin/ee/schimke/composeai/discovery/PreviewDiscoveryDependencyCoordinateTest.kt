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
 * The scan-classpath filter must ask what a dependency **is**, not where its cache entry landed.
 *
 * A JVM dependency resolves under `<cache>/modules-2/files-2.1/<group>/<module>/…`, so matching the
 * path was the same as matching the coordinate and the filter looked correct for years. An AAR does
 * not: AGP extracts it to `<cache>/transforms/<hash>/transformed/<module>/jars/ classes.jar`, which
 * keeps the module name and **drops the group**. Every Compose component library whose module name
 * does not itself say "compose" was therefore dropped from the scan classpath on every Android
 * consumer — `androidx.compose.material3:material3` and
 * `androidx.wear.compose.remote:remote-material3` among them.
 *
 * Measured on `wear-m3-catalog:remote-catalog`, whose 725 previews draw 26 distinct Remote Compose
 * components: every one of them failed to resolve, so all 49 catalog ids collapsed onto the
 * project's own `RemoteSticker` wrapper — two component records for the whole module. With the
 * coordinate supplied, the same run yields 28.
 *
 * The observable here is the cheapest one that isolates the filter: put the `@Preview` annotation
 * class itself in an AAR-shaped jar. On the classpath, discovery resolves it; dropped, discovery
 * says so in as many words.
 */
class PreviewDiscoveryDependencyCoordinateTest {

  @get:Rule val tempDir = TemporaryFolder()

  /** The path AGP's transform produces: the module name, and nothing about its group. */
  private fun aarShapedJar(module: String): File {
    val dir = File(tempDir.root, "transforms/8c3a2aab/transformed/$module/jars")
    dir.mkdirs()
    return File(dir, "classes.jar")
  }

  private var runCount = 0

  private fun discoverWith(jar: File, coordinates: Map<String, String>): List<String> {
    val classDir = tempDir.newFolder("classes-${runCount++}")
    writeEmptyClass(classDir, "test/Empty")
    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = listOf(classDir),
          dependencyJars = listOf(jar),
          dependencyJarCoordinates = coordinates,
          sourceFiles = emptyList(),
          moduleName = ":remote-catalog",
          variantName = "debug",
          projectDirectory = classDir,
          failOnEmpty = false,
        )
      )
    assertThat(outcome).isInstanceOf(PreviewDiscovery.Outcome.Success::class.java)
    return (outcome as PreviewDiscovery.Outcome.Success).warnings
  }

  private val droppedMessage = "@Preview annotation class is not on the ClassGraph classpath"

  @Test
  fun `an AAR whose module name carries no token is dropped without its coordinate`() {
    // The control, and the bug as shipped: nothing in
    // `…/transformed/remote-material3-1.0.0/jars/classes.jar` says "compose", so the path-only
    // filter drops it and every class it carries goes unseen.
    val jar = aarShapedJar("remote-material3-1.0.0")
    writeAnnotationJar(jar)

    assertThat(discoverWith(jar, emptyMap()).joinToString("\n")).contains(droppedMessage)
  }

  @Test
  fun `the same AAR is kept once its coordinate names the group`() {
    val jar = aarShapedJar("remote-material3-1.0.0")
    writeAnnotationJar(jar)

    val warnings =
      discoverWith(
        jar,
        mapOf(jar.absolutePath to "androidx.wear.compose.remote:remote-material3:1.0.0"),
      )

    assertThat(warnings.joinToString("\n")).doesNotContain(droppedMessage)
  }

  @Test
  fun `androidx compose material3 is the same shape, and was dropped the same way`() {
    // Not a hypothetical sibling: `androidx.compose.material3:material3` extracts to
    // `…/transformed/material3/jars/classes.jar`. Every Android consumer of the plugin has been
    // scanning without Material 3 on the classpath.
    val jar = aarShapedJar("material3")
    writeAnnotationJar(jar)

    assertThat(discoverWith(jar, emptyMap()).joinToString("\n")).contains(droppedMessage)
    assertThat(
        discoverWith(jar, mapOf(jar.absolutePath to "androidx.compose.material3:material3:1.4.0"))
          .joinToString("\n")
      )
      .doesNotContain(droppedMessage)
  }

  @Test
  fun `a jar with no coordinate still falls back to its path`() {
    // Callers that cannot attribute a jar — a file dependency, a test harness — keep the old
    // behaviour rather than losing the classpath entirely.
    val jar = File(tempDir.root, "compose-tooling-preview.jar")
    writeAnnotationJar(jar)

    assertThat(discoverWith(jar, emptyMap()).joinToString("\n")).doesNotContain(droppedMessage)
  }

  @Test
  fun `a coordinate that names nothing preview-related is still dropped`() {
    // The filter has to keep filtering: an app's own unrelated dependencies stay off the scan
    // classpath, which is what keeps the scan proportional to the previews.
    val jar = aarShapedJar("okhttp-5.0.0")
    writeAnnotationJar(jar)

    assertThat(discoverWith(jar, mapOf(jar.absolutePath to "com.squareup.okhttp3:okhttp:5.0.0")))
      .isNotEmpty()
    assertThat(
        discoverWith(jar, mapOf(jar.absolutePath to "com.squareup.okhttp3:okhttp:5.0.0"))
          .joinToString("\n")
      )
      .contains(droppedMessage)
  }

  private fun writeAnnotationJar(jar: File) {
    jar.parentFile.mkdirs()
    val internalName = "androidx/compose/ui/tooling/preview/Preview"
    JarOutputStream(jar.outputStream()).use { out ->
      out.putNextEntry(JarEntry("$internalName.class"))
      out.write(annotationClassBytes(internalName))
      out.closeEntry()
    }
  }

  private fun annotationClassBytes(internalName: String): ByteArray {
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
    return cw.toByteArray()
  }

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
