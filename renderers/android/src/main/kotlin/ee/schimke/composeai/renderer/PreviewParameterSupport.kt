package ee.schimke.composeai.renderer

import androidx.compose.runtime.reflect.ComposableMethod
import kotlin.math.ceil

/**
 * Reflective `@PreviewParameter` support shared by the two Android render bodies — `:renderer
 * -android`'s [RobolectricRenderTest] (which fans a provider out into one render per value) and
 * `:daemon:android`'s `RenderEngine` (which renders a single frame and therefore binds exactly one
 * value — value 0 by default, or the one a `row` token names when the caller addressed a specific
 * row; issue #3749).
 *
 * **Why the provider FQN is passed in rather than read off the method.** The upstream
 * `androidx.compose.ui.tooling.preview.PreviewParameter` annotation has
 * `AnnotationRetention.BINARY` — it is written into the class file but is *not* visible through
 * `Method.parameterAnnotations` at runtime, exactly like `@PreviewWrapper` (issue #1440). The
 * gradle plugin's discovery reads it off the class-file annotation tables into `previews.json`
 * (`params.previewParameterProviderClassName` / `params.previewParameterLimit`), and both render
 * bodies get it from there. Nothing here can recover it by reflecting on the composable.
 *
 * **What went wrong without this (issue #3027).** The daemon resolved every preview with the
 * parameterless `getDeclaredComposableMethod(functionName)` lookup, which matches only
 * `foo(Composer, int)`. A preview declaring `@PreviewParameter` compiles to `foo(<T>, Composer,
 * int)`, so the lookup threw `NoSuchMethodException: <class>.<function>` before composition ever
 * started — no PNG, no semantics, just a `.error.json`. Observed across 27 previews in one consumer
 * module.
 */
object PreviewParameterSupport {

  /** A resolved preview entrypoint: the composable method plus the arguments to invoke it with. */
  data class Resolved(val method: ComposableMethod, val args: List<Any?>)

  /**
   * Ceiling on how far a provider is enumerated to satisfy an addressed [resolve] row, and so on
   * the highest addressable row index.
   *
   * A label can only be matched against the label set of the whole fan-out, so without a bound an
   * infinite `generateSequence` provider would be driven to exhaustion. An index-addressed row
   * (`PARAM_<n>`) needs only `n + 1` values, but `n` comes from a caller-supplied previewId and the
   * annotation's `limit` defaults to `Int.MAX_VALUE` — so it needs the same bound, enforced
   * *before* enumeration, or one arbitrary id could wedge the renderer.
   *
   * Well past any real provider; a fan-out that long is a catalog, not a preview.
   */
  const val MAX_ROW_SCAN: Int = 256

  /**
   * Prefix of the index-addressed row token. Mirrors
   * [ee.schimke.composeai.daemon.PreviewRowAddress.INDEX_PREFIX] — the renderer artefacts don't
   * depend on `:daemon:core` (the dependency runs the other way), so the spelling is duplicated
   * here the same way [PreviewParameterLabels] is duplicated across the two renderers.
   */
  private const val INDEX_ROW_PREFIX: String = "PARAM_"

