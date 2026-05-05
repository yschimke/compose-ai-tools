package ee.schimke.composeai.plugin

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
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
 * Wrapper detection is FQN-prefix based. Project-local theme wrappers (e.g. `MyAppTheme { … }`) are
 * intentionally **not** unwrapped here — that needs reading the lambda's synthetic `invoke` class
 * and is reserved for a follow-up. The [TargetSignal.WRAPPER_UNWRAPPED] enum value is declared for
 * that future use.
 */
internal object PreviewTargetInference {

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
    val calls =
      try {
        extractCalls(previewClassInfo, previewMethod)
      } catch (_: Throwable) {
        // Bytecode unavailable / unreadable — discovery should still succeed without target
        // inference. The preview is still emitted; consumers see `targets = []` and fall back
        // to whatever signal they had before.
        return emptyList()
      }

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
        .distinctBy { it.ownerFqn to it.method.name }
        .toList()

    if (candidates.isEmpty()) return emptyList()

    val survivors = candidates.size
    val scored = candidates.map { (callerOwner, callerMethod, callerClassInfo) ->
      score(
        callerOwner = callerOwner,
        callerMethod = callerMethod,
        callerClassInfo = callerClassInfo,
        previewClassFqn = previewFqn,
        previewMethodName = previewMethodName,
        previewSourceFile = previewSourceFile,
        variantName = variantName,
        hasPreviewParameter = hasPreviewParameter,
        callerMethodHasComposableParam =
          callerMethod.parameterInfo?.any { p ->
            // Heuristic for "this candidate consumes a value of the same shape as the preview's
            // @PreviewParameter" — a non-`@Composable () -> Unit` parameter on the candidate.
            // Cheap stand-in for proper data-flow analysis; good enough to flag the common
            // `@PreviewParameter color: Long → Foo(color)` pattern.
            p.annotationInfo?.none { it.name == COMPOSABLE_FQN } ?: true
          } == true,
        totalSurvivors = survivors,
        resolveSourceFile = resolveSourceFile,
      )
    }

    val best = scored.maxByOrNull { it.score } ?: return emptyList()
    if (best.score < MIN_EMIT_SCORE) return emptyList()

    val confidence =
      when {
        best.score >= HIGH_THRESHOLD -> TargetConfidence.HIGH
        best.score >= MEDIUM_THRESHOLD -> TargetConfidence.MEDIUM
        else -> TargetConfidence.LOW
      }
    return listOf(
      PreviewTarget(
        className = best.classFqn,
        functionName = best.methodName,
        sourceFile = best.sourceFile,
        confidence = confidence,
        signals = best.signals,
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
    val targetName = previewMethod.name
    val targetDescriptor = previewMethod.typeDescriptorStr
    val collected = mutableListOf<Invocation>()
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
              if (name != targetName) return null
              if (descriptor != targetDescriptor) return null
              return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                  opcode: Int,
                  owner: String,
                  name: String,
                  descriptor: String,
                  isInterface: Boolean,
                ) {
                  collected += Invocation(owner.replace('/', '.'), name, descriptor)
                }
              }
            }
          },
          ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return collected
  }

  private data class ResolvedCandidate(
    val ownerFqn: String,
    val method: MethodInfo,
    val classInfo: ClassInfo,
  )

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
    if (nameMatches(previewMethodName, callerMethod.name)) {
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

    return ScoredCandidate(
      classFqn = callerOwner,
      methodName = callerMethod.name,
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
