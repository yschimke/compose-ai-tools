package ee.schimke.composeai.discovery

import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isInner
import kotlin.metadata.isNullable
import kotlin.metadata.isValue
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Recovers a composable's real Kotlin value parameters from `@kotlin.Metadata`, so consumers can
 * render true call sites (e.g. for Figma Code Connect). The JVM signature drops names and adds
 * Compose's synthetic `Composer` / `changed` / default-mask parameters; metadata has the source
 * signature.
 *
 * Best-effort: any failure yields an empty list (a parameterless call). Read leniently so
 * newer-Kotlin metadata still parses.
 */
/**
 * A composable's source signature: value parameters and, for an extension, its receiver — without
 * which a printed call (e.g. `AnimatedVisibility` on `ColumnScope`) doesn't resolve.
 */
internal data class ComposableSignatureInfo(
  /** The source-level function name, straight from metadata — never the mangled JVM name. */
  val name: String,
  val parameters: List<TargetParameter>,
  val receiver: String?,
  /** `public` or `internal`, i.e. callable from a generated file. */
  val callableFromAnotherFile: Boolean,
  /**
   * Declares type parameters, which a call omitting all defaulted arguments can't infer (`fun <T>
   * Picker(items: List<T> = emptyList())`).
   */
  val hasTypeParameters: Boolean,
  /**
   * Declares a context receiver / parameter, which a generated wrapper can't supply and the
   * parameter list doesn't show.
   */
  val hasContextReceivers: Boolean,
  /**
   * Fully-qualified `@RequiresOptIn` markers on the declaration (e.g. `ExperimentalMaterial3Api`).
   * A generated wrapper must opt in itself; recorded rather than refused, since opting in is
   * mechanical. Empty may also mean a marker outside the scan couldn't be identified.
   */
  val requiredOptIns: List<String>,
  /**
   * The subset of [requiredOptIns] declared with AndroidX `RequiresOptIn`. Those need
   * `@androidx.annotation.OptIn(markerClass = …)`, which `kotlin.OptIn` can't substitute for; only
   * the marker's meta-annotations reveal which.
   */
  val androidxOptIns: List<String>,
)

internal object ComposableSignature {

  /** Read file-facade kind from bytes; annotation scanners may substitute Metadata defaults. */
  fun isTopLevel(classInfo: ClassInfo): Boolean = readClassMetadata(classInfo)?.kind in setOf(2, 5)

