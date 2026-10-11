package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Reads the **literal default** of a preview's value parameters from its compiled body.
 *
 * ### Why bytecode
 *
 * Kotlin metadata records only *that* a default exists, and the Compose compiler inlines default
 * expressions into the function, each guarded by a bit of the synthetic `$default` mask:
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
 * ### Why it matters
 *
 * An editor needs a knob's default to show its value and offer reset. `previewOverride*` passes it
 * at the call site; a parameter knob's is compiled away. See
 * [`docs/design/PARAMETER_KNOB_MIGRATION.md`](../../../../../../../../docs/design/PARAMETER_KNOB_MIGRATION.md).
 *
 * ### What it refuses
 *
 * **Only a lone constant push counts.** Calls (`stringResource(…)`, `Color(…)`) and field reads
 * (`Modifier`) report *no constant default*: a missing default is better than an invented one. The
 * match is exactly the shape above, with the guard bit and store slot checked against the
 * parameter's own position.
 */
internal object PreviewKnobDefaults {

  /**
   * The literal default of each value parameter, keyed by full-list index, as seed text. Absent
   * means none or unreadable. [valueParameterCount] (from metadata) selects the overload and
   * reconstructs the synthetic tail.
   */
  /**
   * The constants of the enum [classInfo] describes, in declaration order, as `name to seed text`,
   * or empty when unreadable. The name is what a default reads as (`GETSTATIC`); the seed text is
   * what a picker offers.
   *
   * Read from the class file (`ACC_ENUM` fields, declaration order) rather than ClassGraph
   * `fieldInfo`, which would cost every build. A `@KnobValue` constant reports its declared text.
   * Empty when two constants claim the same text.
   */
  private const val KNOB_VALUE_DESCRIPTOR = "Lee/schimke/composeai/preview/KnobValue;"

  fun enumConstantsOf(classInfo: ClassInfo): List<Pair<String, String>> {
    val resource = classInfo.resource ?: return emptyList()
    return try {
      resource.open().use { stream ->
        val values = mutableListOf<Pair<String, String>>()
        ClassReader(stream)
          .accept(
            object : ClassVisitor(Opcodes.ASM9) {
              override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
              ): FieldVisitor? {
                if (access and Opcodes.ACC_ENUM == 0) return null
                // Reserve the slot so declaration order holds whether or not an alias is declared.
                val slot = values.size
                values += name to name
                return object : FieldVisitor(Opcodes.ASM9) {
                  override fun visitAnnotation(
                    annotationDescriptor: String,
                    visible: Boolean,
                  ): AnnotationVisitor? {
                    if (annotationDescriptor != KNOB_VALUE_DESCRIPTOR) return null
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                      override fun visit(annotationName: String?, annotationValue: Any?) {
                        val declared = annotationValue as? String ?: return
                        if (declared.isNotEmpty()) values[slot] = name to declared
                      }
                    }
                  }
                }
              }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
          )
        // Ambiguous seed texts degrade the knob to "not seedable" rather than binding arbitrarily.
        val seeds = values.map { it.second }
        if (seeds.size != seeds.toSet().size) emptyList() else values
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

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
   * [readFrom] on raw class bytes, so tests can hand-build the Compose-emitted shape (this module
   * has no Compose plugin).
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
   * Compose packs 31 parameters per `$default` int; more needs a second mask word, which this
   * refuses.
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
      // Static only: instance methods shift local slots, and member previews aren't on this path.
      if (access and Opcodes.ACC_STATIC == 0) return null
      val argumentTypes = Type.getArgumentTypes(descriptor)
      val layout = layoutOf(argumentTypes) ?: return null
      if (found != null) {
        // Two same-named overloads with the same defaulted shape: can't tell which is the preview.
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
     * Slot layout of a defaulted composable overload, `(realParams…, Composer, changed…, default)`,
     * or null. Checking the whole shape stops this reading an unrelated overload.
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
      // After the real parameters: the Composer, then the ints, the last being the `$default` mask.
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
   * Walks a body for the guarded-assignment shape. Labels, frames and line numbers are skipped so
   * adjacency means real instructions.
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
      // Enum defaults compile to `GETSTATIC Owner.CONST : LOwner;`; recorded only when the field's
      // type is its owner (excluding `$VALUES` and other statics).
      insns +=
        if (opcode == Opcodes.GETSTATIC && descriptor == "L$owner;") Insn.EnumConst(owner, name)
        else Insn.Other
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
      insns += Insn.Other
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
      insns += Insn.Other
    }

    /**
     * Constant defaults by parameter index. Matches `iload <mask>; push (1 shl i); iand; ifeq …;
     * push <constant>; store <slot i>`, with bit and slot checked, so the "dirty bits" computation
     * (a load after the branch) can't match.
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
        val value = insns[i + 4]
        if (value !is Insn.Const && value !is Insn.EnumConst) continue
        val store = insns[i + 5] as? Insn.Store ?: continue
        val parameter = layout.parameterSlots.indexOfFirst { it == store.slot }
        if (parameter < 0) continue
        if (bit != (1 shl parameter)) continue
        val type = layout.parameterTypes[parameter]
        if (store.opcode != storeOpcodeFor(type)) continue
        val rendered =
          when (value) {
            // The constant's name is its seed text; checked against the declared type like every
            // other kind.
            is Insn.EnumConst -> value.name.takeIf { type.internalName == value.owner }
            is Insn.Const -> renderConstant(value.value, type)
            else -> null
          }
        rendered?.let { defaults[parameter] = it }
      }
      return defaults
    }

    /**
     * Seed text for [constant] if valid for [type], else null. The type disambiguates (`iconst_1`
     * is `"true"` for Boolean, `"1"` for Int); a mismatch means an unexpected match, which must not
     * be reported.
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

    /** `GETSTATIC Owner.name : LOwner;` — one enum constant, by its own name. */
    data class EnumConst(val owner: String, val name: String) : Insn

    data class Store(val slot: Int, val opcode: Int) : Insn

    data object And : Insn

    data object IfZero : Insn

    data object Other : Insn
  }
}
