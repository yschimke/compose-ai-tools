package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Pins the bytecode matcher that recovers a parameter knob's **literal default**.
 *
 * The fixtures are assembled here rather than written in Kotlin because the shape being matched is
 * one the **Compose compiler** emits: defaults inlined into the function behind a `$default` mask,
 * with no `name$default` bridge. This module has no Compose plugin, so a Kotlin fixture would
 * compile to the ordinary bridge and prove nothing about the pattern. Assembling it also lets the
 * decoys be exact — in particular the "dirty bits" block, which reads the same mask with the same
 * guard and is the one thing a looser matcher would swallow.
 *
 * The end-to-end proof that this shape is the one `kotlinc` + the Compose plugin actually produce
 * lives in the gradle-plugin's functional tests, which compile a real `@Preview`.
 */
class PreviewKnobDefaultsTest {

  @Test
  fun `a literal default is recovered for every seedable kind`() {
    val defaults =
      readDefaults(
        parameterTypes =
          listOf(
            STRING,
            Type.BOOLEAN_TYPE,
            Type.INT_TYPE,
            Type.LONG_TYPE,
            Type.FLOAT_TYPE,
            Type.DOUBLE_TYPE,
          )
      ) { mv, layout ->
        layout.assignConstant(mv, parameter = 0) { it.visitLdcInsn("Shopping list") }
        layout.assignConstant(mv, parameter = 1) { it.visitInsn(Opcodes.ICONST_1) }
        layout.assignConstant(mv, parameter = 2) { it.visitInsn(Opcodes.ICONST_3) }
        layout.assignConstant(mv, parameter = 3) { it.visitLdcInsn(4281563647L) }
        layout.assignConstant(mv, parameter = 4) { it.visitLdcInsn(0.5f) }
        layout.assignConstant(mv, parameter = 5) { it.visitLdcInsn(1.5) }
      }

    assertThat(defaults)
      .containsExactlyEntriesIn(
        mapOf(
          0 to "Shopping list",
          // `iconst_1` for a Boolean is `true`; for an Int it is `1`. The instruction is identical
          // and the declared type is the only thing that says which the author wrote.
          1 to "true",
          2 to "3",
          3 to "4281563647",
          4 to "0.5",
          5 to "1.5",
        )
      )
  }

  @Test
  fun `the dirty-bits block that reads the same mask is not mistaken for a default`() {
    // The decoy, and the reason the matcher requires a *constant* immediately after the branch:
    // Compose emits `iload mask; push bit; iand; ifeq …; iload dirty; push …; ior; istore dirty`
    // near the top of every defaulted composable. It reads the same mask under the same guard, and
    // a matcher that only looked for "mask, bit, and, ifeq" would report the dirty-bit constant as
    // the parameter's default.
    val defaults =
      readDefaults(parameterTypes = listOf(Type.INT_TYPE)) { mv, layout ->
        val skip = Label()
        mv.visitVarInsn(Opcodes.ILOAD, layout.maskSlot)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IAND)
        mv.visitJumpInsn(Opcodes.IFEQ, skip)
        mv.visitVarInsn(Opcodes.ILOAD, layout.maskSlot + 1)
        mv.visitIntInsn(Opcodes.BIPUSH, 6)
        mv.visitInsn(Opcodes.IOR)
        mv.visitVarInsn(Opcodes.ISTORE, layout.maskSlot + 1)
        mv.visitLabel(skip)
      }