  /**
   * Resolves the composable entrypoint for a preview, supplying `@PreviewParameter` arguments when
   * the discovery manifest recorded a provider.
   *
   * [providerClassName] `null` (the common case) is the plain parameterless lookup. When a provider
   * is named, [row] selects which of its values to bind:
   * - `null` (the default) takes value 0 — the daemon's manifest ids are the un-suffixed base ids
   *   the fan-out renderer would render as `<id>_<label>`, and value 0 is the one Android Studio
   *   shows first as well.
   * - `"PARAM_<n>"` takes value `n` positionally, enumerating only as far as it must.
   * - anything else is matched against [rowSuffixes] — the same labels the fan-out puts in
   *   `<stem>_<label>.png`, so a row is addressed by the name a caller reads off disk (issue
   *
   * #3749). Exact match wins; see [matchLabel] for the case-insensitive fallback.
   *
   * A [row] naming an index past the end, or a label no value carries, throws
   * [PreviewParameterLoadException] listing what IS available — silently falling back to value 0
   * would render the wrong state under the requested id.
   *
   * The returned method is always opened for reflective invocation ([openForInvoke]) — Kotlin
   * `private fun` previews are idiomatic and resolve fine, but invoking one without that throws
   * `IllegalAccessException`. Doing it here rather than at each call site is what keeps the
   * daemon's scroll / `figma-svg-long` / held-session paths from each having to remember.
   *
   * [previewArgs] carries a **parameter knob** seed — a preview's own defaulted value parameters,
   * the secondary override format — and is meaningful only when there is no provider, since the two
   * are mutually exclusive: discovery reports knobs only when every value parameter has a default,
   * which a provider-bound one does not. Empty (the default) resolves the parameterless overload,
   * falling back to the defaulted shape when the preview has one.
   */
  fun resolve(
    clazz: Class<*>,
    functionName: String,
    providerClassName: String?,
    limit: Int = Int.MAX_VALUE,
    classLoader: ClassLoader? = null,
    row: String? = null,
    previewArgs: List<Any?> = emptyList(),
  ): Resolved {
    if (providerClassName.isNullOrBlank()) {
      // [resolveNoArgComposableMethod] rather than the bare lookup: a preview whose parameters all
      // declare defaults — the whole of the **parameter knob** format, and the shape a production
      // composable annotated `@Preview` in place almost always has (`modifier: Modifier =
      // Modifier`)
      // — compiles to `(realParams…, Composer, changed…, default…)`, which
      // `getDeclaredComposableMethod(name)` cannot see because it matches only `(Composer, int)`.
      // The standalone bake lane has resolved it that way since the JetLagged renders; the daemon
      // reached this seam and threw `NoSuchMethodException` before composition, so a defaulted
      // preview produced an `.error.json` and no PNG.
      if (previewArgs.isEmpty()) {
        return Resolved(
          resolveNoArgComposableMethod(clazz, functionName).openForInvoke(),
          emptyList(),
        )
      }
      return Resolved(
        findComposableMethodWithArgs(clazz, functionName, previewArgs).openForInvoke(),
        previewArgs,
      )
    }
    if (limit <= 0) {
      throw PreviewParameterLoadException(
        "@PreviewParameter(provider = $providerClassName, limit = $limit) on $functionName " +
          "leaves no value to render",
        null,
      )
    }
    val requested = row?.trim()?.takeIf { it.isNotEmpty() }
    val requestedIndex = requested?.let(::rowIndex)
    // [MAX_ROW_SCAN] bounds the INDEX lane too, and the check comes before enumeration rather than
    // after. `limit` is `Int.MAX_VALUE` for an un-annotated provider, so an arbitrary previewId
    // (`Screen_PARAM_100000000` — anyone who can name a preview can name that) would otherwise ask
    // `loadValues` to materialise a hundred million values: an infinite `generateSequence` provider
    // spins forever and a large finite one exhausts the daemon heap. One request must not be able
    // to wedge the renderer, so an index at or past the ceiling is rejected outright.
    if (requestedIndex != null && requestedIndex >= MAX_ROW_SCAN) {
      throw PreviewParameterLoadException(
        "@PreviewParameter(provider = $providerClassName) on $functionName: row $requestedIndex is " +
          "beyond the $MAX_ROW_SCAN-row addressing ceiling",
        null,
      )
    }
    // Enumerate the shortest prefix that can answer the request. No row still means `take(1)`,
    // which is what keeps an infinite `generateSequence` provider from being driven to exhaustion
    // on the overwhelmingly common unaddressed render.
    val scan =
      when {
        requested == null -> 1
        requestedIndex != null -> requestedIndex + 1
        else -> MAX_ROW_SCAN
      }.coerceAtMost(limit)
    val values = loadValues(providerClassName, limit = scan, classLoader = classLoader)
    if (values.isEmpty()) {
      throw PreviewParameterLoadException(
        "@PreviewParameter(provider = $providerClassName) on $functionName produced no values",
        null,
      )
    }
    val index =
      when {
        requested == null -> 0
        requestedIndex != null ->
          requestedIndex.takeIf { it < values.size }
            // The sequence ran dry before reaching the requested index, so `values` IS the whole
            // fan-out — list its rows. That makes an over-request (`PARAM_255`, the highest the
            // ceiling above admits) the cheap way for a caller to discover what a provider actually
            // offers, which is the only discovery path there is: `previews.json` carries one entry
            // per parameterized function and can't enumerate the provider (issue #3749).
            ?: throw PreviewParameterLoadException(
              "@PreviewParameter(provider = $providerClassName) on $functionName has no row " +
                "$requestedIndex — it yields ${values.size} value(s); " +
                "available rows: ${rowSuffixes(values)}",
              null,
            )
        else ->
          matchLabel(rowSuffixes(values), requested)
            ?: throw PreviewParameterLoadException(
              "@PreviewParameter(provider = $providerClassName) on $functionName has no row " +
                "named '$requested'; available rows: ${rowSuffixes(values)}",
              null,
            )
      }
    val args = listOf(values[index])
    return Resolved(findComposableMethodWithArgs(clazz, functionName, args).openForInvoke(), args)
  }

