package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
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
/**
 * A composable's source signature as metadata recorded it: its value parameters and, when it is an
 * extension, the receiver it is declared on.
 *
 * The receiver is what a printed call site cannot do without. `AnimatedVisibility` is declared on
 * `ColumnScope`, so it resolves only inside a `Column` — printing it at file scope yields an
 * unresolved reference. A generator that has no receiver field can only guess; one that has it can
 * refuse.
 */
internal data class ComposableSignatureInfo(
  /** The source-level function name, straight from metadata — never the mangled JVM name. */
  val name: String,
  val parameters: List<TargetParameter>,
  val receiver: String?,
  /**
   * True when the declaration is `public` (or `internal`, which is still callable from the same
   * module). A `private` or `protected` composable cannot be called from a file a generator writes,
   * so a call site for one is a compile error waiting to happen.
   */
  val callableFromAnotherFile: Boolean,
  /**
   * True when the function declares type parameters. A call that omits every defaulted argument
   * supplies nothing for the compiler to infer them from — `fun <T> Picker(items: List<T> =
   * emptyList())` cannot be called as `Picker()`.
   */
  val hasTypeParameters: Boolean,
  /**
   * Fully-qualified `@RequiresOptIn` marker annotations on the declaration
   * (`androidx.compose.material3.ExperimentalMaterial3Api`).
   *
   * A preview calling one of these compiles because its own file or function carries `@OptIn`. A
   * generated wrapper inherits nothing, so the same call fails there unless the wrapper opts in
   * too. Recorded rather than used to refuse, because opting in is mechanical and refusing would
   * drop much of Material 3 for a problem the caller can fix in one annotation.
   *
   * Empty also means "none we could resolve": a marker whose own class was outside the scan cannot
   * be identified, and is indistinguishable here from a declaration that needs no opt-in.
   */
  val requiredOptIns: List<String>,
  /**
   * The subset of [requiredOptIns] whose markers are declared with
   * `androidx.annotation.RequiresOptIn` rather than `kotlin.RequiresOptIn`.
   *
   * The two mechanisms are not interchangeable at the call site: `kotlin.OptIn` rejects an AndroidX
   * marker outright ("this class is not an opt-in requirement marker"), and the AndroidX annotation
   * takes its markers as `@androidx.annotation.OptIn(markerClass = [Foo::class])`. A generator that
   * knows only the marker names cannot tell which to write, so the mechanism is recorded here at
   * the one point that can see it — the annotation's own meta-annotations.
   */
  val androidxOptIns: List<String>,
)

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

  /**
   * Everything a printed call site needs from [method]'s Kotlin metadata, or **null when the
   * metadata could not be read**.
   *
   * The null is the point. [parametersOf] degrades an unreadable signature to an empty parameter
   * list, which is indistinguishable from a genuinely parameterless composable — fine for
   * scaffolding a call site a human completes, wrong for a generator that claims its output
   * compiles. A consumer that must not guess reads this instead and refuses on null.
   */
  fun signatureOf(classInfo: ClassInfo, method: MethodInfo): ComposableSignatureInfo? {
    return try {
      val metadata = readClassMetadata(classInfo) ?: return null
      val functions =
        when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
          is KotlinClassMetadata.Class -> parsed.kmClass.functions
          is KotlinClassMetadata.FileFacade -> parsed.kmPackage.functions
          is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage.functions
          else -> return null
        }
      val fn = matchFunction(functions, method) ?: return null
      ComposableSignatureInfo(
        // The name as the author wrote it. Metadata carries it directly, which is the only way to
        // get it right: the JVM name may be value-class-mangled (`Text-Nvy7gAk`) *or* a legally
        // escaped declaration whose own name contains a hyphen (``fun `filled-button`()``), and no
        // amount of string surgery on the JVM name distinguishes those two.
        name = fn.name,
        parameters = fn.valueParameters.map { it.toTargetParameter() },
        callableFromAnotherFile =
          fn.visibility == Visibility.PUBLIC || fn.visibility == Visibility.INTERNAL,
        hasTypeParameters = fn.typeParameters.isNotEmpty(),
        requiredOptIns = requiredOptInsOf(method, OPT_IN_MARKER_ANNOTATIONS),
        androidxOptIns = requiredOptInsOf(method, setOf("androidx.annotation.RequiresOptIn")),
        receiver =
          fn.receiverParameterType?.let { receiver ->
            (receiver.classifier as? KmClassifier.Class)?.name?.replace('/', '.')?.replace('$', '.')
          },
      )
    } catch (_: Throwable) {
      null
    }
  }

  /**
   * The editable knobs [method] declares as its own value parameters — the secondary override
   * format (see [PreviewKnob]).
   *
   * Returns empty unless **every** value parameter declares a default, which is the shape
   * `PreviewDiscovery.allParametersHaveDefaults` already requires before admitting a parameterised
   * preview at all: a parameter with no default cannot be left to the `$default` mask, so a
   * partially-defaulted function is not a knob carrier, it is an unrenderable preview.
   *
   * Within that, only parameters whose type the harness can build from a seed string become knobs.
   * A `modifier: Modifier = Modifier` on a production composable annotated `@Preview` in place is
   * deliberately *not* one — it is defaulted and renderable, but there is no seed value to give it,
   * and publishing it as editable would put an uneditable control on every such preview.
   *
   * [PreviewKnob.index] is the parameter's position in the **full** value-parameter list, not among
   * the knobs, because that is the index the renderer needs to place the argument.
   */
  fun knobsOf(classInfo: ClassInfo, method: MethodInfo): List<PreviewKnob> {
    val parameters =
      try {
        val metadata = readClassMetadata(classInfo) ?: return emptyList()
        val functions =
          when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
            is KotlinClassMetadata.Class -> parsed.kmClass.functions
            is KotlinClassMetadata.FileFacade -> parsed.kmPackage.functions
            is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage.functions
            else -> return emptyList()
          }
        matchFunction(functions, method)?.valueParameters ?: return emptyList()
      } catch (_: Throwable) {
        return emptyList()
      }
    if (parameters.isEmpty()) return emptyList()
    if (!parameters.all { it.declaresDefaultValue }) return emptyList()
    return parameters.mapIndexedNotNull { index, parameter ->
      knobType(parameter.type)?.let { PreviewKnob(parameter.name, index, it) }
    }
  }

  /**
   * The knob kind for [type], or null when the harness cannot construct a value for it.
   *
   * Matched on the metadata classifier's fully-qualified name rather than the rendered short name,
   * so a project's own `Boolean` class cannot masquerade as `kotlin.Boolean`. A nullable parameter
   * is excluded: the renderer signals "use the author default" by passing `null` for a position, so
   * a knob that can legitimately *be* null has no way to say "seed me null" and would silently
   * resolve to its default instead.
   */
  private fun knobType(type: KmType): PreviewKnobType? {
    if (type.isNullable) return null
    val name = (type.classifier as? KmClassifier.Class)?.name ?: return null
    return when (name) {
      "kotlin/String" -> PreviewKnobType.STRING
      "kotlin/Boolean" -> PreviewKnobType.BOOLEAN
      "kotlin/Int" -> PreviewKnobType.INT
      "kotlin/Long" -> PreviewKnobType.LONG
      "kotlin/Float" -> PreviewKnobType.FLOAT
      "kotlin/Double" -> PreviewKnobType.DOUBLE
      else -> null
    }
  }

  /**
   * The `@RequiresOptIn`-marked annotations a caller of [method] has to opt into.
   *
   * An opt-in marker is an annotation whose own class carries `@RequiresOptIn`, so this resolves
   * one level up rather than pattern-matching names like `Experimental…` — a convention plenty of
   * annotations follow without gating anything. An annotation class outside the scan resolves to
   * null and is skipped: unrecognised, not assumed.
   *
   * **`directOnly()` at both levels is what makes this correct, and its absence is what made the
   * first version wrong.** ClassGraph's `annotationInfo` is the transitive closure of
   * meta-annotations, not the annotations written on the element: `Card` carries `@Composable`,
   * `@ComposableInferredTarget` and `@FunctionKeyMeta`, and the closure of those three drags in
   * `InternalComposeApi`, `ComposeCompilerApi` and `kotlin.RequiresOptIn` itself. Reading the
   * closure therefore reported `@OptIn(InternalComposeApi::class)` for placing a `Card` — telling
   * consumers to opt into Compose's internals to draw a container.
   *
   * Filtering those names out was the first fix and the wrong one: it also silenced a component an
   * author had *deliberately* marked `@InternalComposeApi`, whose callers really must opt in.
   * Direct annotations answer the actual Kotlin rule — this element, this marker — so
   * `ComposableInferredTarget` drops out because it is not itself `@RequiresOptIn`, while an
   * author's `@ExperimentalMaterial3Api` or `@InternalComposeApi` survives.
   */
  private fun requiredOptInsOf(method: MethodInfo, mechanisms: Set<String>): List<String> =
    method.annotationInfo
      ?.directOnly()
      .orEmpty()
      .filter { annotation ->
        annotation.classInfo?.annotationInfo?.directOnly()?.any { it.name in mechanisms } == true
      }
      .map { it.name }
      .distinct()
      .sorted()

  private val OPT_IN_MARKER_ANNOTATIONS =
    setOf("kotlin.RequiresOptIn", "androidx.annotation.RequiresOptIn")

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

  private fun KmValueParameter.toTargetParameter(): TargetParameter {
    val slot = isComposableFunctionType(type)
    return TargetParameter(
      name = name,
      type = renderType(type),
      typeFqn =
        (type.classifier as? KmClassifier.Class)?.name?.replace('/', '.')?.replace('$', '.'),
      hasDefault = declaresDefaultValue,
      composableSlot = slot,
      composableSlotReceiver = if (slot) receiverFqnOf(type) else null,
      nullable = type.isNullable,
    )
  }

  /**
   * The fully-qualified receiver of an extension-function type, or null when it has none.
   *
   * Kotlin records the receiver as the first function type argument and marks the type with
   * `kotlin.ExtensionFunctionType` — the same pair [renderFunctionType] reads to print `RowScope.()
   * -> Unit`. This keeps the *qualified* classifier rather than the simple name that rendering
   * deliberately reduces to, because a consumer generating an import or deciding which scoped
   * modifier APIs are legal cannot use `RowScope` on its own.
   */
  private fun receiverFqnOf(type: KmType): String? {
    if (type.annotations.none { it.className == "kotlin/ExtensionFunctionType" }) return null
    val receiver = type.arguments.firstOrNull()?.type ?: return null
    val classifier = (receiver.classifier as? KmClassifier.Class)?.name ?: return null
    return classifier.replace('/', '.').replace('$', '.')
  }

  /**
   * A short, readable type: the classifier's simple name, a trailing `?` when nullable, and a
   * best-effort `<…>` of its type arguments (so `List<String>` reads as such). A function type
   * renders as `(…) -> …`. This is a scaffolding hint, not a resolvable reference — the developer
   * or agent completing the Code Connect mapping supplies the real value.
   */
  private fun renderType(type: KmType): String {
    val isFunction = (type.classifier as? KmClassifier.Class)?.name?.let { isFunctionClassName(it) }
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
    // A nullable function type has to be parenthesised or the `?` reads as part of the return type:
    // material3's `onCheckedChange: ((Boolean) -> Unit)?` rendered as `(Boolean) -> Unit?`, which
    // says the callback returns `Unit?` and is nullable nowhere — the opposite of the truth, handed
    // to every consumer of this string.
    //
    // This corrects the rendering only. Nullability that a *generator* acts on comes from
    // `TargetParameter.nullable`, because even parenthesised the spelling stays ambiguous in the
    // other direction: a non-null `(Int) -> String?` ends in `?` too.
    if (type.isNullable && isFunction == true) return "($base$args)?"
    return base + args + if (type.isNullable) "?" else ""
  }

  private fun renderProjection(projection: KmTypeProjection): String {
    val t = projection.type ?: return "*"
    return renderType(t)
  }

  /**
   * `(A, B) -> R`, or `Receiver.(A) -> R` for an extension-function type, using the metadata type
   * arguments (last = return). Kotlin records the receiver as the first function argument and marks
   * the type with `kotlin.ExtensionFunctionType`; without reading that marker a slot such as
   * `RowScope.() -> Unit` misleadingly appears as an ordinary `(RowScope) -> Unit` callback.
   */
  private fun renderFunctionType(
    type: KmType,
    @Suppress("UNUSED_PARAMETER") simple: String,
  ): String {
    val args = type.arguments
    if (args.isEmpty()) return "() -> Unit"
    val input = args.dropLast(1)
    val ret = args.last().type?.let { renderType(it) } ?: "Unit"
    val extension =
      type.annotations.any { it.className == "kotlin/ExtensionFunctionType" } && input.isNotEmpty()
    if (extension) {
      val receiver = renderProjection(input.first())
      val params = input.drop(1).joinToString(", ") { renderProjection(it) }
      return "$receiver.($params) -> $ret"
    }
    return "(${input.joinToString(", ") { renderProjection(it) }}) -> $ret"
  }

  private fun isFunctionClassName(name: String): Boolean = name.startsWith("kotlin/Function")

  /**
   * A function-typed parameter annotated `@Composable` — a content slot. Kotlin metadata retains
   * the type-use annotation, so use it instead of treating every callback (`onClick`,
   * `onValueChange` and friends) as child content. A consumer can then label and render actual
   * slots distinctly.
   */
  private fun isComposableFunctionType(type: KmType): Boolean =
    (type.classifier as? KmClassifier.Class)?.name?.let { isFunctionClassName(it) } == true &&
      type.annotations.any { it.className == "androidx/compose/runtime/Composable" }

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
