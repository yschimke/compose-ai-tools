package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Reads the **literal default value** of a preview's value parameters out of its compiled body.
 *
 * ### Why this has to read bytecode
 *
 * Kotlin metadata records *that* a parameter declares a default, never what the default is, and the
 * Compose compiler does not emit a separate `$default` bridge whose constant pool could be read
 * either — it compiles the default expressions **inline** into the function, each guarded by a bit
 * of the synthetic `$default` mask:
 * ```
 * iload  <mask>          // the trailing `int $default` parameter
 * iconst_1               // 1 shl <parameter index>
 * iand
 * ifeq   L1              // caller supplied this one — skip the default
 * ldc    "Shopping list"
 * astore_0               // …otherwise assign it
 * L1:
 * ```
 *
 * So the value is there, in the one place a reader can reach it, and nowhere else.
 *
 * ### Why it matters
 *
 * A knob without its default is a control a viewer cannot draw: `PreviewOverrideDeclaration`
 * carries `default` and `current`, which is how an editor shows what a field holds before anyone
 * touches it and how it offers "reset". The `previewOverride*` surface gets both for free because
 * the author passes the default at the call site; a parameter knob's is compiled away. That
 * asymmetry is the single reason a preview cannot yet move from one format to the other — see
 * [`docs/design/PARAMETER_KNOB_MIGRATION.md`](../../../../../../../../docs/design/PARAMETER_KNOB_MIGRATION.md).
 *
 * ### What it deliberately refuses
 *
 * **Only a lone constant push counts.** `title: String = "Shopping list"` is one `LDC` and a store;
 * `label: String = stringResource(Res.string.label)` is a call, `accent: Color = Color(0xFF3366FF)`
 * is a call, `modifier: Modifier = Modifier` is a field read. Each of those is reported as *no
 * constant default* rather than guessed at, because a wrong default is worse than a missing one: a
 * viewer showing an absent default knows to say nothing, while one showing an invented value tells
 * the reader the preview does something it does not.
 *
 * The refusal is structural, not a heuristic — the pattern matched is exactly the six-instruction
 * shape above, with the guard bit and the store slot both checked against the parameter's own
 * position. Anything else falls out.
 */
internal object PreviewKnobDefaults {

  /**
   * The literal default of each value parameter of [method] on [classInfo], keyed by the
   * parameter's index in the full value-parameter list, rendered as the text a seed would carry.
   *
   * Absent from the map means "no constant default" — either the parameter has none, or its default
   * is an expression this cannot read. [valueParameterCount] is the count Kotlin metadata reported,
   * used to pick the right overload and to reconstruct the synthetic tail.
   */
  fun readFrom(
    classInfo: ClassInfo,
    method: MethodInfo,
    valueParameterCount: Int,
  ): Map<Int, String> {
    val resource = classInfo.resource ?: return emptyMap()
    return try {
      resource.open().use { stream -> readFrom(stream, method.name, valueParameterCount) }
    } catch (_: Throwable) {
      // A class file this can't read is a preview with no known defaults, not a failed discovery.
      emptyMap()
    }
  }

