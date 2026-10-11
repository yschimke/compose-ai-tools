package ee.schimke.composeai.themepin.compiler

import org.jetbrains.kotlin.backend.jvm.extensions.ClassGenerator
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGeneratorExtension
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/**
 * Points a project's own calls to Material 3's `MaterialTheme` at `PreviewMaterialTheme`, which has
 * identical JVM descriptors, so only the owner of the `INVOKESTATIC
 * androidx/compose/material3/MaterialThemeKt.MaterialTheme` instruction changes.
 *
 * Done at bytecode generation rather than in IR because IR extension order relative to the Compose
 * compiler's own signature rewrite depends on plugin-classpath order. Only overloads in [REDIRECTS]
 * are touched (others stay unpinned rather than failing to link), and only classes this compilation
 * produces.
 */
class ThemePinClassGeneratorExtension : ClassGeneratorExtension {

  override fun generateClass(generator: ClassGenerator, declaration: IrClass?): ClassGenerator =
    object : ClassGenerator by generator {
      override fun newMethod(
        declaration: IrFunction?,
        access: Int,
        name: String,
        desc: String,
        signature: String?,
        exceptions: Array<out String>?,
      ): MethodVisitor =
        RedirectingMethodVisitor(
          generator.newMethod(declaration, access, name, desc, signature, exceptions)
        )
    }

  private class RedirectingMethodVisitor(delegate: MethodVisitor) :
    MethodVisitor(Opcodes.API_VERSION, delegate) {
    override fun visitMethodInsn(
      opcode: Int,
      owner: String,
      name: String,
      descriptor: String,
      isInterface: Boolean,
    ) {
      if (
        opcode == Opcodes.INVOKESTATIC &&
          owner == MATERIAL_THEME_OWNER &&
          name == MATERIAL_THEME_NAME &&
          descriptor in REDIRECTS
      ) {
        super.visitMethodInsn(opcode, PREVIEW_THEME_OWNER, PREVIEW_THEME_NAME, descriptor, false)
      } else {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
      }
    }
  }

  companion object {
    const val MATERIAL_THEME_OWNER: String = "androidx/compose/material3/MaterialThemeKt"
    const val MATERIAL_THEME_NAME: String = "MaterialTheme"
    const val PREVIEW_THEME_OWNER: String =
      "ee/schimke/composeai/preview/themepin/PreviewMaterialThemeKt"
    const val PREVIEW_THEME_NAME: String = "PreviewMaterialTheme"

    private const val COMPOSE_TAIL =
      "Lkotlin/jvm/functions/Function2;Landroidx/compose/runtime/Composer;II)V"

    /** The `MaterialTheme` descriptors `theme-pin-runtime` has an identical twin for. */
    val REDIRECTS: Set<String> =
      setOf(
        // MaterialTheme(colorScheme, shapes, typography, content)
        "(Landroidx/compose/material3/ColorScheme;Landroidx/compose/material3/Shapes;" +
          "Landroidx/compose/material3/Typography;$COMPOSE_TAIL",
        // MaterialTheme(colorScheme, motionScheme, shapes, typography, content)
        "(Landroidx/compose/material3/ColorScheme;Landroidx/compose/material3/MotionScheme;" +
          "Landroidx/compose/material3/Shapes;Landroidx/compose/material3/Typography;$COMPOSE_TAIL",
      )
  }
}