  /**
   * Value parameters of [method], or empty when unrecoverable. Matched by JVM name + descriptor so
   * overloads don't collide.
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
   * Everything a printed call site needs, or **null when metadata is unreadable**. Unlike
   * [parametersOf], which degrades to an empty list indistinguishable from a parameterless
   * composable, this lets a generator that claims its output compiles refuse.
   */
  fun signatureOf(
    classInfo: ClassInfo,
    method: MethodInfo,
    scanResult: ScanResult? = null,
  ): ComposableSignatureInfo? {
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
        // The source name from metadata: the JVM name may be value-class-mangled or a backticked
        // name containing a hyphen, and string surgery can't tell them apart.
        name = fn.name,
        // Constructibility needs the classpath; without a scan the flag stays false.
        parameters =
          fn.valueParameters.map { it.toTargetParameter().withConstructibility(scanResult) },
        callableFromAnotherFile =
          fn.visibility == Visibility.PUBLIC || fn.visibility == Visibility.INTERNAL,
        hasTypeParameters = fn.typeParameters.isNotEmpty(),
        hasContextReceivers = hasContextRequirement(fn),
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
   * Editable knobs declared as [method]'s own value parameters (see [PreviewKnob]).
   *
   * Empty unless **every** parameter has a default, matching
   * `PreviewDiscovery.allParametersHaveDefaults`. Only types the harness can build from a seed
   * string become knobs; `modifier: Modifier = Modifier` is not one. [PreviewKnob.index] is the
   * position in the full parameter list, which the renderer needs.
   */
  fun knobsOf(
    classInfo: ClassInfo,
    method: MethodInfo,
    scanResult: ScanResult? = null,
  ): List<PreviewKnob> {
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
    // Read the body only when there's a knob: it's the one place discovery reads a method body.
    val hasKnob = parameters.any { knobType(it.type, scanResult) != null }
    val defaults =
      if (hasKnob) PreviewKnobDefaults.readFrom(classInfo, method, parameters.size) else emptyMap()
    return parameters.mapIndexedNotNull { index, parameter ->
      knobType(parameter.type, scanResult)?.let { type ->
        val constants =
          if (type == PreviewKnobType.ENUM) enumConstantsOf(parameter.type, scanResult)
          else emptyList()
        // An enum without resolvable options (unreadable, or ambiguous seed texts) isn't a knob: an
        // empty picker is worse than nothing.
        if (type == PreviewKnobType.ENUM && constants.isEmpty()) return@mapIndexedNotNull null
        PreviewKnob(
          name = parameter.name,
          index = index,
          type = type,
          // The default is read as the constant's name; translate it to its seed text so it's in
          // the options' vocabulary and "reset" sends a valid value.
          default =
            defaults[index]?.let { read ->
              if (type == PreviewKnobType.ENUM) {
                constants.firstOrNull { it.first == read }?.second ?: read
              } else read
            },
          options = constants.map { it.second },
        )
      }
    }
  }

  /**
   * Enum constant names in declaration order, or empty. Read via
   * [PreviewKnobDefaults.enumConstantsOf] because the scan doesn't enable field info.
   */
  private fun enumConstantsOf(
    type: KmType,
    scanResult: ScanResult?,
  ): List<Pair<String, String>> {
    val fqn =
      (type.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: return emptyList()
    val info = scanResult?.getClassInfo(fqn) ?: return emptyList()
    return PreviewKnobDefaults.enumConstantsOf(info)
  }

  /**
   * The knob kind for [type], or null when the harness can't construct it. Matched on the qualified
   * classifier so a project `Boolean` can't masquerade. Nullable parameters are excluded: `null`
   * already means "use the author default".
   */
  private fun knobType(type: KmType, scanResult: ScanResult? = null): PreviewKnobType? {
    if (type.isNullable) return null
    val name = (type.classifier as? KmClassifier.Class)?.name ?: return null
    return when (name) {
      "kotlin/String" -> PreviewKnobType.STRING
      "kotlin/Boolean" -> PreviewKnobType.BOOLEAN
      "kotlin/Int" -> PreviewKnobType.INT
      "kotlin/Long" -> PreviewKnobType.LONG
      "kotlin/Float" -> PreviewKnobType.FLOAT
      "kotlin/Double" -> PreviewKnobType.DOUBLE
      // Enum knobs need the enum's own class, so without a scan this degrades to "not a knob".
      else ->
        PreviewKnobType.ENUM.takeIf {
          scanResult?.getClassInfo(name.replace('/', '.'))?.isEnum == true
        }
    }
  }

  /**
   * `@RequiresOptIn`-marked annotations a caller of [method] must opt into, resolved via each
   * annotation's own class (unresolvable ones are skipped) rather than name patterns.
   *
   * **`directOnly()` at both levels is essential.** ClassGraph's `annotationInfo` is the transitive
   * meta-annotation closure, which for any composable drags in `InternalComposeApi` and friends via
   * `@Composable`'s own annotations. Direct annotations follow the actual Kotlin rule, so an
   * author's deliberate `@InternalComposeApi` still counts.
   */
  private fun requiredOptInsOf(method: MethodInfo, mechanisms: Set<String>): List<String> =
    requiredOptInsOf(method.annotationInfo?.directOnly().orEmpty(), mechanisms)

  /** The same for a class, used by [isNoArgConstructible] to refuse gated types. */
  private fun requiredOptInsOf(classInfo: ClassInfo, mechanisms: Set<String>): List<String> =
    requiredOptInsOf(classInfo.annotationInfo?.directOnly().orEmpty(), mechanisms)

  private fun requiredOptInsOf(
    annotations: List<AnnotationInfo>,
    mechanisms: Set<String>,
  ): List<String> =
    annotations
      .filter { annotation ->
        annotation.classInfo?.annotationInfo?.directOnly()?.any { it.name in mechanisms } == true
      }
      .map { sourceNameOf(it) }
      .distinct()
      .sorted()

  /**
   * Whether [fn] needs a context in scope.
   *
   * **Partial:** reads `contextReceiverTypes`, which older toolchains use. Kotlin 2.2+ records
   * context parameters instead, behind an accessor needing API version 2.2, which this module
   * (targeting 2.0) can't reference. So such components are still admitted and produce
   * non-compiling calls; closing that needs a build-wide API version bump.
   */
  @OptIn(kotlin.metadata.ExperimentalContextReceivers::class)
  @Suppress("DEPRECATION", "DEPRECATION_ERROR")
  private fun hasContextRequirement(fn: KmFunction): Boolean = fn.contextReceiverTypes.isNotEmpty()

  /**
   * An annotation class's source name. ClassGraph gives the binary name, where `$` separates
   * nesting but may also appear in a backticked name, so rebuild it from the nesting chain.
   */
  private fun sourceNameOf(annotation: AnnotationInfo): String {
    val info = annotation.classInfo ?: return annotation.name
    val outers = info.outerClasses?.reversed().orEmpty()
    if (outers.isEmpty()) return annotation.name
    val packageName = annotation.name.substringBeforeLast('.', "")
    val nesting = (outers.map { it.simpleName } + info.simpleName).joinToString(".")
    return if (packageName.isEmpty()) nesting else "$packageName.$nesting"
  }

  private val OPT_IN_MARKER_ANNOTATIONS =
    setOf("kotlin.RequiresOptIn", "androidx.annotation.RequiresOptIn")

  private const val COMPOSABLE_ANNOTATION = "androidx.compose.runtime.Composable"

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
    // A non-`@Composable` receiver lambda is a scope DSL (`LazyListScope.() -> Unit`), not a slot.
    // Plain callbacks have no receiver.
    val dsl = if (slot) null else receiverFqnOf(type)
    return TargetParameter.Builder(name = name, type = renderType(type))
      .also { b ->
        b.typeFqn =
          (type.classifier as? KmClassifier.Class)?.name?.replace('/', '.')?.replace('$', '.')
        b.hasDefault = declaresDefaultValue
        b.composableSlot = slot
        b.composableSlotReceiver = if (slot) receiverFqnOf(type) else null
        b.nullable = type.isNullable
        b.scopeDslReceiver = dsl
        b.lambdaReturnTypeFqn = lambdaReturnFqnOf(type)
      }
      .build()
  }

  /**
   * [TargetParameter.noArgConstructible] (#5067), only computed for required class-typed
   * parameters.
   */
  private fun TargetParameter.withConstructibility(scanResult: ScanResult?): TargetParameter {
    if (scanResult == null || hasDefault || nullable || composableSlot) return this
    val fqn = typeFqn ?: return this
    val constructible = isNoArgConstructible(scanResult, fqn)
    val factory = noArgFactoryFor(scanResult, fqn)
    if (!constructible && factory == null) return this
    return newBuilder()
      .also { b ->
        b.noArgConstructible = constructible
        b.noArgFactory = factory
      }
      .build()
  }

  /**
   * [TargetParameter.noArgFactory]: the `remember<SimpleName>` a call site should prefer over the
   * constructor. Compose's convention is `@Composable fun rememberT(…)` in the type's own package
   * with all parameters defaulted; searching only that package makes it a lookup, not a guess. File
   * facades are named after source files, so the package's classes are walked.
   *
   * Refused (each would fail as `rememberT()`): wrong shape (non-public, generic, extension,
   * context-requiring); a required parameter; a different return type; not `@Composable`; opt-in
   * gated. A gated type doesn't disqualify an ungated factory, since the type name isn't emitted.
   */
  internal fun noArgFactoryFor(scanResult: ScanResult, fqn: String): String? {
    return try {
      val pkg = fqn.substringBeforeLast('.', missingDelimiterValue = "")
      val simpleName = fqn.substringAfterLast('.')
      if (pkg.isEmpty() || simpleName.isEmpty()) return null
      val factoryName = "remember$simpleName"
      val declaring =
        scanResult.getPackageInfo(pkg)?.classInfo?.firstOrNull { info ->
          declaresNoArgComposableFactory(info, factoryName, fqn)
        }
      if (declaring == null) null else "$pkg.$factoryName"
    } catch (_: Throwable) {
      // Unreadable means no factory.
      null
    }
  }

  /**
   * `@kotlin.Deprecated` at any level (`HIDDEN` is also synthetic) or `@java.lang.Deprecated`.
   * Every callable this reader offers consults it.
   */
  fun isDeprecated(method: MethodInfo): Boolean =
    method.isSynthetic || DEPRECATED_ANNOTATIONS.any { method.hasAnnotation(it) }

  /** [isDeprecated] for a class: a deprecated type has no constructor worth printing. */
  internal fun isDeprecated(info: ClassInfo): Boolean = DEPRECATED_ANNOTATIONS.any {
    info.hasAnnotation(it)
  }

  private val DEPRECATED_ANNOTATIONS = listOf("kotlin.Deprecated", "java.lang.Deprecated")

  /** Whether [info] is a file facade declaring [factoryName] in the shape described above. */
  private fun declaresNoArgComposableFactory(
    info: ClassInfo,
    factoryName: String,
    returnFqn: String,
  ): Boolean {
    val metadata = readClassMetadata(info) ?: return false
    val functions =
      when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
        is KotlinClassMetadata.FileFacade -> parsed.kmPackage.functions
        is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage.functions
        else -> return false
      }
    val declared =
      functions.firstOrNull { fn ->
        fn.name == factoryName &&
          fn.visibility == Visibility.PUBLIC &&
          fn.typeParameters.isEmpty() &&
          fn.receiverParameterType == null &&
          !hasContextRequirement(fn) &&
          fn.valueParameters.all { it.declaresDefaultValue } &&
          (fn.returnType.classifier as? KmClassifier.Class)?.name?.replace('/', '.') == returnFqn
      } ?: return false
    // `@Composable` is only on the JVM method, so look it up by the JVM name metadata carries (e.g.
    // `rememberTextFieldState-Le-punE`, mangled by a value-class parameter). Name only: the
    // descriptor has Compose's extra parameters.
    val jvmName = declared.signature?.name ?: factoryName
    // Never offer a deprecated factory.
    return info.getMethodInfo(jvmName).any { method ->
      method.hasAnnotation(COMPOSABLE_ANNOTATION) &&
        !isDeprecated(method) &&
        requiredOptInsOf(method, OPT_IN_MARKER_ANNOTATIONS).isEmpty()
    }
  }

