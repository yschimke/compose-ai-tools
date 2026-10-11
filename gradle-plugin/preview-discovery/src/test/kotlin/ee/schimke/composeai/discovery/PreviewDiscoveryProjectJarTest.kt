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
 * Discovery must find the module's own `@Preview` functions when they arrive as a project JAR
 * (AGP's scoped `PROJECT` `CLASSES` artifact), via [PreviewDiscovery.Input.projectClassJars], since
 * AGP 9 built-in Kotlin never writes `build/tmp/kotlin-classes/<variant>` (see #1924). Unlike
 * [PreviewDiscovery.Input.dependencyJars], these are method-walked as project classes.
 *
 * The fixture's `@Preview` is invisible (ASM `visible = false`), matching its `CLASS` retention.
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
    // The dual: the same jar as a dependency must NOT surface previews (dependency classes serve
    // only multi-preview resolution). Named to pass the preview-relevance path filter, so the walk
    // is actually exercised.
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

  @Test
  fun `catalog and override design kit correspondence is discovered`() {
    val jar = File(tempDir.root, "kit-axis-classes.jar")
    writeKitAxisPreviewClassJar(jar)

    val outcome =
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

    val base = outcome.manifest.previews.single { it.overrides == null }
    val variant = outcome.manifest.previews.single { it.overrides != null }
    assertThat(base.catalog?.kitAxis).isEqualTo("Configuration")
    assertThat(variant.catalog?.kitAxis).isEqualTo("Configuration")
    assertThat(variant.overrides?.kitAxis).isEqualTo("Show avatar")
    assertThat(variant.overrides?.kitValue).isEqualTo("True")
  }

  @Test
  fun `an override variant's tier and stated absence are read off the annotation`() {
    // The read matters more than the field: discovery has to answer `false` for a catalog compiled
    // against an annotations jar that predates the parameter (ClassGraph throws for one that is
    // absent rather than handing back its default), and `true` for one that declares it.
    val jar = File(tempDir.root, "secondary-variant-classes.jar")
    writeSecondaryVariantClassJar(jar)

    val outcome =
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

    val variants = outcome.manifest.previews.mapNotNull { it.overrides }.associateBy { it.name }
    assertThat(variants.getValue("segments-13").secondary).isTrue()
    assertThat(variants.getValue("segments-13").noReference)
      .isEqualTo("the kit publishes no 13-segment cell")
    assertThat(variants.getValue("disabled").secondary).isFalse()
    assertThat(variants.getValue("disabled").noReference).isNull()
  }

  @Test
  fun `a CatalogVariant's design kit correspondence is discovered`() {
    // The other half of the same idea: a folded component names the kit's spelling for the axis
    // its one prop turns, so `type=range` can stay the Compose word while the join uses the kit's
    // `Type=Full-screen (range)`. Read off the annotation table, like the component form above.
    val jar = File(tempDir.root, "variant-kit-names-classes.jar")
    writeVariantKitNamesClassJar(jar)

    val outcome =
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

    val catalog = outcome.manifest.previews.single().catalog
    assertThat(catalog?.role).isEqualTo(CatalogRole.VARIANT)
    assertThat(catalog?.componentId).isEqualTo("DatePicker/Modal")
    assertThat(catalog?.kitAxis).isEqualTo("Type")
    assertThat(catalog?.kitValue).isEqualTo("Full-screen (range)")
  }

  @Test
  fun `repeatable Glimmer environments fan out named captures`() {
    val jar = File(tempDir.root, "glimmer-environment-classes.jar")
    writeRepeatableGlimmerEnvironmentClassJar(jar)

    val outcome =
      PreviewDiscovery.discover(
        PreviewDiscovery.Input(
          classDirs = emptyList(),
          dependencyJars = emptyList(),
          sourceFiles = emptyList(),
          moduleName = ":glimmer-catalog",
          variantName = "debug",
          projectDirectory = tempDir.root,
          failOnEmpty = true,
          projectClassJars = listOf(jar),
        )
      ) as PreviewDiscovery.Outcome.Success

    val captures = outcome.manifest.previews.single().captures
    assertThat(captures.map { it.glimmerEnvironment })
      .containsExactly(GlimmerEnvironmentCapture.Light, GlimmerEnvironmentCapture.Busy)
      .inOrder()
    assertThat(captures[0].renderOutput).endsWith("_GLIMMER_light.png")
    assertThat(captures[1].renderOutput).endsWith("_GLIMMER_busy.png")
    assertThat(captures[0].renderOutput.removeSuffix("_GLIMMER_light.png"))
      .isEqualTo(captures[1].renderOutput.removeSuffix("_GLIMMER_busy.png"))
  }

  /**
   * A JAR with one parameterless static method annotated with an invisible (CLASS-retention)
   * `androidx.compose.ui.tooling.preview.Preview`, the simplest shape discovery accepts.
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

  /** Writes the flattened repeatable-annotation shape ClassGraph exposes to discovery. */
  private fun writeRepeatableGlimmerEnvironmentClassJar(jar: File) {
    val internalName = "test/RepeatableGlimmerEnvironmentKt"
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      internalName,
      null,
      "java/lang/Object",
      null,
    )
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "Preview", "()V", null, null)
    mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", false).visitEnd()
    mv.visitAnnotation("Lee/schimke/composeai/preview/GlimmerEnvironmentPreview;", false).apply {
      visitEnum("environment", "Lee/schimke/composeai/preview/GlimmerEnvironment;", "Light")
      visitEnd()
    }
    mv.visitAnnotation("Lee/schimke/composeai/preview/GlimmerEnvironmentPreview;", false).apply {
      visitEnum("environment", "Lee/schimke/composeai/preview/GlimmerEnvironment;", "Busy")
      visitEnd()
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

  /** Writes a `@CatalogVariant` that names both its Compose prop and the kit's own spelling. */
  private fun writeVariantKitNamesClassJar(jar: File) {
    val internalName = "test/VariantKitNamesPreviewKt"
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
      cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "DatePickerRange", "()V", null, null)
    mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", false).visitEnd()
    mv.visitAnnotation("Lee/schimke/composeai/preview/CatalogVariant;", false).apply {
      visit("of", "DatePicker/Modal")
      visit("kitAxis", "Type")
      visit("kitValue", "Full-screen (range)")
      visitArray("props").apply {
        visit(null, "type=range")
        visitEnd()
      }
      visitEnd()
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

  /** Writes two `@OverrideVariant`s, one of them declaring itself second-tier. */
  private fun writeSecondaryVariantClassJar(jar: File) {
    val internalName = "test/SecondaryVariantPreviewKt"
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
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        "SegmentedProgress",
        "()V",
        null,
        null,
      )
    mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", false).visitEnd()
    mv.visitAnnotation("Lee/schimke/composeai/preview/OverrideVariant;", false).apply {
      visit("name", "segments-13")
      visit("secondary", true)
      visit("noReference", "the kit publishes no 13-segment cell")
      visitArray("ints").apply {
        visit(null, "segmentCount=13")
        visitEnd()
      }
      visitEnd()
    }
    // The one a reader browses by: same shape, no tier declared, so it stays primary.
    mv.visitAnnotation("Lee/schimke/composeai/preview/OverrideVariant;", false).apply {
      visit("name", "disabled")
      visitArray("booleans").apply {
        visit(null, "enabled=false")
        visitEnd()
      }
      visitEnd()
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

  /** Writes the issue #3899 contract directly into CLASS-retained annotation tables. */
  private fun writeKitAxisPreviewClassJar(jar: File) {
    val internalName = "test/KitAxisPreviewKt"
    val methodName = "Avatar"
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
    mv.visitAnnotation("Landroidx/compose/ui/tooling/preview/Preview;", false).visitEnd()
    mv.visitAnnotation("Lee/schimke/composeai/preview/CatalogComponent;", false).apply {
      visit("id", "Avatar")
      visit("kitAxis", "Configuration")
      visitEnd()
    }
    mv.visitAnnotation("Lee/schimke/composeai/preview/OverrideVariant;", false).apply {
      visit("name", "avatar")
      visit("kitAxis", "Show avatar")
      visit("kitValue", "True")
      visitArray("strings").apply {
        visit(null, "content=avatar")
        visitEnd()
      }
      visitEnd()
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