    assertThat(defaults).isEmpty()
  }

  @Test
  fun `a default that is an expression rather than a literal is reported as none`() {
    // `label: String = stringResource(...)` is a call, `modifier: Modifier = Modifier` a field
    // read.
    // Both are the overwhelmingly common shape in this repository's own samples, and both must come
    // back as "no default" — a viewer showing an invented value tells the reader the preview does
    // something it does not.
    val defaults =
      readDefaults(parameterTypes = listOf(STRING, STRING)) { mv, layout ->
        layout.guard(mv, parameter = 0) {
          it.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/example/Strings",
            "label",
            "()Ljava/lang/String;",
            false,
          )
          it.visitVarInsn(Opcodes.ASTORE, layout.slotOf(0))
        }
        layout.guard(mv, parameter = 1) {
          it.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/example/Strings",
            "FALLBACK",
            "Ljava/lang/String;",
          )
          it.visitVarInsn(Opcodes.ASTORE, layout.slotOf(1))
        }
      }

    assertThat(defaults).isEmpty()
  }

  @Test
  fun `a literal alongside an expression still yields the literal`() {
    // The realistic mixed case: one knob a viewer can show a default for, one it cannot. Recovering
    // only what is recoverable beats reporting nothing because a sibling was unreadable.
    val defaults =
      readDefaults(parameterTypes = listOf(STRING, Type.INT_TYPE)) { mv, layout ->
        layout.guard(mv, parameter = 0) {
          it.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/example/Strings",
            "label",
            "()Ljava/lang/String;",
            false,
          )
          it.visitVarInsn(Opcodes.ASTORE, layout.slotOf(0))
        }
        layout.assignConstant(mv, parameter = 1) { it.visitIntInsn(Opcodes.BIPUSH, 12) }
      }

    assertThat(defaults).containsExactlyEntriesIn(mapOf(1 to "12"))
  }

  @Test
  fun `a guard bit that does not match the parameter it stores into is refused`() {
    // Not a shape the compiler emits — which is the point. The bit and the slot are checked against
    // each other, so a body this doesn't actually understand cannot be read as if it did.
    val defaults =
      readDefaults(parameterTypes = listOf(Type.INT_TYPE, Type.INT_TYPE)) { mv, layout ->
        val skip = Label()
        mv.visitVarInsn(Opcodes.ILOAD, layout.maskSlot)
        mv.visitInsn(Opcodes.ICONST_1) // bit for parameter 0…
        mv.visitInsn(Opcodes.IAND)
        mv.visitJumpInsn(Opcodes.IFEQ, skip)
        mv.visitInsn(Opcodes.ICONST_5)
        mv.visitVarInsn(Opcodes.ISTORE, layout.slotOf(1)) // …storing into parameter 1
        mv.visitLabel(skip)
      }

    assertThat(defaults).isEmpty()
  }

  @Test
  fun `a method with no Compose tail is not read at all`() {
    // An ordinary Kotlin function with defaults gets a `name$default` bridge instead, and its
    // locals
    // are laid out differently. Matching on the whole shape — not merely "ends in ints" — is what
    // keeps this from reading an unrelated overload's constants as a preview's defaults.
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null)
    val mv =
      writer.visitMethod(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        METHOD,
        "(Ljava/lang/String;II)V",
        null,
        null,
      )
    mv.visitCode()
    val skip = Label()
    mv.visitVarInsn(Opcodes.ILOAD, 2)
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitInsn(Opcodes.IAND)
    mv.visitJumpInsn(Opcodes.IFEQ, skip)
    mv.visitLdcInsn("nope")
    mv.visitVarInsn(Opcodes.ASTORE, 0)
    mv.visitLabel(skip)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(2, 3)
    mv.visitEnd()
    writer.visitEnd()

    assertThat(PreviewKnobDefaults.readFrom(writer.toByteArray().inputStream(), METHOD, 1))
      .isEmpty()
  }

  @Test
  fun `two defaulted overloads of one name are refused rather than guessed between`() {
    // Discovery records the function name only, so nothing here can tell which one carries the
    // `@Preview`. Reading either one's defaults would be a coin flip presented as fact.
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null)
    repeat(2) {
      emitComposableMethod(writer, listOf(Type.INT_TYPE)) { mv, layout ->
        layout.assignConstant(mv, parameter = 0) { it.visitInsn(Opcodes.ICONST_2) }
      }
    }
    writer.visitEnd()

    assertThat(PreviewKnobDefaults.readFrom(writer.toByteArray().inputStream(), METHOD, 1))
      .isEmpty()
  }

  // --- assembly helpers -------------------------------------------------------------------------

  private fun readDefaults(
    parameterTypes: List<Type>,
    body: (MethodVisitor, ComposableLayout) -> Unit,
  ): Map<Int, String> {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null)
    emitComposableMethod(writer, parameterTypes, body)
    writer.visitEnd()
    return PreviewKnobDefaults.readFrom(
      writer.toByteArray().inputStream(),
      METHOD,
      parameterTypes.size,
    )
  }

  /**
   * Emits `METHOD(realParams…, Composer, int changed, int default)` — the exact shape the Compose
   * compiler gives a composable whose parameters all declare defaults — with [body] filling in the
   * default assignments.
   */
  private fun emitComposableMethod(
    writer: ClassWriter,
    parameterTypes: List<Type>,
    body: (MethodVisitor, ComposableLayout) -> Unit,
  ) {
    val changedInts = ((parameterTypes.size + 9) / 10).coerceAtLeast(1)
    val descriptor =
      Type.getMethodDescriptor(
        Type.VOID_TYPE,
        *(parameterTypes + COMPOSER + List(changedInts + 1) { Type.INT_TYPE }).toTypedArray(),
      )
    val mv =
      writer.visitMethod(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        METHOD,
        descriptor,
        null,
        null,
      )
    mv.visitCode()
    body(mv, ComposableLayout(parameterTypes, changedInts))
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(4, 16)
    mv.visitEnd()
  }

  /** Local-slot arithmetic for the emitted method, so a fixture can name a parameter by index. */
  private class ComposableLayout(val parameterTypes: List<Type>, changedInts: Int) {
    private val slots = IntArray(parameterTypes.size)

    val maskSlot: Int

    init {
      var slot = 0
      parameterTypes.forEachIndexed { i, type ->
        slots[i] = slot
        slot += type.size
      }
      maskSlot = slot + 1 + changedInts
    }

    fun slotOf(parameter: Int): Int = slots[parameter]

    /** The `iload mask; push (1 shl i); iand; ifeq …` guard, with [assign] as its body. */
    fun guard(mv: MethodVisitor, parameter: Int, assign: (MethodVisitor) -> Unit) {
      val skip = Label()
      mv.visitVarInsn(Opcodes.ILOAD, maskSlot)
      pushBit(mv, 1 shl parameter)
      mv.visitInsn(Opcodes.IAND)
      mv.visitJumpInsn(Opcodes.IFEQ, skip)
      assign(mv)
      mv.visitLabel(skip)
    }

    /** [guard] whose body is a single constant push and the matching store. */
    fun assignConstant(mv: MethodVisitor, parameter: Int, push: (MethodVisitor) -> Unit) {
      guard(mv, parameter) {
        push(it)
        it.visitVarInsn(storeOpcode(parameterTypes[parameter]), slots[parameter])
      }
    }

    private fun pushBit(mv: MethodVisitor, bit: Int) {
      when {
        bit <= 5 -> mv.visitInsn(Opcodes.ICONST_0 + bit)
        bit <= Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, bit)
        else -> mv.visitIntInsn(Opcodes.SIPUSH, bit)
      }
    }

    private fun storeOpcode(type: Type): Int =
      when (type.sort) {
        Type.LONG -> Opcodes.LSTORE
        Type.FLOAT -> Opcodes.FSTORE
        Type.DOUBLE -> Opcodes.DSTORE
        Type.OBJECT,
        Type.ARRAY -> Opcodes.ASTORE
        else -> Opcodes.ISTORE
      }
  }

  private companion object {
    const val OWNER = "com/example/FixtureKt"
    const val METHOD = "KnobbedPreview"
    val STRING: Type = Type.getObjectType("java/lang/String")
    val COMPOSER: Type = Type.getObjectType("androidx/compose/runtime/Composer")
  }
}