  /**
   * Whether `Type()` compiles for [fqn]. Refused when:
   * - **not on the classpath**;
   * - **not a plain class** — object, interface, annotation, enum, or abstract;
   * - **not public**;
   * - **an inner class** — needs an outer instance;
   * - **generic** — `T` uninferable;
   * - **a value class** — mangled constructor;
   * - **opt-in gated** — the wrapper wouldn't carry the type's marker (a follow-up).
   *
   * Otherwise it needs a public constructor whose parameters all declare defaults (or none), read
   * from metadata since Kotlin emits only the `DefaultConstructorMarker` bridge. No recursion.
   */
  internal fun isNoArgConstructible(scanResult: ScanResult, fqn: String): Boolean {
    return try {
      val info = scanResult.getClassInfo(fqn) ?: return false
      if (!info.isPublic || info.isAbstract || info.isInterface || info.isEnum) return false
      if (info.isAnnotation) return false
      if (requiredOptInsOf(info, OPT_IN_MARKER_ANNOTATIONS).isNotEmpty()) return false
      // Deprecated types or constructors print no `T()`. The synthetic marker bridge isn't
      // deprecated.
      if (isDeprecated(info)) return false
      val constructors = info.declaredConstructorInfo.filter { it.isPublic }
      if (
        constructors.isNotEmpty() &&
          constructors.all { c -> DEPRECATED_ANNOTATIONS.any { c.hasAnnotation(it) } }
      )
        return false
      val metadata = readClassMetadata(info) ?: return false
      val kmClass =
        (KotlinClassMetadata.readLenient(metadata) as? KotlinClassMetadata.Class)?.kmClass
          ?: return false
      isNoArgConstructible(kmClass)
    } catch (_: Throwable) {
      // Unreadable means "no".
      false
    }
  }

