package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.Resource
import io.github.classgraph.ScanResult
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Infers which production `@Composable` a `@Preview` function is presumed to render. The preview's
 * bytecode is walked for `INVOKE*` instructions; calls into project-local `@Composable` functions
 * are kept (theming / layout primitives are filtered out by FQN), then scored against signals like
 * "preview is in a debug/screenshotTest source set" or "preview's name matches the call's simple
 * name once the `Preview` suffix is stripped".
 *
 * v1 emits at most one [PreviewTarget] per preview. The output type is a list because the schema is
 * forward-compatible with later multi-target inference (e.g. `Row { Foo(); Bar() }` returning
 * both), but the current scoring pass keeps only the top-scored candidate.
 *
 * Project-local theme/preview wrappers are filtered by source/name, and compiler-generated lambda
 * methods reachable from the preview are traversed so `Theme { Component() }` can still nominate
 * `Component`.
 */
object PreviewTargetInference {

  // FQN prefixes whose @Composable functions are theming / layout / runtime scaffolding
  // rather than the production UI under preview. Anything matching one of these is dropped
  // from the candidate set before scoring. Prefix-match keeps the list short and lets us
  // reach into deeper packages (e.g. `androidx.compose.foundation.layout.Box`) without
  // enumerating every leaf.
  private val WRAPPER_FQN_PREFIXES =
    listOf(
      "androidx.compose.material.",
      "androidx.compose.material3.",
      "androidx.compose.foundation.",
      "androidx.compose.runtime.",
      "androidx.compose.ui.",
      "androidx.compose.animation.",
      "androidx.wear.compose.material.",
      "androidx.wear.compose.material3.",
      "androidx.wear.compose.foundation.",
      "org.jetbrains.compose.",
    )

  // The subset of [WRAPPER_FQN_PREFIXES] that names design-system **components** rather than
  // layout, runtime or drawing primitives.
  //
  // Both lists are right about the same packages for different questions. "Which of MY composables
  // does this preview render?" wants `material3.Button` dropped — it is not the project's UI.
  // "Which
  // component does this sticker demonstrate?" wants exactly that call and nothing else, because a
  // catalog sticker's whole subject is the library component it wraps. m3-catalog's 59 entries
  // stand
  // for 148 distinct `androidx.compose.material3.*` symbols, none of which the project-local pass
  // can name, so a catalog gets no target at all today.
  //
  // Deliberately NOT the whole of `WRAPPER_FQN_PREFIXES`: `foundation.layout.Column`,
  // `runtime.remember` and `ui.Modifier` stay scaffolding under either question, and admitting them
  // would bury the one call a reader cares about under the frame that positions it.
  private val COMPONENT_LIBRARY_FQN_PREFIXES =
    listOf(
      "androidx.compose.material3.",
      "androidx.compose.material.",
      "androidx.wear.compose.material3.",
      "androidx.wear.compose.material.",
    )

  // Theme entry points inside the component libraries. They pass every other test here — real
  // `@Composable`s returning `Unit`, in `material3` — and they are the frame a sticker is drawn in,
  // not its subject. Nine of `:samples:cmp`'s twelve component-bearing previews reported
  // `MaterialTheme` before this list existed.
  //
  // A denylist rather than a shape rule, because the obvious shape rule does not separate them:
  // `MaterialTheme(colorScheme, shapes, typography, content)` and
  // `Card(modifier, shape, colors, elevation, border, content)` are both "defaulted config plus one
  // `@Composable` content lambda". The set is small, stable and named in every catalog's own
  // scaffolding rules already (`compose-usage.json` rewrites `Sticker` to `MaterialTheme` for
  // exactly this reason), so naming it here is cheaper and clearer than a heuristic that would
  // eventually drop a real component.
  private val THEME_ENTRY_POINTS =
    setOf(
      "androidx.compose.material3.MaterialThemeKt.MaterialTheme",
      "androidx.compose.material.MaterialThemeKt.MaterialTheme",
      "androidx.wear.compose.material3.MaterialThemeKt.MaterialTheme",
      "androidx.wear.compose.material.MaterialThemeKt.MaterialTheme",
    )

  // Stdlib / JVM / Kotlin-runtime owners. Filtered explicitly so we never attempt to look
  // them up as project-local @Composable methods.
  private val STDLIB_FQN_PREFIXES = listOf("java.", "javax.", "kotlin.", "kotlinx.", "sun.", "jdk.")

