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
 * Infers which production `@Composable` a `@Preview` renders: walks the preview's bytecode for
 * calls into project-local composables (theming/layout filtered by FQN), then scores them on
 * signals such as a non-shipping source set or a name match with the `Preview` suffix stripped.
 *
 * Emits at most one [PreviewTarget] for now; the list type leaves room for multi-target inference.
 * Compiler-generated lambda methods are traversed so `Theme { Component() }` nominates `Component`.
 */
object PreviewTargetInference {

  // FQN prefixes of theming / layout / runtime scaffolding, dropped before scoring. Prefixes reach
  // nested packages without listing every leaf.
  private val WRAPPER_FQN_PREFIXES =
    listOf(
      "androidx.compose.material.",
      "androidx.compose.material3.",
      "androidx.compose.foundation.",
      "androidx.compose.runtime.",
      "androidx.compose.ui.",
      "androidx.compose.animation.",
      "androidx.wear.compose.material.",
      "androidx.wear.compose.remote.material3.",
      "androidx.wear.compose.material3.",
      "androidx.wear.compose.foundation.",
      "org.jetbrains.compose.",
    )

  // The subset of [WRAPPER_FQN_PREFIXES] naming design-system **components** rather than layout,
  // runtime or drawing primitives. "Which of my composables does this render?" drops
  // `material3.Button`; "which component does this sticker demonstrate?" wants exactly it. Layout,
  // `remember` and `Modifier` stay scaffolding either way.
  /**
   * How far a component may sit behind the project's own composables and still count as the
   * preview's: `Sticker { Frame { Component() } }` is two, plus one to spare.
   */
  private const val PROJECT_COMPOSABLE_MAX_DEPTH = 3

  private val COMPONENT_LIBRARY_FQN_PREFIXES =
    listOf(
      "androidx.compose.material3.",
      "androidx.compose.material.",
      "androidx.wear.compose.material3.",
      "androidx.wear.compose.material.",
      "androidx.wear.compose.remote.material3.",
      // Glimmer is AndroidX's glasses design system; its stickers have the same wrapper → component
      // shape.
      "androidx.xr.glimmer.",
    )

  // Theme entry points inside component libraries: the frame a sticker is drawn in, not its
  // subject. A denylist because no shape rule separates `MaterialTheme(…, content)` from `Card(…,
  // content)`; the set is small and stable.
  private val THEME_ENTRY_POINTS =
    setOf(
      "androidx.compose.material3.MaterialThemeKt.MaterialTheme",
      "androidx.compose.material.MaterialThemeKt.MaterialTheme",
      "androidx.wear.compose.material3.MaterialThemeKt.MaterialTheme",
      "androidx.wear.compose.material.MaterialThemeKt.MaterialTheme",
      // Remote Compose's theme entry point.
      "androidx.wear.compose.remote.material3.RemoteMaterialThemeKt.RemoteMaterialTheme",
      "androidx.xr.glimmer.GlimmerThemeKt.GlimmerTheme",
    )

  // Stdlib / JVM / Kotlin-runtime owners. Filtered explicitly so we never attempt to look
  // them up as project-local @Composable methods.
  private val STDLIB_FQN_PREFIXES = listOf("java.", "javax.", "kotlin.", "kotlinx.", "sun.", "jdk.")

  // Source sets that mark a preview file as non-shipping; used for scoring only.
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
  // Without CMP's @Preview, a composable called only by CMP previews would score as a render
  // target.
  private const val CMP_PREVIEW_FQN = PreviewDiscovery.CMP_PREVIEW_FQN
  private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"

