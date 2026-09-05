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
 * Writes a stand-in `androidx.customview.poolingcontainer.R$id` onto the END of the render
 * classpath, so a module whose merged unit-test `R.jar` never arrives still renders.
 *
 * ## The failure this exists for
 *
 * `ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool` reaches
 * `PoolingContainer.<clinit>`, which reads `androidx.customview.poolingcontainer.R.id.*`. That R
 * class is not in the AAR — AGP generates it per consumer — so when it is missing EVERY Compose
 * preview in the module dies at class initialisation with the same error, and the report reads as
 * if each preview were independently broken:
 * ```
 * java.lang.NoClassDefFoundError: Could not initialize class androidx.customview.poolingcontainer.PoolingContainer
 * Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NoClassDefFoundError:
 *   androidx/customview/poolingcontainer/R$id
 *     at androidx.customview.poolingcontainer.PoolingContainer.<clinit>(PoolingContainer.kt:121)
 * ```
 *
 * [AndroidPreviewSupport] already pins the artifact onto the main variant so AGP merges its R class
 * into `compile_and_runtime_r_class_jar/<variant>UnitTest/…/R.jar`, and that is the mechanism that
 * normally supplies it. It is not sufficient everywhere — element-hq/element-x-android's
 * `:libraries:designsystem` loses all 321 previews to exactly this, with the pin applied
 * (compose-ai-tools#5026).
 *
 * ## Why a fabricated R class is correct here, and not a fudge
 *
 * Both ids are declared by the AAR as bare id resources with no value:
 * ```xml
 * <item name="is_pooling_container_tag" type="id"/>
 * <item name="pooling_container_listener_holder_tag" type="id"/>
 * ```
 *
 * `PoolingContainer` uses them ONLY as `View.setTag`/`getTag` keys. Nothing resolves them through
 * `Resources`, nothing compares them to an id from anywhere else, and no layout references them. So
 * any two distinct ints satisfy the class completely rather than approximately — the ids are opaque
 * keys, and this class hands the initialiser two of them.
 *
 * What is NOT interchangeable is the value range. `View.setTag(int, Object)` rejects a key whose
 * top byte is below 2 ("The key must be an application-specific resource id"), which is why the
 * AAR's own `R.txt` — where both ids are the `0x0` placeholders a compile-time R carries — cannot
 * simply be compiled and put on the classpath. [TAG_IDS] uses the `0x7e` package byte: high enough
 * for that check, and one AGP does not emit (app and library resources land in `0x7f`), so a value
 * here can never collide with a real tag key on the same view — `androidx.compose.ui.R.id.*`, which
 * Compose sets on the same views, included.
 *
 * ## Why it is appended LAST
 *
 * The generated directory goes after AGP's own test classpath in the render task, so a real merged
 * `R.jar` always wins at class-load time and this file is inert. It is a floor for the case where
 * nothing else provides the class, not a replacement for the R class AGP generates.
 *
 * ## Why only this one R class
 *
 * It does not generalise. `androidx.core`, `androidx.activity` and `androidx.compose.ui` — the
 * other R classes [AndroidPreviewSupport] pins artifacts for — carry ids that ARE resolved as
 * resources, where a synthetic value would be wrong rather than merely arbitrary. This class is
 * scoped to the two tag keys whose values genuinely do not matter.
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
     * The two ids, and the `0x7e` package byte the KDoc above explains. Distinct values: the two
     * keys index different tags on the same view, so collapsing them would make one overwrite the
     * other.
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
     * The class file, as `getstatic` needs it: public static final int fields with a ConstantValue,
     * no methods, no `InnerClasses` attribute. The JVM resolves `getstatic
     * androidx/customview/poolingcontainer/R$id.<name> : I` from the field alone — the nesting
     * relationship to the enclosing `R` is a compile-time and reflection nicety that
     * `PoolingContainer` never consults, so the enclosing class is not emitted at all.
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