  /**
   * [readFrom] against raw class bytes. Split out so a test can drive the matcher with a method it
   * built instruction by instruction: the shape this reads is one the *Compose compiler* emits, so
   * a fixture written in Kotlin in this module — which has no Compose plugin — would compile to the
   * ordinary `name$default` bridge instead and prove nothing about the pattern.
   */
  fun readFrom(
    classBytes: java.io.InputStream,
    methodName: String,
    valueParameterCount: Int,
  ): Map<Int, String> {
    if (valueParameterCount !in 1..BITS_PER_DEFAULT_INT) return emptyMap()
    val reader = DefaultsReader(methodName, valueParameterCount)
    ClassReader(classBytes).accept(reader, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
    return reader.defaults()
  }

  /**
   * Compose packs 31 parameters per `$default` int (one bit each). A preview with more than that
   * carries a second mask word and the single-guard pattern below no longer describes it — a shape
   * rare enough that refusing it is cheaper than supporting it.
   */
  private const val BITS_PER_DEFAULT_INT = 31

  /** Compose's `changed` ints encode this many parameters each — a *different* rate to the mask. */
  private const val SLOTS_PER_CHANGED_INT = 10

  private class DefaultsReader(
    private val methodName: String,
    private val valueParameterCount: Int,
  ) : ClassVisitor(Opcodes.ASM9) {

    /** Null until a matching overload is seen; set to the empty map on a second, ambiguous one. */
    private var found: Map<Int, String>? = null
    private var ambiguous = false

    fun defaults(): Map<Int, String> = if (ambiguous) emptyMap() else found.orEmpty()

    override fun visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      exceptions: Array<out String>?,
    ): MethodVisitor? {
      if (name != methodName) return null
      // Static only: an instance method shifts every local slot by the receiver, and a `@Preview`
      // on
      // a member function is resolved through a receiver the renderer constructs rather than the
      // defaults path this reads.
      if (access and Opcodes.ACC_STATIC == 0) return null
      val argumentTypes = Type.getArgumentTypes(descriptor)
      val layout = layoutOf(argumentTypes) ?: return null
      if (found != null) {
        // Two overloads of one name with the same defaulted shape. Discovery records the function
        // name only, so nothing here can tell which one carries the `@Preview`; reading either
        // one's defaults would be a coin flip presented as fact.
        ambiguous = true
        return null
      }
      val visitor = DefaultsMethodVisitor(layout)
      found = emptyMap()
      return object : MethodVisitor(Opcodes.ASM9, visitor) {
        override fun visitEnd() {
          found = visitor.harvest()
          super.visitEnd()
        }
      }
    }

    /**
     * The local-slot layout of a defaulted composable overload, or null when [argumentTypes] is not
     * one: `(realParams…, Composer, changed…, default)`, with the counts the calling convention
     * fixes. Checking the whole shape — not merely "ends in two ints" — is what stops this reading
     * an unrelated overload's body and reporting its constants as this preview's defaults.
     */
    private fun layoutOf(argumentTypes: Array<Type>): SlotLayout? {
      val changedInts =
        ((valueParameterCount + SLOTS_PER_CHANGED_INT - 1) / SLOTS_PER_CHANGED_INT).coerceAtLeast(1)
      if (argumentTypes.size != valueParameterCount + 1 + changedInts + 1) return null
      if (argumentTypes[valueParameterCount].className != COMPOSER_FQN) return null
      for (i in valueParameterCount + 1 until argumentTypes.size) {
        if (argumentTypes[i].sort != Type.INT) return null
      }
      var slot = 0
      val parameterSlots = IntArray(valueParameterCount)
      for (i in 0 until valueParameterCount) {
        parameterSlots[i] = slot
        slot += argumentTypes[i].size
      }
      // Past the real parameters: the Composer (one slot) then the ints, the last of which is the
      // `$default` mask this reads the guard bits from.
      var tail = slot + 1
      repeat(changedInts) { tail += 1 }
      return SlotLayout(
        parameterSlots = parameterSlots,
        parameterTypes = argumentTypes.copyOfRange(0, valueParameterCount),
        maskSlot = tail,
      )
    }
  }

  private const val COMPOSER_FQN = "androidx.compose.runtime.Composer"

  private class SlotLayout(
    val parameterSlots: IntArray,
    val parameterTypes: Array<Type>,
    val maskSlot: Int,
  )

  /**
   * Walks a method body looking for the guarded-assignment shape, one parameter at a time.
   *
   * Labels, frames and line numbers are ignored rather than recorded, so "the instruction after the
   * branch" means the next *real* instruction — a jump target label sitting between them would
   * otherwise break the adjacency the match depends on.
   */
  private class DefaultsMethodVisitor(private val layout: SlotLayout) :
    MethodVisitor(Opcodes.ASM9) {

    private val insns = mutableListOf<Insn>()

    override fun visitInsn(opcode: Int) {
      insns +=
        when (opcode) {
          Opcodes.ICONST_M1 -> Insn.Const(-1)
          Opcodes.ICONST_0 -> Insn.Const(0)
          Opcodes.ICONST_1 -> Insn.Const(1)
          Opcodes.ICONST_2 -> Insn.Const(2)
          Opcodes.ICONST_3 -> Insn.Const(3)
          Opcodes.ICONST_4 -> Insn.Const(4)
          Opcodes.ICONST_5 -> Insn.Const(5)
          Opcodes.LCONST_0 -> Insn.Const(0L)
          Opcodes.LCONST_1 -> Insn.Const(1L)
          Opcodes.FCONST_0 -> Insn.Const(0f)
          Opcodes.FCONST_1 -> Insn.Const(1f)
          Opcodes.FCONST_2 -> Insn.Const(2f)
          Opcodes.DCONST_0 -> Insn.Const(0.0)
          Opcodes.DCONST_1 -> Insn.Const(1.0)
          Opcodes.IAND -> Insn.And
          else -> Insn.Other
        }
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
      insns +=
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) Insn.Const(operand)
        else Insn.Other
    }