  /**
   * One bytecode call site from the preview body.
   *
   * [viaLambda] marks calls reached by descending into a content lambda. It must be recorded here
   * because whether a lambda is reachable depends on the compiler: non-capturing lambdas are lifted
   * into `ComposableSingletons$…` (walked narrowly by [extractComposeSingletonLambdaCalls]), while
   * capturing ones become `<preview>$lambda$N` methods followed wholesale. A single defaulted
   * parameter flips the shape, which would otherwise change `infer`'s call count and target.
   *
   * Only the nested-method descent is tagged; tagging singleton-lambda results too would change
   * targets of unrelated previews.
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
   * @param projectClassFqns FQNs of classes compiled from the project's own sources; the
   *   project-local filter.
   * @param previewSourceFile module-relative source path of the preview file, when known.
   * @param resolveSourceFile maps a class FQN to its module-relative source path, or `null` when
   *   unknown.
   * @param variantName the source set the preview was discovered under (for
   *   `NON_SHIPPING_SOURCE_SET`).
   * @param hasPreviewParameter enables the `PARAMETER_FORWARDED` signal.
   */
  /**
   * The **design-system components** [previewMethod] renders, separate from [infer]'s project-local
   * answer: the two answer different questions, and mixing them would change what `targets[0]`
   * means.
   *
   * Candidates go through [resolveCandidate] (real `@Composable`, not itself a `@Preview`), ordered
   * by call order and deduped: one component → `HIGH`, several → `MEDIUM`. Parameters come from
   * `@kotlin.Metadata`, giving each library component its real signature.
   */
  fun inferComponents(
    previewClassInfo: ClassInfo,
    previewMethod: MethodInfo,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
    /**
     * Extra component-library owners from `componentLibraryPrefixes`; see
     * [isComponentLibraryOwner].
     */
    extraLibraryPrefixes: List<String> = emptyList(),
  ): List<PreviewTarget> =
    inferComponents(
      renderedCalls(previewClassInfo, previewMethod, scanResult, projectClassFqns),
      scanResult,
      extraLibraryPrefixes,
    )

  /**
   * Every call a preview renders through: its body, the singleton lambdas it passes, and project
   * composables reached up to [PROJECT_COMPOSABLE_MAX_DEPTH]. Empty when unreadable. Shared by
   * [inferComponents] and [drawsWearWidget].
   */
  internal fun renderedCalls(
    previewClassInfo: ClassInfo,
    previewMethod: MethodInfo,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
  ): List<Invocation> {
    val directCalls =
      try {
        extractCalls(previewClassInfo, previewMethod)
      } catch (_: Throwable) {
        return emptyList()
      }
    val lambdaCalls = extractComposeSingletonLambdaCalls(directCalls, scanResult, projectClassFqns)
    return directCalls +
      lambdaCalls +
      extractProjectComposableCalls(directCalls + lambdaCalls, scanResult, projectClassFqns)
  }

  /**
   * Whether [calls] reach a widget-preview entry point (`WearWidgetPreview` or our wrapping
   * `CapturingWearWidgetPreview`), which renders under the `WEAR_WIDGETS` profile whatever the
   * canvas says.
   */
  internal fun drawsWearWidget(calls: List<Invocation>): Boolean = calls.any { call ->
    (call.ownerFqn == CAPTURING_WEAR_WIDGET_PREVIEW_OWNER &&
      call.methodName.startsWith("CapturingWearWidgetPreview")) ||
      (call.ownerFqn.startsWith(GLANCE_WEAR_TOOLING_PREVIEW_PACKAGE) &&
        call.methodName.startsWith("WearWidgetPreview"))
  }

  private const val CAPTURING_WEAR_WIDGET_PREVIEW_OWNER =
    "ee.schimke.composeai.wear.preview.CapturingWearWidgetPreviewKt"
  private const val GLANCE_WEAR_TOOLING_PREVIEW_PACKAGE = "androidx.glance.wear.tooling.preview."

