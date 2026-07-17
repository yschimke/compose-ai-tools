package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Recovers a target composable's real Kotlin value parameters (names / types / defaults) from its
 * `@kotlin.Metadata`, so a consumer can render a true call site for Figma Code Connect rather than
 * a bare `Foo()`.
 *
 * Why metadata and not the JVM signature: a `@Composable` function's bytecode signature is mangled
 * — parameter names are dropped and Compose's compiler inserts synthetic `Composer` and `changed:
 * Int` parameters plus a default-mask arg. `@kotlin.Metadata` carries the *source* Kotlin
 * signature, so reading it yields the parameters as the author wrote them, with none of the
 * synthetic noise.
 *
 * Best-effort and non-fatal: any failure (no metadata, a newer metadata version than this reader
 * understands, a signature that doesn't line up) returns an empty list, and the caller falls back
 * to a parameterless call. Read leniently so a class compiled by a newer Kotlin than the bundled
 * `kotlin-metadata-jvm` still parses instead of throwing.
 */
internal object ComposableSignature {

  /**
   * The value parameters of [method] on [classInfo], or empty when they can't be recovered.
   * [method] is matched inside the class metadata by its JVM name + descriptor, so overloads don't
   * collide.
   */
  fun parametersOf(classInfo: ClassInfo, method: MethodInfo): List<TargetParameter> {
    return try {
      val metadata = readClassMetadata(classInfo) ?: return emptyList()
      val functions =
        when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
          is KotlinClassMetadata.Class -> parsed.kmClass.functions
          is KotlinClassMetadata.FileFacade -> parsed.kmPackage.functions
          is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage.functions
          else -> return emptyList()
        }
      val fn = matchFunction(functions, method) ?: return emptyList()
      fn.valueParameters.map { it.toTargetParameter() }
    } catch (_: Throwable) {
      // Metadata unreadable / newer format / API mismatch — degrade to no parameters.
      emptyList()
    }
  }

  /** Match the metadata function to [method] by JVM signature (name + descriptor). */
  private fun matchFunction(functions: List<KmFunction>, method: MethodInfo): KmFunction? {
    val name = method.name
    val descriptor = method.typeDescriptorStr
    val byName = functions.filter { it.signature?.name == name }
    if (byName.isEmpty()) return null
    if (byName.size == 1) return byName.single()
    // Overloads: disambiguate by the exact JVM descriptor.
    return byName.firstOrNull { it.signature?.descriptor == descriptor } ?: byName.first()
  }

  private fun KmValueParameter.toTargetParameter(): TargetParameter =
    TargetParameter(
      name = name,
      type = renderType(type),
      hasDefault = declaresDefaultValue,
      composableSlot = isComposableFunctionType(type),
    )

  /**
   * A short, readable type: the classifier's simple name, a trailing `?` when nullable, and a
   * best-effort `<…>` of its type arguments (so `List<String>` reads as such). A function type
   * renders as `(…) -> …`. This is a scaffolding hint, not a resolvable reference — the developer
   * or agent completing the Code Connect mapping supplies the real value.
   */
  private fun renderType(type: KmType): String {
    val base =
      when (val c = type.classifier) {
        is KmClassifier.Class -> {
          val simple = c.name.substringAfterLast('/').substringAfterLast('.').replace('$', '.')
          if (isFunctionClassName(c.name)) renderFunctionType(type, simple) else simple
        }
        is KmClassifier.TypeAlias -> c.name.substringAfterLast('/').substringAfterLast('.')
        is KmClassifier.TypeParameter -> "T"
      }
    val args =
      if (
        type.arguments.isNotEmpty() &&
          (type.classifier as? KmClassifier.Class)?.name?.let { !isFunctionClassName(it) } != false
      ) {
        type.arguments
          .joinToString(", ") { renderProjection(it) }
          .let { if (it.isBlank()) "" else "<$it>" }
      } else {
        ""
      }
    return base + args + if (type.isNullable) "?" else ""
  }

  private fun renderProjection(projection: KmTypeProjection): String {
    val t = projection.type ?: return "*"
    return renderType(t)
  }

  /** `(A, B) -> R` for a `kotlin/FunctionN` type, using its type arguments (last = return). */
  private fun renderFunctionType(
    type: KmType,
    @Suppress("UNUSED_PARAMETER") simple: String,
  ): String {
    val args = type.arguments
    if (args.isEmpty()) return "() -> Unit"
    val params = args.dropLast(1).joinToString(", ") { renderProjection(it) }
    val ret = args.last().type?.let { renderType(it) } ?: "Unit"
    return "($params) -> $ret"
  }

  private fun isFunctionClassName(name: String): Boolean = name.startsWith("kotlin/Function")

  /**
   * A function-typed parameter — treated as a composable content slot. Metadata doesn't cheaply
   * expose the `@Composable` *type* annotation here, so this approximates: a `kotlin/FunctionN`
   * parameter on a composable is, in practice, a `content = { … }` slot. Good enough to let a
   * consumer render the slot as a trailing-lambda rather than an inline value.
   */
  private fun isComposableFunctionType(type: KmType): Boolean =
    (type.classifier as? KmClassifier.Class)?.name?.let { isFunctionClassName(it) } == true

  // --- @kotlin.Metadata extraction (ASM, from the class bytes)
  // -------------------------------------

  /**
   * Read the raw `@kotlin.Metadata` values off [classInfo]'s class file and rebuild a `Metadata`.
   */
  private fun readClassMetadata(classInfo: ClassInfo): Metadata? {
    val resource = classInfo.resource ?: return null
    val collector = MetadataCollector()
    resource.open().use { stream ->
      ClassReader(stream)
        .accept(
          object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
              if (descriptor != "Lkotlin/Metadata;") return null
              return collector
            }
          },
          ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
        )
    }
    return collector.build()
  }

  /** Accumulates the `@kotlin.Metadata` annotation members as ASM visits them. */
  private class MetadataCollector : AnnotationVisitor(Opcodes.ASM9) {
    private var kind = 1
    private var metadataVersion = IntArray(0)
    private val data1 = mutableListOf<String>()
    private val data2 = mutableListOf<String>()
    private val mv = mutableListOf<Int>()
    private var extraInt = 0
    private var extraString = ""
    private var packageName = ""
    private var seen = false

    override fun visit(name: String?, value: Any?) {
      seen = true
      when (name) {
        "k" -> kind = value as? Int ?: kind
        // ASM hands a primitive-typed annotation array (here `mv: IntArray`) to `visit` as the
        // whole
        // array in one call — NOT element-by-element through `visitArray` (which only object
        // arrays,
        // e.g. the `String[]` d1/d2, use). So capture `mv` here.
        "mv" -> (value as? IntArray)?.let { metadataVersion = it }
        "xi" -> extraInt = value as? Int ?: extraInt
        "xs" -> extraString = value as? String ?: extraString
        "pn" -> packageName = value as? String ?: packageName
      }
    }

    override fun visitArray(name: String?): AnnotationVisitor {
      return object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(n: String?, value: Any?) {
          when (name) {
            // Fallback: some ASM paths do stream an int array element-by-element.
            "mv" -> (value as? Int)?.let { mv += it }
            "d1" -> (value as? String)?.let { data1 += it }
            "d2" -> (value as? String)?.let { data2 += it }
          }
        }
      }
    }

    fun build(): Metadata? {
      if (!seen) return null
      return Metadata(
        kind = kind,
        metadataVersion = if (mv.isNotEmpty()) mv.toIntArray() else metadataVersion,
        data1 = data1.toTypedArray(),
        data2 = data2.toTypedArray(),
        extraInt = extraInt,
        extraString = extraString,
        packageName = packageName,
      )
    }
  }
}
