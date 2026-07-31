package ee.schimke.composeai.renderer

import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod

/**
 * Reflective `@PreviewParameter` support for the **desktop daemon** render body
 * (`:daemon:desktop`'s `RenderEngine`), which renders a single frame and therefore takes the
 * provider's first value only. The desktop equivalent of `:renderer-android`'s
 * [ee.schimke.composeai.renderer.PreviewParameterSupport]; the two artefacts keep independent
 * copies on purpose so neither renderer takes a shared-module dependency (see
 * [DesktopRendererMain]'s own `loadProviderValues` / `findComposableMethodWithArgs`, which the
 * standalone renderer's fan-out still uses — those soft-fail per row so one bad value can't sink a
 * batch, whereas the daemon's single-frame [resolve] hard-fails so the caller can surface one
 * preview's `.error.json`).
 *
 * **Why the provider FQN is passed in rather than read off the method.** The upstream
 * `androidx.compose.ui.tooling.preview.PreviewParameter` annotation has
 * `AnnotationRetention.BINARY` — it is written into the class file but is not visible through
 * `Method.parameterAnnotations` at runtime. The gradle plugin's discovery reads it off the
 * class-file annotation tables into `previews.json` (`params.previewParameterProviderClassName` /
 * `params.previewParameterLimit`), and both render bodies get it from there. Nothing here can
 * recover it by reflecting on the composable.
 *
 * **What went wrong without this.** The desktop daemon resolved every preview with the
 * parameterless `getDeclaredComposableMethod(functionName)` lookup, which matches only
 * `foo(Composer, int)`. A preview declaring `@PreviewParameter` compiles to `foo(<T>, Composer,
 * int)`, so the lookup threw `NoSuchMethodException` before composition ever started — no PNG, no
 * semantics, just a `.error.json`. This is the desktop counterpart of the Android fix in
 * issue #3027, and it is what let `bundle pack --with-semantics` drop a CMP/desktop fan-out's a11y
 * tree entirely.
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
   * `IllegalAccessException`.
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
   * succeeds for public/internal previews) rather than failing resolution outright.
   */
  private fun ComposableMethod.openForInvoke(): ComposableMethod = also {
    runCatching { it.asMethod().isAccessible = true }
  }

  /**
   * Enumerate a `PreviewParameterProvider`'s values reflectively, up to [limit].
   *
   * Throws [PreviewParameterLoadException] on any hard failure (class missing, no no-arg
   * constructor, missing/throwing `getValues()`) so the caller can isolate the failing preview and
   * surface it as a per-preview error card. Returns an empty list only when the provider
   * legitimately yields no values.
   *
   * [classLoader] is the loader the consumer's classes live on — the daemon hands in its per-swap
   * child loader; leave it null to use `Class.forName`'s default (the caller's / context loader).
   */
  fun loadValues(providerFqn: String, limit: Int, classLoader: ClassLoader? = null): List<Any?> {
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
        // this package without opening it up first.
        ctor.isAccessible = true
        ctor.newInstance()
      } catch (e: Throwable) {
        throw PreviewParameterLoadException(
          "couldn't instantiate $providerFqn via its no-arg constructor",
          e,
        )
      }
    // `PreviewParameterProvider<T>` exposes `values: Sequence<T>` — its JVM signature is
    // `getValues(): Sequence`. Look it up by name to avoid a compile-time dependency on the
    // provider
    // interface (which lives in the consumer's Compose artifact).
    val getValues =
      try {
        clazz.getMethod("getValues")
      } catch (e: Throwable) {
        throw PreviewParameterLoadException(
          "$providerFqn has no getValues() — not a PreviewParameterProvider?",
          e,
        )
      }
    // Same package-private-class problem as the constructor: a public `getValues()` on a
    // package-private class throws `IllegalAccessException` from `Method.invoke` unless opened up.
    getValues.isAccessible = true
    return try {
      @Suppress("UNCHECKED_CAST")
      val sequence = getValues.invoke(instance) as? Sequence<Any?> ?: return emptyList()
      // `Sequence.take(Int).toList()` drives the sequence lazily up to `limit` without reflective
      // access into package-private iterator implementations (which `Method.invoke` rejects).
      sequence.take(limit).toList()
    } catch (e: Throwable) {
      throw PreviewParameterLoadException("$providerFqn.getValues() failed", e)
    }
  }

  /**
   * Resolve the `foo(<T>, Composer, int)` overload of a `@PreviewParameter` preview given the
   * argument list to bind. Mirrors [DesktopRendererMain]'s local `findComposableMethodWithArgs`
   * (kept in sync by hand) — scans `declaredMethods` for the name whose leading parameters match
   * the provided args, then resolves the `ComposableMethod` for that concrete signature.
   */
  fun findComposableMethodWithArgs(
    clazz: Class<*>,
    name: String,
    previewArgs: List<Any?>,
  ): ComposableMethod {
    val argCount = previewArgs.size
    val candidate =
      clazz.declaredMethods.firstOrNull { m ->
        m.name == name && m.parameterCount >= argCount + 2 && argsMatch(m, previewArgs)
      }
        ?: throw NoSuchMethodException(
          "Couldn't find composable method $name on ${clazz.name} taking $argCount parameter(s); " +
            "check that the @PreviewParameter provider's value type matches the preview's parameter type."
        )
    val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
    return clazz.getDeclaredComposableMethod(name, *declaredTypes)
  }

  private fun argsMatch(method: java.lang.reflect.Method, previewArgs: List<Any?>): Boolean {
    for ((i, arg) in previewArgs.withIndex()) {
      val expected = method.parameterTypes[i]
      if (arg == null) {
        if (expected.isPrimitive) return false
        continue
      }
      val actual = arg.javaClass
      if (expected.isAssignableFrom(actual)) continue
      if (expected.kotlin.javaObjectType.isAssignableFrom(actual)) continue
      return false
    }
    return true
  }
}

/**
 * Thrown by [PreviewParameterSupport] on a hard provider-load failure so the caller can isolate the
 * failing preview instead of failing every preview that shares its shard / sandbox.
 */
class PreviewParameterLoadException(message: String, cause: Throwable?) :
  RuntimeException(message, cause)