  // Source-set / variant names that signal the preview file is non-shipping. These are the
  // standard AGP / Kotlin source set names; the check is conservative — anything not in the
  // shipping set is treated as non-shipping for scoring purposes only.
  private val NON_SHIPPING_SOURCE_SETS =
    setOf(
      "debug",
      "test",
      "androidTest",
      "screenshotTest",
      "debugAndroidTest",
      "debugUnitTest",
      "release", // not shipping in the sense relevant here, but production builds; keep neutral
    )

  // Filename heuristic for "this file is dedicated to previews" — case-insensitive.
  private val DEDICATED_FILE_REGEX = Regex(""".*Previews?\.kt$""", RegexOption.IGNORE_CASE)

  private const val PREVIEW_FQN = "androidx.compose.ui.tooling.preview.Preview"
  private const val DESKTOP_PREVIEW_FQN = "androidx.compose.desktop.ui.tooling.preview.Preview"
  private const val TILE_PREVIEW_FQN = "androidx.wear.tiles.tooling.preview.Preview"
  // Compose Multiplatform's own @Preview — see PreviewDiscovery.CMP_PREVIEW_FQN. A CMP project's
  // previews would otherwise not count as previews here, so a composable called only by them would
  // look like an ordinary composable and score as a render target.
  private const val CMP_PREVIEW_FQN = PreviewDiscovery.CMP_PREVIEW_FQN
  private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"

  /** Single bytecode call site, as captured from the preview method body. */
  internal data class Invocation(
    val ownerFqn: String,
    val methodName: String,
    val descriptor: String,
  )

  private data class Candidate(
    val classFqn: String,
    val methodName: String,
    val sourceFile: String?,
    val score: Int,
    val signals: List<TargetSignal>,
  )

  /**
   * @param previewClassInfo the class containing the `@Preview` method.
   * @param previewMethod the `@Preview`-annotated method.
   * @param scanResult the active ClassGraph result; used to look up call targets.
   * @param projectClassFqns FQNs of every class compiled from the project's own source dirs (i.e.
   *   not pulled from a dependency JAR). The "is this call project-local?" filter keys off this
   *   set.
   * @param previewSourceFile module-relative source path of the preview file, when known.
   * @param resolveSourceFile maps a target class FQN to its module-relative source path. Returns
   *   `null` when the source file isn't wired into the discovery task's `sourceFiles` input.
   * @param variantName the AGP/Kotlin source set the preview was discovered under (used for the
   *   `NON_SHIPPING_SOURCE_SET` signal).
   * @param hasPreviewParameter `true` when the preview function has a `@PreviewParameter`-annotated
   *   parameter; enables the `PARAMETER_FORWARDED` signal when the candidate consumes a value of
   *   the right shape.
   */
  /**
   * The **design-system components** [previewMethod] renders — the library composables a catalog
   * sticker exists to demonstrate, as opposed to [infer]'s "which of the project's own composables
   * does this preview render?".
   *
   * The two answer different questions and neither subsumes the other, so this is a separate list
   * rather than more entries in `targets`: a consumer correlating a UI change to its preview wants
   * the project-local answer, while a consumer describing a component's API wants this one. Mixing
   * them would also silently change what `targets[0]` means for every existing reader.
   *
   * Resolution reuses [resolveCandidate], so a candidate must be a real `@Composable` on the scan's
   * classpath — which already spans dependency jars — and must not itself carry a `@Preview`.
   * Ordering is by call order, deduped, so a sticker wrapping one component yields exactly one
   * entry and `HIGH` confidence; several distinct component calls yield several at `MEDIUM`,
   * because nothing here can tell the subject from its neighbours.
   *
   * Parameters come from the same `@kotlin.Metadata` read as [infer]'s, so a library component
   * arrives with its real signature — which is the point: `Button(onClick, modifier, enabled,
   * shape, colors, elevation, border, contentPadding, interactionSource, content)` is written down
   * nowhere in a catalog that spells it `Sticker("button-filled")`.
   */
  fun inferComponents(
    previewClassInfo: ClassInfo,
    previewMethod: MethodInfo,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
  ): List<PreviewTarget> {
    val directCalls =
      try {
        extractCalls(previewClassInfo, previewMethod)
      } catch (_: Throwable) {
        return emptyList()
      }
    val calls =
      directCalls + extractComposeSingletonLambdaCalls(directCalls, scanResult, projectClassFqns)
    val candidates =
      calls
        .asSequence()
        .filter { call -> COMPONENT_LIBRARY_FQN_PREFIXES.any { call.ownerFqn.startsWith(it) } }
        .mapNotNull { resolveCandidate(it, scanResult) }
        .filter {
          isComponentLibraryTarget(
            ownerFqn = it.ownerFqn,
            methodName = sourceFunctionName(it.method.name),
            returnsUnit = it.method.typeDescriptor?.resultType?.toString() == "void",
          )
        }
        .distinctBy { it.ownerFqn to sourceFunctionName(it.method.name) }
        .toList()
    if (candidates.isEmpty()) return emptyList()
    val confidence = if (candidates.size == 1) TargetConfidence.HIGH else TargetConfidence.MEDIUM
    return candidates.map { candidate ->
      // Metadata is matched on the JVM name (`candidate.method`), which is the mangled one; only
      // the *reported* name is demangled. Reading the signature with the source name would find
      // nothing.
      val signature = ComposableSignature.signatureOf(candidate.classInfo, candidate.method)
      PreviewTarget(
        className = candidate.ownerFqn,
        functionName = sourceFunctionName(candidate.method.name),
        // A library symbol has no source file in this build; `sourceFile` stays null and
        // [PreviewTarget.origin] is what says so, rather than the null being read as "library".
        sourceFile = null,
        confidence = confidence,
        signals = listOf(TargetSignal.LIBRARY_COMPONENT),
        parameters = signature?.parameters.orEmpty(),
        receiver = signature?.receiver,
        signatureKnown = signature != null,
      )
    }
  }

