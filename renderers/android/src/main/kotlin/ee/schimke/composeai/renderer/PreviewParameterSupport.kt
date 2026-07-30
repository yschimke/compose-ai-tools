package ee.schimke.composeai.renderer

import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod

/**
 * Reflective `@PreviewParameter` support shared by the two Android render bodies — `:renderer
 * -android`'s [RobolectricRenderTest] (which fans a provider out into one render per value) and
 * `:daemon:android`'s `RenderEngine` (which renders a single frame and therefore takes the first
 * value only).
 *
 * **Why the provider FQN is passed in rather than read off the method.** The upstream
 * `androidx.compose.ui.tooling.preview.PreviewParameter` annotation has `AnnotationRetention.BINARY`
 * — it is written into the class file but is *not* visible through `Method.parameterAnnotations` at
 * runtime, exactly like `@PreviewWrapper` (issue #1440). The gradle plugin's discovery reads it off
 * the class-file annotation tables into `previews.json`
 * (`params.previewParameterProviderClassName` / `params.previewParameterLimit`), and both render
 * bodies get it from there. Nothing here can recover it by reflecting on the composable.
 *
 * **What went wrong without this (issue #3027).** The daemon resolved every preview with the
 * parameterless `getDeclaredComposableMethod(functionName)` lookup, which matches only
 * `foo(Composer, int)`. A preview declaring `@PreviewParameter` compiles to
 * `foo(<T>, Composer, int)`, so the lookup threw
 * `NoSuchMethodException: <class>.<function>` before composition ever started — no PNG, no
 * semantics, just a `.error.json`. Observed across 27 previews in one consumer module.
 */
object PreviewParameterSupport {

  /** A resolved preview entrypoint: the composable method plus the arguments to invoke it with. */
  data class Resolved(val method: ComposableMethod, val args: List<Any?>)

  /**
   * Resolves the composable entrypoint for a preview, supplying `@PreviewParameter` arguments when
   * the discovery manifest recorded a provider.
   *
   * [providerClassName] `null` (the common case) is the plain parameterless lookup. When a provider
   * is named, its first value is used: a single-frame renderer has one output file per preview id,
   * and the daemon's manifest ids are the un-suffixed base ids the fan-out renderer would render as
   * `<id>_<label>`. Value 0 is the one Android Studio shows first as well.
   *
   * The returned method is always opened for reflective invocation ([openForInvoke]) — Kotlin
   * `private fun` previews are idiomatic and resolve fine, but invoking one without that throws
   * `IllegalAccessException`. Doing it here rather than at each call site is what keeps the daemon's
   * scroll / `figma-svg-long` / held-session paths from each having to remember.
   */
  fun resolve(
    clazz: Class<*>,
    functionName: String,
    providerClassName: String?,
    limit: Int = Int.MAX_VALUE,
    classLoader: ClassLoader? = null,
  ): Resolved {
    if (providerClassName.isNullOrBlank()) {
      return Resolved(clazz.getDeclaredComposableMethod(functionName).openForInvoke(), emptyList())
    }
    if (limit <= 0) {
      throw PreviewParameterLoadException(
        "@PreviewParameter(provider = $providerClassName, limit = $limit) on $functionName " +
          "leaves no value to render",
        null,
      )
    }
    // Only the first value is needed; `take(1)` also keeps an infinite `generateSequence` provider
    // from being driven to exhaustion.
    val values = loadValues(providerClassName, limit = 1, classLoader = classLoader)
    if (values.isEmpty()) {
      throw PreviewParameterLoadException(
        "@PreviewParameter(provider = $providerClassName) on $functionName produced no values",
        null,
      )
    }
    val args = listOf(values.first())
    return Resolved(findComposableMethodWithArgs(clazz, functionName, args).openForInvoke(), args)
  }

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
}

/**
 * Thrown by [PreviewParameterSupport] on a hard provider-load failure so the caller can isolate the
 * failing preview instead of failing every preview that shares its shard / sandbox.
 */
class PreviewParameterLoadException(message: String, cause: Throwable?) :
  RuntimeException(message, cause)