    override fun visitLdcInsn(value: Any?) {
      insns += if (value is String || value is Number) Insn.Const(value) else Insn.Other
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
      insns +=
        when (opcode) {
          Opcodes.ILOAD -> Insn.Load(varIndex)
          Opcodes.ISTORE,
          Opcodes.LSTORE,
          Opcodes.FSTORE,
          Opcodes.DSTORE,
          Opcodes.ASTORE -> Insn.Store(varIndex, opcode)
          else -> Insn.Other
        }
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
      insns += if (opcode == Opcodes.IFEQ) Insn.IfZero else Insn.Other
    }

    override fun visitMethodInsn(
      opcode: Int,
      owner: String,
      name: String,
      descriptor: String,
      isInterface: Boolean,
    ) {
      insns += Insn.Other
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
      insns += Insn.Other
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
      insns += Insn.Other
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
      insns += Insn.Other
    }

    /**
     * The constant defaults this body assigns, keyed by parameter index.
     *
     * The shape matched is `iload <mask>; push (1 shl i); iand; ifeq …; push <constant>; store
     * <slot of parameter i>`. Both the guard bit and the store slot are checked against the
     * parameter's own position, so the *other* place a body reads the mask — the "dirty bits"
     * computation, which is `iload <mask>; push bit; iand; ifeq …; iload <dirty>; …` — cannot
     * match: a load, not a constant, follows its branch.
     */
    fun harvest(): Map<Int, String> {
      val defaults = mutableMapOf<Int, String>()
      for (i in insns.indices) {
        if (i + 5 >= insns.size) break
        val load = insns[i] as? Insn.Load ?: continue
        if (load.slot != layout.maskSlot) continue
        val bit = (insns[i + 1] as? Insn.Const)?.value as? Int ?: continue
        if (insns[i + 2] !is Insn.And) continue
        if (insns[i + 3] !is Insn.IfZero) continue
        val constant = (insns[i + 4] as? Insn.Const)?.value ?: continue
        val store = insns[i + 5] as? Insn.Store ?: continue
        val parameter = layout.parameterSlots.indexOfFirst { it == store.slot }
        if (parameter < 0) continue
        if (bit != (1 shl parameter)) continue
        if (store.opcode != storeOpcodeFor(layout.parameterTypes[parameter])) continue
        renderConstant(constant, layout.parameterTypes[parameter])?.let { defaults[parameter] = it }
      }
      return defaults
    }

    /**
     * The seed text for [constant] when it is a valid value of [type], or null when the two
     * disagree.
     *
     * The type check is what turns `iconst_1` into `"true"` for a `Boolean` and `"1"` for an `Int`:
     * both compile to the same instruction, and the parameter's declared type is the only thing
     * that says which the author wrote. A constant whose Java type doesn't match the parameter's is
     * dropped — that means the pattern matched something this doesn't understand, and a default it
     * doesn't understand is one it must not report.
     */
    private fun renderConstant(constant: Any, type: Type): String? =
      when (type.sort) {
        Type.BOOLEAN -> (constant as? Int)?.let { (it != 0).toString() }
        Type.INT -> (constant as? Int)?.toString()
        Type.LONG -> (constant as? Long)?.toString()
        Type.FLOAT -> (constant as? Float)?.toString()
        Type.DOUBLE -> (constant as? Double)?.toString()
        Type.OBJECT -> if (type.className == "java.lang.String") constant as? String else null
        else -> null
      }

    private fun storeOpcodeFor(type: Type): Int =
      when (type.sort) {
        Type.LONG -> Opcodes.LSTORE
        Type.FLOAT -> Opcodes.FSTORE
        Type.DOUBLE -> Opcodes.DSTORE
        Type.OBJECT,
        Type.ARRAY -> Opcodes.ASTORE
        else -> Opcodes.ISTORE
      }
  }

  /** The handful of instruction shapes the match cares about; everything else is [Insn.Other]. */
  private sealed interface Insn {
    data class Load(val slot: Int) : Insn

    data class Const(val value: Any) : Insn

    data class Store(val slot: Int, val opcode: Int) : Insn

    data object And : Insn

    data object IfZero : Insn

    data object Other : Insn
  }
}