  /**
   * The index of the row [requested] names within [suffixes], or `null` when nothing matches.
   *
   * **Exact first, case-insensitive only when unambiguous.** Label derivation is case-*sensitive*
   * about collisions, so a provider yielding `Dark` and `dark` legitimately produces two distinct
   * fan-out files on a case-sensitive filesystem; folding case unconditionally would map both ids
   * onto the first value and silently render the wrong state for the second. The case-insensitive
   * pass is still worth having as a fallback — a label comes from user data (a value's
   * `name`/`toString()`, so `"Dark"`) while a hand-written id often spells the same axis
   * differently (`"dark"`), and nothing reconciles the two — but it only decides when exactly one
   * row matches. Two or more and the caller gets the "no row named …" diagnostic listing every row,
   * which is the right answer: the request was genuinely ambiguous.
   */
  internal fun matchLabel(suffixes: List<String>, requested: String): Int? {
    val exact = suffixes.indexOf(requested)
    if (exact >= 0) return exact
    val folded = suffixes.indices.filter { suffixes[it].equals(requested, ignoreCase = true) }
    return folded.singleOrNull()
  }

  /**
   * The row tokens for [values] — the fan-out filename suffixes of `<stem>_<suffix>.<ext>` with the
   * leading `_` stripped, so `["Dark", "Light"]` or `["PARAM_0", "PARAM_1"]`.
   *
   * Public passthrough over the module-internal [PreviewParameterLabels] so [resolve]'s row
   * matching and its diagnostics report exactly the tokens the fan-out wrote, without duplicating
   * the label-derivation rules (which are collision-sensitive across the *whole* list — see that
   * file).
   */
  fun rowSuffixes(values: List<Any?>): List<String> =
    PreviewParameterLabels.suffixesFor(values).map { it.removePrefix("_") }

