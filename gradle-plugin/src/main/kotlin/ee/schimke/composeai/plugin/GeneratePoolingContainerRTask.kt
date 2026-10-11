package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Writes a stand-in `androidx.customview.poolingcontainer.R$id` at the END of the render classpath,
 * so a module whose merged unit-test `R.jar` never arrives still renders.
 *
 * ## The failure
 *
 * `PoolingContainer.<clinit>` reads `androidx.customview.poolingcontainer.R.id.*`, which AGP
 * generates per consumer. When it's missing, every Compose preview in the module fails at class
 * init:
 * ```
 * java.lang.NoClassDefFoundError: Could not initialize class androidx.customview.poolingcontainer.PoolingContainer
 * Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NoClassDefFoundError:
 *   androidx/customview/poolingcontainer/R$id
 * ```
 * [AndroidPreviewSupport]'s main-variant pin normally supplies it, but not everywhere
 * (element-x-android, #5026).
 *
 * ## Why a fabricated R class is correct
 *
 * Both ids are value-less id resources used only as `View.setTag`/`getTag` keys, never resolved
 * through `Resources`, so any two distinct ints suffice. `setTag` rejects keys whose top byte is
 * below 2, so the AAR's `0x0` placeholders won't do; [TAG_IDS] uses package byte `0x7e`, which AGP
 * never emits, so they can't collide with real tag keys.
 *
 * ## Why LAST
 *
 * After AGP's own test classpath, so a real merged `R.jar` always wins and this is inert.
 *
 * ## Why only this class
 *
 * Other pinned R classes (`androidx.core`, `activity`, `compose.ui`) have ids resolved as
 * resources, where synthetic values would be wrong.
 */
@CacheableTask
abstract class GeneratePoolingContainerRTask : DefaultTask() {
  /** Field name to value, defaulted to [TAG_IDS]. Declared as an input so a change re-runs. */
  @get:Input abstract val ids: MapProperty<String, Int>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val root = outputDir.get().asFile
    val packageDir = root.resolve(PACKAGE_PATH)
    packageDir.mkdirs()
    packageDir.resolve("R\$id.class").writeBytes(classBytes(ids.get()))
  }

  internal companion object {
    /**
     * Distinct values in the `0x7e` package (see above): the keys index different tags on the same
     * view.
     */
    val TAG_IDS =
      mapOf(
        "is_pooling_container_tag" to 0x7e0f0001,
        "pooling_container_listener_holder_tag" to 0x7e0f0002,
      )

    const val PACKAGE_PATH = "androidx/customview/poolingcontainer"
    const val CLASS_NAME = "androidx.customview.poolingcontainer.R\$id"

    private const val INTERNAL_NAME = "$PACKAGE_PATH/R\$id"

    /**
     * A class with public static final int ConstantValue fields only, enough for `getstatic`; the
     * enclosing `R` and `InnerClasses` attribute aren't needed.
     */
    fun classBytes(ids: Map<String, Int>): ByteArray {
      val writer = ClassWriter(0)
      writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        INTERNAL_NAME,
        null,
        "java/lang/Object",
        null,
      )
      for ((name, value) in ids.entries.sortedBy { it.key }) {
        writer
          .visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            name,
            "I",
            null,
            value,
          )
          .visitEnd()
      }
      writer.visitEnd()
      return writer.toByteArray()
    }
  }
}