  /** The metadata half of [isNoArgConstructible], split out so it can be tested without a scan. */
  internal fun isNoArgConstructible(kmClass: KmClass): Boolean {
    if (kmClass.kind != ClassKind.CLASS) return false
    if (kmClass.isValue || kmClass.isInner) return false
    if (kmClass.typeParameters.isNotEmpty()) return false
    if (kmClass.visibility != Visibility.PUBLIC) return false
    return kmClass.constructors.any { constructor ->
      constructor.visibility == Visibility.PUBLIC &&
        constructor.valueParameters.all { it.declaresDefaultValue }
    }
  }

  /**
   * The qualified receiver of an extension-function type, or null: the first type argument, marked
   * `kotlin.ExtensionFunctionType`. Kept qualified (unlike rendering) for imports and scoped-API
   * decisions.
   */
  /**
   * The classifier a function type returns (its last type argument), or null when not a function
   * type. See [TargetParameter.lambdaReturnTypeFqn].
   */
  private fun lambdaReturnFqnOf(type: KmType): String? {
    val classifier = (type.classifier as? KmClassifier.Class)?.name ?: return null
    if (!isFunctionClassName(classifier)) return null
    val returned = type.arguments.lastOrNull()?.type ?: return null
    val name = (returned.classifier as? KmClassifier.Class)?.name ?: return null
    return name.replace('/', '.').replace('$', '.')
  }