  /**
   * The index a `PARAM_<n>` row token names, or `null` for a label token.
   *
   * The suffix must be **digits only** — the same grammar [PreviewParameterLabels] reserves. Going
   * through `toIntOrNull` alone would accept signed spellings that label derivation happily keeps
   * as labels: `PARAM_-0` parses to 0 and passes a `>= 0` check, so `<stem>_PARAM_-0.png` — a real
   * labelled row on disk — would silently bind value 0 instead. The two grammars have to be the
   * same one or a token means different things to the writer and the reader.
   */
  private fun rowIndex(row: String): Int? =
    if (row.startsWith(INDEX_ROW_PREFIX)) {
      val digits = row.removePrefix(INDEX_ROW_PREFIX)
      if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) digits.toIntOrNull() else null
    } else null

  /**
   * Opens [this] method for reflective invocation. Guarded with `runCatching`: a SecurityManager or
   * strong module encapsulation can refuse, in which case we still attempt the invoke (which
   * succeeds for public/internal previews) rather than failing resolution outright — same contract
   * as `PreviewRenderStrategy`'s ComposePreviewStrategy.
   */
  private fun ComposableMethod.openForInvoke(): ComposableMethod = also {
    runCatching { it.asMethod().isAccessible = true }
  }

  /**
   * Enumerate a `PreviewParameterProvider`'s values reflectively, up to [limit].
   *
   * Throws [PreviewParameterLoadException] on any hard failure (class missing, no no-arg
   * constructor, missing/throwing `getValues()`) so the caller can isolate the failing preview and
   * surface it as a per-preview error card rather than letting the throw sink a whole shard.
   * Returns an empty list only when the provider legitimately yields no values.
   *
   * [classLoader] is the loader the consumer's classes live on — the daemon hands in its per-swap
   * child loader; the standalone renderer leaves it null and uses the caller's (which under
   * Robolectric is the sandbox loader).
   */
  fun loadValues(
    providerFqn: String,
    limit: Int,
    classLoader: ClassLoader? = null,
  ): List<Any?> {
    val clazz =
      try {
        if (classLoader == null) Class.forName(providerFqn)
        else Class.forName(providerFqn, true, classLoader)
      } catch (e: ClassNotFoundException) {
        throw PreviewParameterLoadException(
          "provider class $providerFqn not found on the render classpath",
          e,
        )
      }
    val instance =
      try {
        val ctor = clazz.getDeclaredConstructor()
        // `private` providers (idiomatic Kotlin, and rendered fine by Android Studio) compile to
        // package-private JVM classes, so their no-arg constructor isn't reflectively callable from
        // this package without opening it up first — see issue #2493.
        ctor.isAccessible = true
        ctor.newInstance()
      } catch (e: Throwable) {
        throw PreviewParameterLoadException(
          "couldn't instantiate $providerFqn via its no-arg constructor",
          e,
        )
      }
    // `PreviewParameterProvider<T>` exposes `values: Sequence<T>` as a Kotlin property — its JVM
    // signature is `getValues(): Sequence`. Look up the method by name to avoid taking a
    // compile-time dependency on the provider interface (which lives in the consumer's Compose
    // artifact).
    val getValues =
      try {
        clazz.getMethod("getValues")
      } catch (e: Throwable) {
        throw PreviewParameterLoadException(
          "$providerFqn has no getValues() — not a PreviewParameterProvider?",
          e,
        )
      }
    // Same package-private-class problem as the constructor above: a public `getValues()` declared
    // on a package-private class throws `IllegalAccessException` from `Method.invoke` unless the
    // member is opened up first. This is the crash in issue #2493.
    getValues.isAccessible = true
    return try {
      @Suppress("UNCHECKED_CAST")
      val sequence = getValues.invoke(instance) as? Sequence<Any?> ?: return emptyList()
      // `Sequence.take(Int).toList()` is the Kotlin stdlib contract — drives the sequence lazily up
      // to `limit` without requiring reflective access into package-private iterator
      // implementations (`kotlin.jvm.internal.ArrayIterator`, which `Method.invoke` rejects with
      // IllegalAccessException from outside the stdlib module).
      sequence.take(limit).toList()
    } catch (e: Throwable) {
      throw PreviewParameterLoadException("$providerFqn.getValues() failed", e)
    }
  }

  /**
   * Invokes [composableMethod]'s defaults-mask overload directly when [previewArgs] leaves a
   * parameter unseeded, returning true when it did the call — so a caller falls back to the
   * ordinary `ComposableMethod.invoke` for every shape this cannot safely drive.
   *
   * `ComposableMethod.invoke` cannot express a **partial** seed. It derives the mask from which
   * arguments are null *and* forwards those same nulls as the parameter values, so a null destined
   * for a primitive parameter reaches `Method.invoke` as a null `int` and throws
   * `IllegalArgumentException`. Trailing arguments it never receives are fine — those it pads by
   * type — so the failure appears exactly when something *after* an unseeded parameter is seeded,
   * which is precisely the shape a partial parameter-knob seed produces (`Button(label = …, enabled
   * = <default>, size = …)`).
   *
   * Doing it here keeps the semantics the knob format needs: an unseeded position contributes a set
   * bit to the mask *and* a type-appropriate zero as its placeholder, so the compiled default
   * expression runs and the author's default is what renders.
   *
   * The synthetic tail is reconstructed from the calling convention rather than assumed to be three
   * ints: a composable with more than [SLOTS_PER_COMPOSABLE_INT] real parameters carries a second
   * `changed` int, and one with more than [BITS_PER_DEFAULT_INT] carries a second `default` int.
   * Assuming the small shape would make this abstain on exactly the wide previews whose partial
   * seed then throws.
   *
   * Returns false for an instance method, for a method with no defaults mask, for a tail that does
   * not match the convention, and when there is no null to place — the shapes where the ordinary
   * invoke is already correct.
   */
  fun invokeWithDefaultMask(
    composableMethod: ComposableMethod,
    instance: Any?,
    previewArgs: List<Any?>,
    composer: androidx.compose.runtime.Composer,
  ): Boolean {
    if (instance != null) return false
    // Any null at all, not just an interior one: `ComposableMethod.invoke` pads only positions past
    // `previewArgs.size`, so a null *inside* the list — trailing or not — is forwarded verbatim.
    if (previewArgs.none { it == null }) return false
    val method = composableMethod.asMethod()
    if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) return false
    if (!method.hasComposableDefaults()) return false
    val types = method.parameterTypes
    val realParams = types.indexOfLast { it == androidx.compose.runtime.Composer::class.java }
    if (realParams <= 0 || previewArgs.size > realParams) return false
    val changedInts =
      ceil(realParams.toDouble() / SLOTS_PER_COMPOSABLE_INT).toInt().coerceAtLeast(1)
    val defaultInts = ceil(realParams.toDouble() / BITS_PER_DEFAULT_INT).toInt().coerceAtLeast(1)
    if (types.size != realParams + 1 + changedInts + defaultInts) return false

    val masks = IntArray(defaultInts)
    val arguments = arrayOfNulls<Any?>(types.size)
    for (i in 0 until realParams) {
      val seeded = previewArgs.getOrNull(i)
      if (seeded == null) {
        masks[i / BITS_PER_DEFAULT_INT] =
          masks[i / BITS_PER_DEFAULT_INT] or (1 shl (i % BITS_PER_DEFAULT_INT))
        arguments[i] = zeroValueForComposableParameter(types[i])
      } else {
        arguments[i] = seeded
      }
    }
    arguments[realParams] = composer
    for (c in 0 until changedInts) arguments[realParams + 1 + c] = 0
    for (d in 0 until defaultInts) arguments[realParams + 1 + changedInts + d] = masks[d]
    method.isAccessible = true
    method.invoke(null, *arguments)
    return true
  }

  /**
   * The placeholder an unseeded parameter is passed alongside its set mask bit. The compiled
   * default expression overwrites it, so the value never reaches the body — but the JVM still
   * requires one of the right type, which is the whole reason a null cannot be used here.
   */
  private fun zeroValueForComposableParameter(type: Class<*>): Any? =
    when (type) {
      Int::class.javaPrimitiveType -> 0
      Boolean::class.javaPrimitiveType -> false
      Long::class.javaPrimitiveType -> 0L
      Float::class.javaPrimitiveType -> 0f
      Double::class.javaPrimitiveType -> 0.0
      Short::class.javaPrimitiveType -> 0.toShort()
      Byte::class.javaPrimitiveType -> 0.toByte()
      Char::class.javaPrimitiveType -> '\u0000'
      else -> null
    }

  /**
   * Real parameters encoded per synthetic `default` int. The Compose compiler's `defaultParamCount`
   * packs 31 parameters per int (one bit each, the top bit unused), which is *not* the same rate as
   * the `changed` ints above — mixing the two silently corrupts the mask on a wide preview.
   */
  private const val BITS_PER_DEFAULT_INT = 31
}

/**
 * Thrown by [PreviewParameterSupport] on a hard provider-load failure so the caller can isolate the
 * failing preview instead of failing every preview that shares its shard / sandbox.
 */
class PreviewParameterLoadException(message: String, cause: Throwable?) :
  RuntimeException(message, cause)