  fun infer(
    previewClassInfo: ClassInfo,
    previewMethod: MethodInfo,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
    previewSourceFile: String?,
    resolveSourceFile: (String) -> String?,
    variantName: String,
    hasPreviewParameter: Boolean,
  ): List<PreviewTarget> {
    val directCalls =
      try {
        extractCalls(previewClassInfo, previewMethod)
      } catch (_: Throwable) {
        // Bytecode unavailable / unreadable — discovery should still succeed without target
        // inference. The preview is still emitted; consumers see `targets = []` and fall back
        // to whatever signal they had before.
        return emptyList()
      }
    val unwrappedCalls =
      extractComposeSingletonLambdaCalls(directCalls, scanResult, projectClassFqns)
    val calls = directCalls + unwrappedCalls
    val unwrappedTargets = unwrappedCalls.mapTo(mutableSetOf()) { it.ownerFqn to it.methodName }

    val previewFqn = previewClassInfo.name
    val previewMethodName = previewMethod.name

    val candidates =
      calls
        .asSequence()
        .filterNot { it.ownerFqn == previewFqn && it.methodName == previewMethodName }
        .filterNot { isStdlib(it.ownerFqn) }
        .filterNot { isWrapperFqn(it.ownerFqn) }
        .filter { it.ownerFqn in projectClassFqns }
        .mapNotNull { resolveCandidate(it, scanResult) }
        .filterNot { candidate ->
          // Demangled first: a project composable taking a `Dp` compiles to `MyCard-abc123`, which
          // is not a usable import and was therefore dropped outright — the same value-class
          // mangling that hid `Text` from the component path, and more common here because `Dp`
          // and `Color` parameters are ordinary in an app's own composables.
          val name = sourceFunctionName(candidate.method.name)
          !isValidKotlinImportIdentifier(name) ||
            isPreviewOnlyWrapper(name, resolveSourceFile(candidate.ownerFqn))
        }
        .distinctBy { it.ownerFqn to sourceFunctionName(it.method.name) }
        .toList()

    if (candidates.isEmpty()) return emptyList()

    val survivors = candidates.size
    // Keep each candidate paired with its score so the winner's resolved ClassInfo/MethodInfo is in
    // hand for signature extraction (params come from the target's Kotlin metadata, below).
    val scored = candidates.map { candidate ->
      candidate to
        score(
          callerOwner = candidate.ownerFqn,
          callerMethod = candidate.method,
          callerClassInfo = candidate.classInfo,
          previewClassFqn = previewFqn,
          previewMethodName = previewMethodName,
          previewSourceFile = previewSourceFile,
          variantName = variantName,
          hasPreviewParameter = hasPreviewParameter,
          callerMethodHasComposableParam =
            candidate.method.parameterInfo?.any { p ->
              // Heuristic for "this candidate consumes a value of the same shape as the preview's
              // @PreviewParameter" — a non-`@Composable () -> Unit` parameter on the candidate.
              // Cheap stand-in for proper data-flow analysis; good enough to flag the common
              // `@PreviewParameter color: Long → Foo(color)` pattern.
              p.annotationInfo?.none { it.name == COMPOSABLE_FQN } ?: true
            } == true,
          wrapperUnwrapped = candidate.ownerFqn to candidate.method.name in unwrappedTargets,
          totalSurvivors = survivors,
          resolveSourceFile = resolveSourceFile,
        )
    }

    val (bestCandidate, best) = scored.maxByOrNull { it.second.score } ?: return emptyList()
    if (best.score < MIN_EMIT_SCORE) return emptyList()

    val confidence =
      when {
        best.score >= HIGH_THRESHOLD -> TargetConfidence.HIGH
        best.score >= MEDIUM_THRESHOLD -> TargetConfidence.MEDIUM
        else -> TargetConfidence.LOW
      }
    val signature = ComposableSignature.signatureOf(bestCandidate.classInfo, bestCandidate.method)
    return listOf(
      PreviewTarget(
        className = best.classFqn,
        functionName = best.methodName,
        sourceFile = best.sourceFile,
        confidence = confidence,
        signals = best.signals,
        // The target's real Kotlin value parameters (names / types / defaults) for the call site a
        // consumer renders into Code Connect. Best-effort — empty when metadata can't be read,
        // which `signatureKnown` is what distinguishes from a parameterless composable.
        parameters = signature?.parameters.orEmpty(),
        receiver = signature?.receiver,
        signatureKnown = signature != null,
      )
    )
  }

