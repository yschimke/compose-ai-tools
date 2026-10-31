package ee.schimke.composeai.cli

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Whether a class's new compiler output can replace its running copy by a hot swap: same fields and
 * methods (what the JVM's redefinition requires), and every method whose code changed a composable
 * one, which a recomposition runs again. Line numbers are ignored: an edit shifts every method
 * below it.
 */
internal object HotSwapCheck {

  /** Why [after] cannot hot-swap [before], or null when it can. */
  fun why(before: ByteArray, after: ByteArray): String? {
    val old = shape(before)
    val new = shape(after)
    if (old.header != new.header) return "its declaration changed (${old.header} -> ${new.header})"
    if (old.fields != new.fields) return "fields or constants added, removed or changed"
    if (old.methods.keys != new.methods.keys) {
      val added = (new.methods.keys - old.methods.keys).firstOrNull()
      val removed = (old.methods.keys - new.methods.keys).firstOrNull()
      return "methods ${if (added != null) "added ($added)" else "removed ($removed)"}"
    }
    for ((key, body) in new.methods) {
      if (body == old.methods.getValue(key)) continue
      if (!key.contains(COMPOSER)) return "$key changed, and it is not composable"
    }
    return null
  }

  private const val COMPOSER = "Landroidx/compose/runtime/Composer;"

  private class Shape(
    val header: String,
    val fields: Set<String>,
    /** "access name descriptor" to a rendering of its code. */
    val methods: Map<String, String>,
  )

  private fun shape(bytes: ByteArray): Shape {
    var header = ""
    val fields = sortedSetOf<String>()
    val methods = sortedMapOf<String, String>()
    ClassReader(bytes)
      .accept(
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
          ) {
            header = "$access $name : $superName ${interfaces?.joinToString(",")}"
          }

          override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
          ): FieldVisitor? {
            // The constant too: redefinition keeps a static field's value and never runs the class
            // initializer again, so a changed constant would not reach code that reads the field.
            fields +=
              "$access $name $descriptor = ${value?.let { "${it.javaClass.simpleName} $it" }}"
            return null
          }

          override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
          ): MethodVisitor {
            val key = "$access $name$descriptor"
            return CodeText { methods[key] = it }
          }
        },
        ClassReader.SKIP_FRAMES,
      )
    return Shape(header, fields, methods)
  }

  /** Renders a method's instructions and constants; labels by order, line numbers left out. */
  private class CodeText(private val done: (String) -> Unit) : MethodVisitor(Opcodes.ASM9) {
    private val text = StringBuilder()
    private val labels = HashMap<Label, Int>()

    private fun label(label: Label) = labels.getOrPut(label) { labels.size }

    private fun emit(vararg parts: Any?) {
      parts.joinTo(text, " ")
      text.append('\n')
    }

    override fun visitInsn(opcode: Int) = emit(opcode)

    override fun visitIntInsn(opcode: Int, operand: Int) = emit(opcode, operand)

    override fun visitVarInsn(opcode: Int, varIndex: Int) = emit(opcode, varIndex)

    override fun visitTypeInsn(opcode: Int, type: String) = emit(opcode, type)

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
      emit(opcode, owner, name, descriptor)

    override fun visitMethodInsn(
      opcode: Int,
      owner: String,
      name: String,
      descriptor: String,
      isInterface: Boolean,
    ) = emit(opcode, owner, name, descriptor, isInterface)

    override fun visitInvokeDynamicInsn(
      name: String,
      descriptor: String,
      bootstrapMethodHandle: Handle,
      vararg bootstrapMethodArguments: Any?,
    ) = emit("indy", name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments.toList())

    override fun visitJumpInsn(opcode: Int, label: Label) = emit(opcode, "L${label(label)}")

    override fun visitLabel(label: Label) = emit("L${label(label)}:")

    override fun visitLdcInsn(value: Any?) = emit("ldc", value?.javaClass?.simpleName, value)

    override fun visitIincInsn(varIndex: Int, increment: Int) = emit("iinc", varIndex, increment)

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) =
      emit("table", min, max, "L${label(dflt)}", labels.map { "L${label(it)}" })

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>) =
      emit("lookup", keys?.toList(), "L${label(dflt)}", labels.map { "L${label(it)}" })

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) =
      emit("multianew", descriptor, numDimensions)

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) =
      emit("try", "L${label(start)}", "L${label(end)}", "L${label(handler)}", type)

    override fun visitEnd() = done(text.toString())
  }
}