  internal fun inferComponents(
    calls: List<Invocation>,
    scanResult: ScanResult,
    extraLibraryPrefixes: List<String>,
  ): List<PreviewTarget> {
    val candidates =
      calls
        .asSequence()
        .filter { call -> isComponentLibraryOwner(call.ownerFqn, extraLibraryPrefixes) }
        .mapNotNull { resolveCandidate(it, scanResult) }
        // Metadata carries the source name every decision below depends on. Candidates without
        // metadata are dropped: a mangled JVM name can't be told from an escaped one, and reporting
        // it publishes a nonexistent import.
        .mapNotNull { candidate ->
          // The scan lets self-constructing required parameters be recognised (#5067), e.g.
          // `TextField(state = TextFieldState())`.
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
        // Library symbols have no source file here; [PreviewTarget.origin] says so.
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
        deprecated = ComposableSignature.isDeprecated(candidate.method),
        overloads = overloadsOf(candidate, scanResult),
      )
    }
  }

  /**
   * Every overload of [candidate]'s function, or empty when there's one. Overloads without readable
   * metadata are omitted, since calls are printed from them.
   */
  private fun overloadsOf(
    candidate: ResolvedCandidate,
    scanResult: ScanResult,
  ): List<TargetOverload> {
    if (candidate.overloads.size < 2) return emptyList()
    return candidate.overloads.mapNotNull { method ->
      val descriptor = method.typeDescriptorStr ?: return@mapNotNull null
      val signature =
        ComposableSignature.signatureOf(candidate.classInfo, method, scanResult)
          ?: return@mapNotNull null
      TargetOverload(
        jvmName = method.name,
        descriptor = descriptor,
        parameters = signature.parameters,
        receiver = signature.receiver,
        callableFromAnotherFile = signature.callableFromAnotherFile,
        hasTypeParameters = signature.hasTypeParameters,
        hasContextReceivers = signature.hasContextReceivers,
        requiredOptIns = signature.requiredOptIns,
        androidxOptIns = signature.androidxOptIns,
        deprecated = ComposableSignature.isDeprecated(method),
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
        // Unreadable bytecode: emit the preview without targets.
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
        // Pair candidates with metadata (for the source name), but unlike `inferComponents` keep
        // ones without it: `signatureKnown` models that, and dropping them would lose targets.
        .map { candidate ->
          candidate to ComposableSignature.signatureOf(candidate.classInfo, candidate.method)
        }
        .filterNot { (candidate, signature) ->
          // Without metadata the JVM name is all there is, and the import filter is the only guard.
          val sourceName = signature?.name ?: candidate.method.name
          !isValidKotlinImportIdentifier(sourceName) ||
            isPreviewOnlyWrapper(sourceName, resolveSourceFile(candidate.ownerFqn))
        }
        .distinctBy { (candidate, signature) ->
          candidate.ownerFqn to (signature?.name ?: candidate.method.name)
        }
        .toList()

    if (candidates.isEmpty()) return emptyList()

    // Count only composables the preview calls itself, falling back to lambda-reached ones when
    // there are none (`Theme { Screen() }`). Lambda contents are the inside of one thing, and
    // counting them made the penalty depend on whether the lambda captured. See
    // [Invocation.viaLambda].
    val directCallKeys =
      calls
        .asSequence()
        .filterNot { it.viaLambda }
        .mapTo(mutableSetOf()) { it.ownerFqn to it.methodName }
    fun key(candidate: ResolvedCandidate) = candidate.ownerFqn to candidate.method.name
    val directCandidates = candidates.filter { (candidate, _) -> key(candidate) in directCallKeys }
    // Candidates outside the counted set can still win, but only on their own signals.
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
              // Cheap heuristic for "consumes the @PreviewParameter value": a non-`@Composable ()
              // -> Unit` parameter.
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
        // Real value parameters for Code Connect call sites; empty when unreadable (see
        // `signatureKnown`).
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
   * Walks [previewMethod]'s body for `INVOKE*` instructions, matching methods by name + descriptor
   * so overloads don't mix.
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

                /**
                 * A singleton lambda reached by reading its field (GETSTATIC) rather than calling
                 * its getter, as when one singleton lambda passes another as content. Without this
                 * the walk stopped at the first nested `{ … }` (m3-catalog#317). Recorded as the
                 * getter call so lambda keys resolve in one place.
                 */
                override fun visitFieldInsn(
                  opcode: Int,
                  owner: String,
                  name: String,
                  descriptor: String,
                ) {
                  if (opcode != Opcodes.GETSTATIC) return
                  if (!name.startsWith("lambda$")) return
                  val ownerFqnRead = owner.replace('/', '.')
                  if (".ComposableSingletons$" !in ownerFqnRead) return
                  calls +=
                    Invocation(
                      ownerFqnRead,
                      "getLambda$" + name.removePrefix("lambda$") + "\$fieldRead",
                      descriptor,
                    )
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
      // Only a root body's calls are the preview's own; see [Invocation.viaLambda].
      collected += if (isRoot(key)) body.calls else body.calls.map { it.copy(viaLambda = true) }
      pending.addAll(body.nestedMethods.filter(shouldFollow))
    }
    return collected
  }

  private data class MethodKey(val name: String, val descriptor: String)

  private data class MethodBody(val calls: List<Invocation>, val nestedMethods: Set<MethodKey>)

  /**
   * Calls inside a preview's **non-capturing** composable lambdas, which the Compose compiler lifts
   * into `ComposableSingletons$…`; the preview only calls `getLambda$<key>$…`, so the body is found
   * by key. Both compiler layouts are read:
   * - **A class**, `ComposableSingletons$…$lambda$<key>$…`, body in `invoke(Composer, Int)` (up to
   *   Kotlin 2.2).
   * - **A static method**, `lambda_<key>$lambda$<n>(Composer, Int)` (Kotlin 2.3+); without this
   *   every `Theme { Component() }` preview reported the theme. Names are matched with `$` / `-`
   *   normalised, and the body's own nested `$lambda$n$…` callbacks are followed.
   */
  /**
   * The library components a preview reaches through its own composables. Without this, `Sticker {
   * Frame(…) }` hides every component behind the project frame. Project calls are followed up to
   * [PROJECT_COMPOSABLE_MAX_DEPTH] with a visited set, bounding cost and cycles. Not tagged
   * `viaLambda`: for components, one reached through a frame is as much the subject as one called
   * directly.
   */
  /**
   * Singleton holders are handled by [extractComposeSingletonLambdaCalls]; following the getter
   * here finds nothing.
   */
  private fun Invocation.isComposeSingleton(): Boolean = ".ComposableSingletons$" in ownerFqn

  private fun extractProjectComposableCalls(
    seed: List<Invocation>,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
  ): List<Invocation> {
    val found = mutableListOf<Invocation>()
    val visited = mutableSetOf<Triple<String, String, String>>()
    var frontier = seed.filter { it.ownerFqn in projectClassFqns && !it.isComposeSingleton() }
    repeat(PROJECT_COMPOSABLE_MAX_DEPTH) {
      val next = mutableListOf<Invocation>()
      for (call in frontier) {
        if (!visited.add(Triple(call.ownerFqn, call.methodName, call.descriptor))) continue
        val resource = scanResult.getClassInfo(call.ownerFqn)?.resource ?: continue
        val inner =
          try {
            extractCalls(
              resource = resource,
              ownerFqn = call.ownerFqn,
              isRoot = { key -> key.name == call.methodName && key.descriptor == call.descriptor },
              // Following nested methods is how a captured lambda's body is reached.
              shouldFollow = { key -> key.name.contains('$') },
            )
          } catch (_: Throwable) {
            continue
          }
        found += inner
        next += inner.filter { it.ownerFqn in projectClassFqns && !it.isComposeSingleton() }
        // Project composables can pass content to singleton lambdas too, so apply the hop at every
        // level.
        val throughLambdas = extractComposeSingletonLambdaCalls(inner, scanResult, projectClassFqns)
        found += throughLambdas
        next += throughLambdas.filter { it.ownerFqn in projectClassFqns }
      }
      frontier = next
      if (frontier.isEmpty()) return found
    }
    return found
  }

  /**
   * How many singleton lambdas deep to go: each nested `{ … }` is its own entry (`Sticker {
   * KeyboardNavigable { InlineDialogHost { DatePickerDialog { DatePicker() } } } }`). Bounded like
   * [PROJECT_COMPOSABLE_MAX_DEPTH].
   */
  private const val SINGLETON_LAMBDA_MAX_DEPTH = 4

  /** The singleton lambda a call names, as `owner to key`. */
  private fun singletonLambdaTargets(
    calls: List<Invocation>,
    projectClassFqns: Set<String>,
  ): List<Pair<String, String>> =
    calls
      .filter {
        it.ownerFqn in projectClassFqns &&
          ".ComposableSingletons$" in it.ownerFqn &&
          it.methodName.startsWith("getLambda$")
      }
      .mapNotNull { getter ->
        val key = getter.methodName.removePrefix("getLambda$").substringBefore('$')
        if (key.isEmpty()) null else getter.ownerFqn to key
      }

  private fun extractComposeSingletonLambdaCalls(
    directCalls: List<Invocation>,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
  ): List<Invocation> {
    val found = mutableListOf<Invocation>()
    val visited = mutableSetOf<Pair<String, String>>()
    var frontier = singletonLambdaTargets(directCalls, projectClassFqns)
    repeat(SINGLETON_LAMBDA_MAX_DEPTH) {
      val next = mutableListOf<Pair<String, String>>()
      for (target in frontier) {
        if (!visited.add(target)) continue
        val calls = callsInSingletonLambda(target.first, target.second, scanResult)
        found += calls
        next += singletonLambdaTargets(calls, projectClassFqns)
      }
      frontier = next
      if (frontier.isEmpty()) return found.distinct()
    }
    return found.distinct()
  }

  /** Every call the singleton lambda [lambdaKey] of [singletonsFqn] makes, from either shape. */
  private fun callsInSingletonLambda(
    singletonsFqn: String,
    lambdaKey: String,
    scanResult: ScanResult,
  ): List<Invocation> {
    val ownerResource = scanResult.getClassInfo(singletonsFqn)?.resource ?: return emptyList()
    val lambdaClassPrefix = singletonsFqn + "\$lambda\$$lambdaKey\$"
    val lambdaClasses =
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
    val lambdaMethods =
      try {
        val bodyPrefix = "_" + normaliseLambdaName(lambdaKey) + "_lambda_"
        extractCalls(
            resource = ownerResource,
            ownerFqn = singletonsFqn,
            isRoot = { key ->
              isSingletonLambdaMethod(key.name, bodyPrefix) &&
                "Landroidx/compose/runtime/Composer;" in key.descriptor
            },
            shouldFollow = { key -> isSingletonLambdaMethod(key.name, bodyPrefix) },
          )
          .asSequence()
      } catch (_: Throwable) {
        emptySequence()
      }
    return (lambdaClasses + lambdaMethods).distinct().toList()
  }

  /**
   * Whether [methodName] is the static body of the singleton lambda whose normalised key gives
   * [bodyPrefix], or one of that body's own nested lambdas. `lambda__767012711$lambda$0` and
   * `lambda__767012711$lambda$0$1$0` both are, for the key `-767012711`; `lambda_1578330026$…` is
   * not.
   */
  private fun isSingletonLambdaMethod(methodName: String, bodyPrefix: String): Boolean =
    methodName.startsWith("lambda") &&
      normaliseLambdaName(methodName.removePrefix("lambda")).startsWith(bodyPrefix)

  /** `$` and `-` both become `_`, which is how the compiler spells a lambda key into a method. */
  private fun normaliseLambdaName(name: String): String = name.replace('$', '_').replace('-', '_')

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
    /** Every `@Composable` overload of the same name on the owner, declaration order. */
    val overloads: List<MethodInfo> = listOf(method),
  )

  /**
   * Whether a call's owner is in a component library: [COMPONENT_LIBRARY_FQN_PREFIXES] (prefix
   * match) or [extra]. Extra entries ending in `.` are packages; others are exact owner classes, so
   * `RemoteTextKt` doesn't also admit `RemoteTextKtx` or sibling layouts that would compete for
   * builder policy.
   */
  internal fun isComponentLibraryOwner(
    ownerFqn: String,
    extra: List<String> = emptyList(),
  ): Boolean =
    COMPONENT_LIBRARY_FQN_PREFIXES.any { ownerFqn.startsWith(it) } ||
      extra.any { entry ->
        if (entry.endsWith('.')) ownerFqn.startsWith(entry) else ownerFqn == entry
      }

  /**
   * Whether a resolved library call is the **component a sticker demonstrates**: a valid Kotlin
   * import name, [returnsUnit] (components emit; `MaterialTheme.colorScheme` is a getter), and not
   * a [THEME_ENTRY_POINTS] member.
   *
   * [methodName] must be the **source** name from metadata. JVM names of functions mentioning value
   * classes are mangled (`Text-Nvy7gAk`), and judging those dropped every component using `Color`,
   * `Dp` or `TextUnit`. Trimming at `-` is wrong too: a backtick-escaped name may legally contain a
   * hyphen.
   */
  internal fun isComponentLibraryTarget(
    ownerFqn: String,
    methodName: String,
    returnsUnit: Boolean,
  ): Boolean =
    isValidKotlinImportIdentifier(methodName) &&
      returnsUnit &&
      "$ownerFqn.$methodName" !in THEME_ENTRY_POINTS

  /**
   * The overload a call site invoked, by JVM descriptor (Compose defaults travel in a bitmask, so
   * the descriptor is exact). Taking the first same-named composable recorded the wrong
   * `OutlinedTextField` overload. Falls back to the first composable when nothing matches.
   */
  internal fun <M> calledOverload(
    candidates: List<M>,
    calledDescriptor: String,
    descriptorOf: (M) -> String?,
    isComposable: (M) -> Boolean,
  ): M? {
    val composables = candidates.filter(isComposable)
    return composables.firstOrNull { descriptorOf(it) == calledDescriptor }
      ?: composables.firstOrNull()
  }

  private fun resolveCandidate(call: Invocation, scanResult: ScanResult): ResolvedCandidate? {
    val classInfo = scanResult.getClassInfo(call.ownerFqn) ?: return null
    val candidateMethods = classInfo.methodInfo?.filter { it.name == call.methodName }.orEmpty()
    if (candidateMethods.isEmpty()) return null
    val composable =
      calledOverload(
        candidateMethods,
        call.descriptor,
        descriptorOf = { it.typeDescriptorStr },
        isComposable = { it.hasAnnotation(COMPOSABLE_FQN) },
      ) ?: return null
    // Composables that are themselves previews are siblings, not targets.
    if (
      composable.hasAnnotation(PREVIEW_FQN) ||
        composable.hasAnnotation(DESKTOP_PREVIEW_FQN) ||
        composable.hasAnnotation(CMP_PREVIEW_FQN) ||
        composable.hasAnnotation(TILE_PREVIEW_FQN)
    ) {
      return null
    }
    val overloads = candidateMethods.filter {
      it.hasAnnotation(COMPOSABLE_FQN) &&
        !it.hasAnnotation(PREVIEW_FQN) &&
        !it.hasAnnotation(DESKTOP_PREVIEW_FQN) &&
        !it.hasAnnotation(CMP_PREVIEW_FQN) &&
        !it.hasAnnotation(TILE_PREVIEW_FQN)
    }
    return ResolvedCandidate(call.ownerFqn, composable, classInfo, overloads)
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

    // +3 for the only remaining candidate, but only within the counted set, so a lambda-reached
    // sibling can't tie the direct call.
    if (totalSurvivors == 1 && counted) {
      score += 3
      signals += TargetSignal.SINGLE_PROJECT_COMPOSABLE_CALL
    } else if (totalSurvivors > 1) {
      // With several survivors, each loses (n-1), so a name match can still clear the threshold.
      score -= (totalSurvivors - 1)
    }

    // +2 if the preview name strips to the candidate's **source** name (`ScreenPreview` → `Screen`,
    // never `Screen-a1b2c3d`).
    if (nameMatches(previewMethodName, callerSourceName)) {
      score += 2
      signals += TargetSignal.NAME_MATCH
    } else if (nameQualifies(previewMethodName, callerSourceName)) {
      // +1 for a CamelCase qualifier (`SessionCardPopulatedPreview` → `SessionCard`), so an exact
      // name still outranks a prefix.
      score += 1
      signals += TargetSignal.NAME_MATCH
    }

    // +1 if the candidate is in a different class file; top-level functions share an owner only
    // within one source file.
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

    // +1 when the preview has a @PreviewParameter and the candidate has a plausible non-composable
    // parameter.
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
   * `FooScreen`. Internal-mangled names are stripped first.
   */
  internal fun nameMatches(previewMethodName: String, candidateName: String): Boolean {
    val stripped = strippedPreviewName(previewMethodName)
    if (stripped.isBlank()) return false
    if (stripped == candidateName) return true
    // `Foo_Light_Preview` → `Foo_Light` → leading segment `Foo`.
    val leadingSegment = stripped.substringBefore('_')
    return leadingSegment.isNotBlank() && leadingSegment == candidateName
  }

  /** The preview's name with its `Preview` affixes, separators and JVM mangle removed. */
  private fun strippedPreviewName(previewMethodName: String): String {
    // Strip the JVM `internal fun` mangle (`name$module`) before any name-shape work.
    val cleaned = previewMethodName.substringBefore('$')
    // Try specific affixes (`_Preview` / `Preview_`) first so `Foo_Preview` becomes `Foo`, not
    // `Foo_`.
    val stripped =
      cleaned
        .removeSuffix("_Preview")
        .removeSuffix("Preview")
        .removePrefix("Preview_")
        .removePrefix("Preview")
        .trim('_')
    return stripped
  }

  /**
   * `SessionCardPopulatedPreview` ↔ `SessionCard`: the stripped name starts with the candidate's
   * and continues with an upper-case letter or digit. Never true where [nameMatches] is.
   */
  internal fun nameQualifies(previewMethodName: String, candidateName: String): Boolean {
    val stripped = strippedPreviewName(previewMethodName)
    if (stripped.isBlank() || candidateName.isBlank()) return false
    if (!stripped.startsWith(candidateName) || stripped.length == candidateName.length) return false
    val next = stripped[candidateName.length]
    return next.isUpperCase() || next.isDigit()
  }

  /**
   * Mirror of `DiscoverPreviewsTask.packageQualifiedSourcePath`: a `<pkg>/<File>.kt` fallback when
   * sources aren't wired in.
   */
  private fun packageQualifiedSourcePath(classInfo: ClassInfo): String? {
    val simpleName = classInfo.sourceFile ?: return null
    val pkg = classInfo.packageName.orEmpty()
    return if (pkg.isEmpty()) simpleName else "${pkg.replace('.', '/')}/$simpleName"
  }
}
