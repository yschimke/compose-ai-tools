package ee.schimke.composeai.themepin.compiler

import org.jetbrains.kotlin.backend.jvm.extensions.ClassGenerator
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGeneratorExtension
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/**
 * Points a project's own calls to Material 3's `MaterialTheme` at `PreviewMaterialTheme`.
 *
 * `ee.schimke.composeai:theme-pin-runtime` declares `PreviewMaterialTheme` with the same parameters
 * and defaults as each `MaterialTheme` overload, so the two compile to byte-identical JVM
 * descriptors. The redirect is therefore one change to one instruction: the owner of an
 * `INVOKESTATIC androidx/compose/material3/MaterialThemeKt.MaterialTheme` in code this compilation
 * generates. Arguments, the Compose compiler's `$composer` / `$changed` / `$default` parameters and
 * everything else stay exactly as generated.
 *
 * **Why at bytecode generation and not in IR.** The Compose compiler plugin rewrites the signature
 * of every composable call during its own IR lowering, and the order IR extensions run in follows
 * plugin-classpath order rather than anything either plugin controls. An IR-level redirect would
 * see the call before or after that rewrite depending on the build. By the time a class is written,
 * the call is a fixed instruction with a fixed descriptor.
 *
 * Only the overloads listed in [REDIRECTS] are touched — an overload this runtime has no twin for
 * (a future `MaterialTheme` shape) keeps calling Material 3, unpinned, rather than failing at link
 * time. Library code is never rewritten: this runs on classes the compilation itself produces.
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
