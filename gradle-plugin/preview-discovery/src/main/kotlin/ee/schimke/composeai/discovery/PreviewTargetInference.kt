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

  /**
   * Single bytecode call site, as captured from the preview method body.
   *
   * [viaLambda] marks a call the walk reached by descending into one of the preview's content
   * lambdas rather than one the preview body makes itself. The distinction has to be recorded here
   * because the two are indistinguishable afterwards, and because **whether a lambda is even
   * reachable depends on the Kotlin compiler, not on the preview**: a non-capturing composable
   * lambda is lifted into a `ComposableSingletons$…` class (walked, narrowly, by
   * [extractComposeSingletonLambdaCalls]), while one that captures compiles to a
   * `<preview>$lambda$N` method of the preview's own class, which the nested-method walk follows
   * wholesale. Adding a single defaulted parameter to a preview flips it from the first shape to
   * the second, so without this flag `infer`'s "how many project composables did this preview
   * call?" count — and with it the target — changes for a preview whose body did not.
   *
   * Only the nested-method descent is tagged. [extractComposeSingletonLambdaCalls]'s results are
   * lambda contents by the same argument and arguably belong here too, but tagging them changes the
   * target of previews that have nothing to do with this bug — a `Theme { … }` sticker whose
   * candidates were all suppressed by the survivor penalty starts reporting the frame it wraps — so
   * that half is deliberately left alone rather than settled as a side effect of this fix.
   */
  internal data class Invocation(
    val ownerFqn: String,
    val methodName: String,
    val descriptor: String,
    val viaLambda: Boolean = false,
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
        // Pair each candidate with its metadata up front, because the *source* name lives there
        // and every decision below is about the source name. A candidate whose metadata cannot be
        // read is dropped: without it there is no way to tell a mangled JVM name from a legally
        // escaped one, and reporting the JVM name is how a nonexistent import gets published.
        .mapNotNull { candidate ->
          // The scan goes with it so a required parameter whose type constructs itself can be
          // recognised (issue #5067) — `TextField(state = TextFieldState())`. Only this path needs
          // it: these are the library components whose call sites get printed.
          ComposableSignature.signatureOf(candidate.classInfo, candidate.method, scanResult)?.let {
            candidate to it
          }
        }
        .filter { (candidate, signature) ->
          isComponentLibraryTarget(
            ownerFqn = candidate.ownerFqn,
            methodName = signature.name,
            returnsUnit = candidate.method.typeDescriptor?.resultType?.toString() == "void",
          )
        }
        .distinctBy { (candidate, signature) -> candidate.ownerFqn to signature.name }
        .toList()
    if (candidates.isEmpty()) return emptyList()
    val confidence = if (candidates.size == 1) TargetConfidence.HIGH else TargetConfidence.MEDIUM
    return candidates.map { (candidate, signature) ->
      PreviewTarget(
        className = candidate.ownerFqn,
        functionName = signature.name,
        jvmName = candidate.method.name,
        descriptor = candidate.method.typeDescriptorStr,
        // A library symbol has no source file in this build; `sourceFile` stays null and
        // [PreviewTarget.origin] is what says so, rather than the null being read as "library".
        sourceFile = null,
        confidence = confidence,
        signals = listOf(TargetSignal.LIBRARY_COMPONENT),
        parameters = signature.parameters,
        receiver = signature.receiver,
        signatureKnown = true,
        callableFromAnotherFile = signature.callableFromAnotherFile,
        hasTypeParameters = signature.hasTypeParameters,
        hasContextReceivers = signature.hasContextReceivers,
        requiredOptIns = signature.requiredOptIns,
        androidxOptIns = signature.androidxOptIns,
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
        // Pair each candidate with its metadata before judging its name, because every decision
        // below is about the *source* name and only metadata carries it. Unlike `inferComponents`,
        // a candidate whose metadata cannot be read is kept rather than dropped: `signatureKnown`
        // already models "we could not look", `infer` has always emitted such targets, and
        // dropping them here would trade a wrong name for a missing target.
        .map { candidate ->
          candidate to ComposableSignature.signatureOf(candidate.classInfo, candidate.method)
        }
        .filterNot { (candidate, signature) ->
          // No metadata means no source name, so the JVM name is all there is — and then the
          // import filter below is the only guard, exactly as it was before this pairing existed.
          val sourceName = signature?.name ?: candidate.method.name
          !isValidKotlinImportIdentifier(sourceName) ||
            isPreviewOnlyWrapper(sourceName, resolveSourceFile(candidate.ownerFqn))
        }
        .distinctBy { (candidate, signature) ->
          candidate.ownerFqn to (signature?.name ?: candidate.method.name)
        }
        .toList()

    if (candidates.isEmpty()) return emptyList()

    // Count the composables the preview body calls ITSELF, and fall back to the lambda-reached
    // ones only when it calls none directly — the `Theme { Screen() }` shape, where the wrapper is
    // filtered out and the subject is only reachable through the lambda.
    //
    // A call reached by descending into a content lambda is not evidence that the preview renders
    // several things side by side; it is the inside of the one thing it wraps. Counting both
    // together made the penalty below depend on whether that lambda captured — a Kotlin compiler
    // decision that a single defaulted parameter flips, silently changing the target of a preview
    // whose body did not change. See [Invocation.viaLambda].
    val directCallKeys =
      calls
        .asSequence()
        .filterNot { it.viaLambda }
        .mapTo(mutableSetOf()) { it.ownerFqn to it.methodName }
    fun key(candidate: ResolvedCandidate) = candidate.ownerFqn to candidate.method.name
    val directCandidates = candidates.filter { (candidate, _) -> key(candidate) in directCallKeys }
    // The candidates the count is taken over. Anything outside it is still allowed to win, but on
    // its own merits (name match, cross-file, …) rather than by being "the only call".
    val counted = (directCandidates.ifEmpty { candidates }).mapTo(mutableSetOf()) { key(it.first) }
    val survivors = counted.size
    // Keep each candidate paired with its score so the winner's resolved metadata is in hand below
    // rather than read a second time.
    val scored = candidates.map { (candidate, signature) ->
      (candidate to signature) to
        score(
          callerOwner = candidate.ownerFqn,
          callerMethod = candidate.method,
          callerSourceName = signature?.name ?: candidate.method.name,
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
          counted = key(candidate) in counted,
          resolveSourceFile = resolveSourceFile,
        )
    }

    val (winner, best) = scored.maxByOrNull { it.second.score } ?: return emptyList()
    if (best.score < MIN_EMIT_SCORE) return emptyList()

    val confidence =
      when {
        best.score >= HIGH_THRESHOLD -> TargetConfidence.HIGH
        best.score >= MEDIUM_THRESHOLD -> TargetConfidence.MEDIUM
        else -> TargetConfidence.LOW
      }
    val signature = winner.second
    return listOf(
      PreviewTarget(
        className = best.classFqn,
        // The source name when metadata gave one, else the JVM name — `signatureKnown` says which.
        functionName = best.methodName,
        jvmName = best.jvmName,
        descriptor = best.descriptor,
        sourceFile = best.sourceFile,
        confidence = confidence,
        signals = best.signals,
        // The target's real Kotlin value parameters (names / types / defaults) for the call site a
        // consumer renders into Code Connect. Best-effort — empty when metadata can't be read,
        // which `signatureKnown` is what distinguishes from a parameterless composable.
        parameters = signature?.parameters.orEmpty(),
        receiver = signature?.receiver,
        signatureKnown = signature != null,
        // Null metadata keeps the permissive defaults: "not recovered" must not read as "private".
        callableFromAnotherFile = signature?.callableFromAnotherFile ?: true,
        hasTypeParameters = signature?.hasTypeParameters ?: false,
        hasContextReceivers = signature?.hasContextReceivers ?: false,
        requiredOptIns = signature?.requiredOptIns.orEmpty(),
        androidxOptIns = signature?.androidxOptIns.orEmpty(),
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
      // Only a root body's calls are the preview's own; everything a followed nested method calls
      // was reached by descending into a lambda. See [Invocation.viaLambda].
      collected += if (isRoot(key)) body.calls else body.calls.map { it.copy(viaLambda = true) }
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
   * Three rules, each earning its place against `:samples:cmp`: the name must be usable as a Kotlin
   * import; [returnsUnit], because a component *emits* rather than returning a value
   * (`MaterialTheme.colorScheme` is a `@Composable` property getter that passes every other test
   * here, and reporting it would describe a sticker's theme lookup as its subject); and not a
   * [THEME_ENTRY_POINTS] member, which is the frame a sticker is drawn in rather than its subject.
   *
   * [methodName] must be the **source** name from `@kotlin.Metadata`, never the JVM one. Kotlin
   * mangles the JVM name of any function whose signature mentions a value class, so
   * `androidx.compose.material3.Text` compiles to `TextKt."Text-Nvy7gAk"` — its `fontSize`, `color`
   * and `overflow` are `TextUnit`, `Color` and `TextOverflow`. Judged on the JVM name the import
   * rule below rejects it, which silently dropped **every Material 3 component whose signature
   * mentions `Color`, `Dp` or `TextUnit`** from the component record, `Text` included: a preview
   * whose only call was `Text` inferred no component at all.
   *
   * Recovering the source name by trimming at the first `-` would be wrong in the other direction.
   * A backtick-escaped declaration is legal Kotlin and its own name may contain a hyphen — ``fun
   * `filled-button`()`` — so trimming publishes `filled`, a function that does not exist. Only
   * metadata separates the two, which is why [inferComponents] reads it before deciding anything.
   */
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
    /** Source-level name, or the JVM name when metadata could not be read. */
    val methodName: String,
    val jvmName: String,
    val descriptor: String,
    val sourceFile: String?,
    val score: Int,
    val signals: List<TargetSignal>,
  )

  @Suppress("LongParameterList")
  private fun score(
    callerOwner: String,
    callerMethod: MethodInfo,
    callerSourceName: String,
    callerClassInfo: ClassInfo,
    previewClassFqn: String,
    previewMethodName: String,
    previewSourceFile: String?,
    variantName: String,
    hasPreviewParameter: Boolean,
    callerMethodHasComposableParam: Boolean,
    wrapperUnwrapped: Boolean,
    totalSurvivors: Int,
    counted: Boolean,
    resolveSourceFile: (String) -> String?,
  ): ScoredCandidate {
    var score = 0
    val signals = mutableListOf<TargetSignal>()

    // +3 if this is the only project-local non-wrapper composable left after filtering. Only a
    // candidate inside the counted set can earn it: when a preview calls one composable directly
    // and that composable's lambda calls others, "the only call" describes the direct one, and
    // handing the bonus to a lambda-reached sibling would let it tie and win on call order.
    if (totalSurvivors == 1 && counted) {
      score += 3
      signals += TargetSignal.SINGLE_PROJECT_COMPOSABLE_CALL
    } else if (totalSurvivors > 1) {
      // Multiple survivors penalise *each* candidate by (n-1) so the top one still has a
      // chance to clear the threshold when it independently matches by name.
      score -= (totalSurvivors - 1)
    }

    // +2 if `FooPreview` / `PreviewFoo` / `Foo_*_Preview` strips down to the candidate's name.
    // Against the SOURCE name: `ScreenPreview` matches `Screen`, never `Screen-a1b2c3d`, so
    // scoring a mangled candidate on its JVM name silently withheld this signal from exactly the
    // previews whose naming convention was clearest.
    if (nameMatches(previewMethodName, callerSourceName)) {
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
      methodName = callerSourceName,
      jvmName = callerMethod.name,
      descriptor = callerMethod.typeDescriptorStr,
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