  private const val MIN_EMIT_SCORE = 1
  private const val MEDIUM_THRESHOLD = 2
  private const val HIGH_THRESHOLD = 4

  private fun isStdlib(fqn: String): Boolean = STDLIB_FQN_PREFIXES.any { fqn.startsWith(it) }

  private fun isWrapperFqn(fqn: String): Boolean = WRAPPER_FQN_PREFIXES.any { fqn.startsWith(it) }

  /**
   * Reads [previewClassInfo]'s class file via ClassGraph and walks [previewMethod]'s body for
   * `INVOKE*` instructions. Method matching is by name + descriptor so overloads don't bleed into
   * each other; ClassGraph's `MethodInfo.typeDescriptorStr` is the JVM signature.
   */
  internal fun extractCalls(
    previewClassInfo: ClassInfo,
    previewMethod: MethodInfo,
  ): List<Invocation> {
    val resource = previewClassInfo.resource ?: return emptyList()
    val root = MethodKey(previewMethod.name, previewMethod.typeDescriptorStr)
    return extractCalls(
      resource = resource,
      ownerFqn = previewClassInfo.name,
      isRoot = { it == root },
      shouldFollow = { candidate -> candidate.name.startsWith(previewMethod.name + '$') },
    )
  }

  private fun extractCalls(
    resource: Resource,
    ownerFqn: String,
    isRoot: (MethodKey) -> Boolean,
    shouldFollow: (MethodKey) -> Boolean,
  ): List<Invocation> {
    val ownerInternal = ownerFqn.replace('.', '/')
    val bodies = mutableMapOf<MethodKey, MethodBody>()
    resource.open().use { stream ->
      ClassReader(stream)
        .accept(
          object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String?,
              exceptions: Array<out String>?,
            ): MethodVisitor? {
              val key = MethodKey(name, descriptor)
              val calls = mutableListOf<Invocation>()
              val nestedMethods = mutableSetOf<MethodKey>()
              bodies[key] = MethodBody(calls, nestedMethods)
              return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                  opcode: Int,
                  owner: String,
                  name: String,
                  descriptor: String,
                  isInterface: Boolean,
                ) {
                  calls += Invocation(owner.replace('/', '.'), name, descriptor)
                  if (owner == ownerInternal) {
                    nestedMethods += MethodKey(name, descriptor)
                  }
                }

                override fun visitInvokeDynamicInsn(
                  name: String,
                  descriptor: String,
                  bootstrapMethodHandle: Handle,
                  vararg bootstrapMethodArguments: Any,
                ) {
                  bootstrapMethodArguments
                    .filterIsInstance<Handle>()
                    .filter { it.owner == ownerInternal }
                    .forEach { nestedMethods += MethodKey(it.name, it.desc) }
                }
              }
            }
          },
          ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    val collected = mutableListOf<Invocation>()
    val pending = ArrayDeque<MethodKey>().apply { addAll(bodies.keys.filter(isRoot)) }
    val visited = mutableSetOf<MethodKey>()
    while (pending.isNotEmpty()) {
      val key = pending.removeFirst()
      if (!visited.add(key)) continue
      val body = bodies[key] ?: continue
      collected += body.calls
      pending.addAll(body.nestedMethods.filter(shouldFollow))
    }
    return collected
  }

  private data class MethodKey(val name: String, val descriptor: String)

  private data class MethodBody(val calls: List<Invocation>, val nestedMethods: Set<MethodKey>)

  /**
   * Compose 2.x stores non-capturing composable lambdas in generated
   * `ComposableSingletons$…$lambda$<key>$…` classes. A preview only calls the matching
   * `getLambda$<key>$…` getter, so walk that narrowly identified generated class to find the
   * component rendered inside `Theme { … }` / `Wrap { … }`.
   */
  private fun extractComposeSingletonLambdaCalls(
    directCalls: List<Invocation>,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
  ): List<Invocation> =
    directCalls
      .asSequence()
      .filter {
        it.ownerFqn in projectClassFqns &&
          ".ComposableSingletons$" in it.ownerFqn &&
          it.methodName.startsWith("getLambda$")
      }
      .flatMap { getter ->
        val lambdaKey = getter.methodName.removePrefix("getLambda$").substringBefore('$')
        if (lambdaKey.isEmpty()) return@flatMap emptySequence()
        val lambdaClassPrefix = getter.ownerFqn + "\$lambda\$$lambdaKey\$"
        val ownerResource = scanResult.getClassInfo(getter.ownerFqn)?.resource
        if (ownerResource == null) return@flatMap emptySequence()
        referencedClasses(ownerResource, lambdaClassPrefix)
          .asSequence()
          .flatMap { lambdaClassFqn ->
            scanResult
              .getResourcesWithPathIgnoringAccept(lambdaClassFqn.replace('.', '/') + ".class")
              .asSequence()
              .map { lambdaClassFqn to it }
          }
          .filter { it.second.classpathElementURI == ownerResource.classpathElementURI }
          .flatMap { (lambdaClassFqn, resource) ->
            try {
              extractCalls(
                  resource = resource,
                  ownerFqn = lambdaClassFqn,
                  isRoot = { key ->
                    key.name == "invoke" && "Landroidx/compose/runtime/Composer;" in key.descriptor
                  },
                  shouldFollow = { false },
                )
                .asSequence()
            } catch (_: Throwable) {
              emptySequence()
            }
          }
      }
      .distinct()
      .toList()

  private fun referencedClasses(resource: Resource, classFqnPrefix: String): Set<String> {
    val internalPrefix = classFqnPrefix.replace('.', '/')
    val referenced = mutableSetOf<String>()
    resource.open().use { stream ->
      ClassReader(stream)
        .accept(
          object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String?,
              exceptions: Array<out String>?,
            ): MethodVisitor =
              object : MethodVisitor(Opcodes.ASM9) {
                override fun visitFieldInsn(
                  opcode: Int,
                  owner: String,
                  name: String,
                  descriptor: String,
                ) {
                  if (owner.startsWith(internalPrefix)) {
                    referenced += owner.replace('/', '.')
                  }
                }
              }
          },
          ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return referenced
  }

  internal fun isValidKotlinImportIdentifier(name: String): Boolean =
    KOTLIN_IMPORT_IDENTIFIER.matches(name)

  internal fun isPreviewOnlyWrapper(methodName: String, sourceFile: String?): Boolean {
    val sourceName = methodName.substringBefore('$')
    if (
      sourceName == "Wrap" ||
        sourceName.endsWith("Theme") ||
        sourceName.endsWith("PreviewWrapper") ||
        sourceName.endsWith("PreviewScope")
    ) {
      return true
    }
    val path = sourceFile?.replace('\\', '/')?.lowercase() ?: return false
    val isDebugSource = path.startsWith("src/debug/") || "/src/debug/" in path
    return isDebugSource && (path.startsWith("catalog/") || "/catalog/" in path)
  }

  private val KOTLIN_IMPORT_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

  private data class ResolvedCandidate(
    val ownerFqn: String,
    val method: MethodInfo,
    val classInfo: ClassInfo,
  )

  /**
   * Whether a resolved call in a component library is the **component a sticker demonstrates**.
   *
   * Three rules, each earning its place against `:samples:cmp`:
   * * the name must be usable as a Kotlin import — a mangled or synthetic member is not a call site
   *   anyone can write;
   * * [returnsUnit] — a component *emits*, it does not return a value. `MaterialTheme.colorScheme`
   *   and `MaterialTheme.typography` are `@Composable` property getters that pass every other test
   *   here, and reporting them would describe a sticker's theme lookup as its subject;
   * * not a [THEME_ENTRY_POINTS] member — the frame a sticker is drawn in rather than its subject.
   */
  /**
   * The **source-level** name behind a JVM method name, undoing Kotlin's inline-class mangling.
   *
   * Kotlin mangles the JVM name of any function whose signature mentions a value class, appending
   * `-` and a hash of that signature: `androidx.compose.material3.Text` compiles to
   * `TextKt."Text-Nvy7gAk"` because its `fontSize`, `color` and `overflow` parameters are
   * `TextUnit`, `Color` and `TextOverflow`. A Kotlin identifier can never contain `-`, so the first
   * one is unambiguously the mangling boundary.
   *
   * This is load-bearing rather than cosmetic, and it bites on **both** inference paths, because
   * both reject a name that is not a usable import:
   * * [inferComponents] dropped **every Material 3 component whose signature mentions `Color`, `Dp`
   *   or `TextUnit`**, `Text` among them — a preview whose only call was `Text` inferred no
   *   component at all;
   * * [infer] dropped any of the project's *own* composables with a value-class parameter, which in
   *   a typical app means anything taking a `Dp`. `AppTile(padding: Dp)` left its preview with
   *   `targets = []` for a composable the preview does nothing but render.
   *
   * The functional compile gate is what surfaced it. `Button` and `Card` take no value-class
   * parameters, so the hand-written unit tests never had a mangled name to trip over — a corpus
   * chosen by hand is a corpus that agrees with you.
   *
   * Only the `-` mangle, deliberately. Kotlin's other JVM mangle is `name$module` for an `internal`
   * function ([nameMatches] strips that one for its own purposes), but stripping `$` here would
   * also turn `Card$lambda$0` into `Card` and rescue exactly the synthetic members
   * [isComponentLibraryTarget] exists to reject — and a library's `internal` composable is not a
   * call site a consumer could write anyway.
   */
  internal fun sourceFunctionName(jvmName: String): String = jvmName.substringBefore('-')

  internal fun isComponentLibraryTarget(
    ownerFqn: String,
    methodName: String,
    returnsUnit: Boolean,
  ): Boolean =
    isValidKotlinImportIdentifier(methodName) &&
      returnsUnit &&
      "$ownerFqn.$methodName" !in THEME_ENTRY_POINTS

  private fun resolveCandidate(call: Invocation, scanResult: ScanResult): ResolvedCandidate? {
    val classInfo = scanResult.getClassInfo(call.ownerFqn) ?: return null
    val candidateMethods = classInfo.methodInfo?.filter { it.name == call.methodName }.orEmpty()
    if (candidateMethods.isEmpty()) return null
    val composable =
      candidateMethods.firstOrNull { it.hasAnnotation(COMPOSABLE_FQN) } ?: return null
    // Skip composables that themselves carry a @Preview — those are sibling previews, not the
    // production target.
    if (
      composable.hasAnnotation(PREVIEW_FQN) ||
        composable.hasAnnotation(DESKTOP_PREVIEW_FQN) ||
        composable.hasAnnotation(CMP_PREVIEW_FQN) ||
        composable.hasAnnotation(TILE_PREVIEW_FQN)
    ) {
      return null
    }
    return ResolvedCandidate(call.ownerFqn, composable, classInfo)
  }

  private data class ScoredCandidate(
    val classFqn: String,
    val methodName: String,
    val sourceFile: String?,
    val score: Int,
    val signals: List<TargetSignal>,
  )

  @Suppress("LongParameterList")
  private fun score(
    callerOwner: String,
    callerMethod: MethodInfo,
    callerClassInfo: ClassInfo,
    previewClassFqn: String,
    previewMethodName: String,
    previewSourceFile: String?,
    variantName: String,
    hasPreviewParameter: Boolean,
    callerMethodHasComposableParam: Boolean,
    wrapperUnwrapped: Boolean,
    totalSurvivors: Int,
    resolveSourceFile: (String) -> String?,
  ): ScoredCandidate {
    var score = 0
    val signals = mutableListOf<TargetSignal>()

    // +3 if this is the only project-local non-wrapper composable left after filtering.
    if (totalSurvivors == 1) {
      score += 3
      signals += TargetSignal.SINGLE_PROJECT_COMPOSABLE_CALL
    } else {
      // Multiple survivors penalise *each* candidate by (n-1) so the top one still has a
      // chance to clear the threshold when it independently matches by name.
      score -= (totalSurvivors - 1)
    }

    // +2 if `FooPreview` / `PreviewFoo` / `Foo_*_Preview` strips down to the candidate's name.
    // Compared against the *source* name: `MyCardPreview` never strips down to `MyCard-abc123`.
    if (nameMatches(previewMethodName, sourceFunctionName(callerMethod.name))) {
      score += 2
      signals += TargetSignal.NAME_MATCH
    }

    // +1 if the candidate lives in a different .class file than the preview. Top-level functions
    // share an owner only when declared in the same source file (Kotlin's `<File>Kt` synthetic),
    // so this is a clean proxy for "cross-file".
    val crossFile = callerOwner != previewClassFqn
    if (crossFile) {
      score += 1
      signals += TargetSignal.CROSS_FILE
    }

    // +1 if the preview is in a non-shipping source set (debug / test / screenshotTest).
    if (variantName in NON_SHIPPING_SOURCE_SETS) {
      score += 1
      signals += TargetSignal.NON_SHIPPING_SOURCE_SET
    }

    // +1 if the preview file looks dedicated to previews (file name `*Previews?.kt`).
    if (
      previewSourceFile != null &&
        DEDICATED_FILE_REGEX.matches(previewSourceFile.substringAfterLast('/'))
    ) {
      score += 1
      signals += TargetSignal.DEDICATED_PREVIEW_FILE
    }

    // +1 when the preview takes a @PreviewParameter and the candidate has a non-composable param
    // it could plausibly receive. Approximate but cheap.
    if (hasPreviewParameter && callerMethodHasComposableParam) {
      score += 1
      signals += TargetSignal.PARAMETER_FORWARDED
    }

    if (wrapperUnwrapped) {
      signals += TargetSignal.WRAPPER_UNWRAPPED
    }

    return ScoredCandidate(
      classFqn = callerOwner,
      methodName = sourceFunctionName(callerMethod.name),
      sourceFile = resolveSourceFile(callerOwner) ?: packageQualifiedSourcePath(callerClassInfo),
      score = score,
      signals = signals,
    )
  }

  /**
   * `FooPreview` ↔ `Foo`, `PreviewFoo` ↔ `Foo`, `Foo_Light_Preview` ↔ `Foo`, `FooScreenPreview` ↔
   * `FooScreen`. Internal-mangled JVM names (`InternalPreview$module`) are stripped first.
   */
  internal fun nameMatches(previewMethodName: String, candidateName: String): Boolean {
    // Strip the JVM `internal fun` mangle (`name$module`) before any name-shape work.
    val cleaned = previewMethodName.substringBefore('$')
    // Order matters: try the more specific affixes (`_Preview` / `Preview_`) before the bare ones,
    // so `Foo_Preview` becomes `Foo`, not `Foo_`. Final `_` trim catches any residue.
    val stripped =
      cleaned
        .removeSuffix("_Preview")
        .removeSuffix("Preview")
        .removePrefix("Preview_")
        .removePrefix("Preview")
        .trim('_')
    if (stripped.isBlank()) return false
    if (stripped == candidateName) return true
    // Allow `Foo_Light_Preview` → `Foo_Light` → match `Foo` by the leading segment.
    val leadingSegment = stripped.substringBefore('_')
    return leadingSegment.isNotBlank() && leadingSegment == candidateName
  }

  /**
   * Mirror of `DiscoverPreviewsTask.packageQualifiedSourcePath`: builds a `<pkg>/<File>.kt`-shaped
   * fallback for when the source file isn't wired into the task. Used as the second leg of source
   * resolution so consumers always see *something*.
   */
  private fun packageQualifiedSourcePath(classInfo: ClassInfo): String? {
    val simpleName = classInfo.sourceFile ?: return null
    val pkg = classInfo.packageName.orEmpty()
    return if (pkg.isEmpty()) simpleName else "${pkg.replace('.', '/')}/$simpleName"
  }
}