  private fun receiverFqnOf(type: KmType): String? {
    if (type.annotations.none { it.className == "kotlin/ExtensionFunctionType" }) return null
    val receiver = type.arguments.firstOrNull()?.type ?: return null
    val classifier = (receiver.classifier as? KmClassifier.Class)?.name ?: return null
    return classifier.replace('/', '.').replace('$', '.')
  }

  /**
   * A readable type hint: simple name, `?` when nullable, best-effort type arguments, `(…) -> …`
   * for functions. Not a resolvable reference.
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
    // Parenthesise nullable function types, or `((Boolean) -> Unit)?` reads as `(Boolean) ->
    // Unit?`. Generators use `TargetParameter.nullable`, since the spelling stays ambiguous for
    // `(Int) -> String?`.
    if (type.isNullable && isFunction == true) return "($base$args)?"
    return base + args + if (type.isNullable) "?" else ""
  }

  private fun renderProjection(projection: KmTypeProjection): String {
    val t = projection.type ?: return "*"
    return renderType(t)
  }

  /**
   * `(A, B) -> R`, or `Receiver.(A) -> R` when marked `kotlin.ExtensionFunctionType`; otherwise
   * `RowScope.() -> Unit` would look like a `(RowScope) -> Unit` callback.
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
   * A function-typed parameter annotated `@Composable` is a content slot (metadata keeps the
   * type-use annotation), as opposed to callbacks like `onClick`.
   */
  private fun isComposableFunctionType(type: KmType): Boolean =
    (type.classifier as? KmClassifier.Class)?.name?.let { isFunctionClassName(it) } == true &&
      type.annotations.any { it.className == "androidx/compose/runtime/Composable" }

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
        // ASM passes a primitive annotation array (`mv: IntArray`) whole to `visit`, not through
        // `visitArray`.
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
