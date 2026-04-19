package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.reflect.getDeclaredComposableMethod

/**
 * Produces the composition body for a single [RenderPreviewEntry]. Selection
 * happens through [strategyFor] — driven by the [PreviewKind] recorded at
 * discovery time.
 *
 * Each strategy owns its own reflection + framing logic so the main Robolectric
 * pipeline stays oblivious to whether it's driving a @Composable or a tile.
 */
internal interface PreviewRenderStrategy {
    @Composable
    fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int, previewArgs: List<Any?>)
}

private val STRATEGIES: Map<PreviewKind, PreviewRenderStrategy> = mapOf(
    PreviewKind.COMPOSE to ComposePreviewStrategy,
    PreviewKind.TILE to TilePreviewStrategy,
)

internal fun strategyFor(kind: PreviewKind): PreviewRenderStrategy =
    STRATEGIES[kind] ?: error("No render strategy registered for PreviewKind.$kind")

/**
 * Default strategy: reflect the `@Composable` and invoke it through the
 * Composer. Honours `@PreviewWrapper` by looking up the provider's
 * `Wrap(content)` method.
 *
 * No `Box(Modifier.fillMaxSize().background(bgColor)) { ... }` wrapper —
 * [RobolectricRenderTestBase.renderDefault] paints the background on the
 * activity window before `setContent`, so we don't need to emit layout-node
 * bytecode (`ComposeUiNode.setCompositeKeyHash` etc.) here. That's what
 * keeps the renderer runnable against older compose-ui BOMs (see the
 * commentary in `renderDefault` for the full compat story).
 */
private object ComposePreviewStrategy : PreviewRenderStrategy {
    @Composable
    override fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int, previewArgs: List<Any?>) {
        val clazz = Class.forName(preview.className)
        // For previews with a `@PreviewParameter` argument, look up the
        // overload whose Composable-visible parameters match the supplied
        // values. The pipeline only injects one value today (Studio-parity:
        // multi-@PreviewParameter functions aren't supported), but passing
        // the full list keeps the lookup shape honest with the invocation.
        val composableMethod = if (previewArgs.isEmpty()) {
            clazz.getDeclaredComposableMethod(preview.functionName)
        } else {
            findComposableMethodWithArgs(clazz, preview.functionName, previewArgs)
        }
        // Top-level `@Preview` functions compile into static methods on the
        // file's synthetic `FooKt` class, so `receiver = null` works. Google's
        // `com.android.compose.screenshot` tool (and Paparazzi-style tests)
        // idiomatically wrap previews in a regular `class ScreenshotTest { ... }`
        // — `SessionDetailsPreview` is then an instance method and invoking
        // with a null receiver throws `NullPointerException: Cannot invoke
        // "Object.getClass()" because "obj" is null` inside
        // `ComposableMethod.invoke`. Mirror how Compose tooling's
        // `ComposeViewAdapter` resolves the receiver: prefer the Kotlin
        // `object` singleton (INSTANCE), else instantiate via the nullary
        // constructor, else fall back to null for static methods.
        val receiver = resolvePreviewReceiver(clazz)
        val body: @Composable () -> Unit = {
            composableMethod.invoke(currentComposer, receiver, *previewArgs.toTypedArray())
        }
        val wrapperFqn = preview.params.wrapperClassName
        if (wrapperFqn != null) {
            val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
            resolved.first.invoke(currentComposer, resolved.second, body)
        } else {
            body()
        }
    }
}

/**
 * Resolves the `ComposableMethod` for a preview function that declares
 * `@PreviewParameter` arguments, where parameter types aren't known
 * statically. Walks `declaredMethods`, picks the overload whose leading
 * JVM parameter types line up with `previewArgs` (receiver types match the
 * runtime class of each value, plus the usual trailing Composer + changed
 * int-bits), then hands that shape to
 * `Class<*>.getDeclaredComposableMethod(name, vararg parameterTypes)` —
 * the only officially supported way to produce a `ComposableMethod`.
 *
 * Null entries in [previewArgs] are matched against the declared parameter's
 * box type (Kotlin nullable types already compile to boxed reference types).
 * Primitive-typed non-null values are auto-boxed in [previewArgs], so we
 * check both box and primitive forms.
 */
internal fun findComposableMethodWithArgs(
    clazz: Class<*>,
    name: String,
    previewArgs: List<Any?>,
): androidx.compose.runtime.reflect.ComposableMethod {
    val argCount = previewArgs.size
    // Compose compiler emits `(…args, Composer, changed[, defaultBits…])`
    // at the JVM level, so a method with N composable-visible params has at
    // least N + 2 JVM params. The default-bits tail is emitted when the
    // preview function declares default arguments we didn't supply.
    val candidate = clazz.declaredMethods.firstOrNull { m ->
        m.name == name && m.parameterCount >= argCount + 2 && argsMatch(m, previewArgs)
    } ?: throw NoSuchMethodException(
        "Couldn't find composable method $name on ${clazz.name} taking ${previewArgs.size} parameter(s); " +
            "check that the @PreviewParameter provider's value type matches the preview's parameter type.",
    )
    val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
    return clazz.getDeclaredComposableMethod(name, *declaredTypes)
}

private fun argsMatch(method: java.lang.reflect.Method, previewArgs: List<Any?>): Boolean {
    for ((i, arg) in previewArgs.withIndex()) {
        val expected = method.parameterTypes[i]
        if (arg == null) {
            // A null argument can satisfy any reference parameter; a primitive
            // JVM parameter can't accept null, so it's an immediate mismatch.
            if (expected.isPrimitive) return false
            continue
        }
        val actual = arg.javaClass
        if (expected.isAssignableFrom(actual)) continue
        // Auto-boxing: `int` vs `Integer`, etc. `expected.kotlin.javaObjectType`
        // is the box class for primitives; for reference types it's itself.
        if (expected.kotlin.javaObjectType.isAssignableFrom(actual)) continue
        return false
    }
    return true
}

/**
 * Resolves the JVM receiver instance to pass into
 * `ComposableMethod.invoke(composer, receiver, …)` for a preview function
 * declared on [clazz]. Extracted as a top-level internal function so
 * [PreviewReceiverTest] can exercise it without standing up a Robolectric
 * sandbox. Returns:
 *  - the `INSTANCE` field of a Kotlin `object` (singleton receiver);
 *  - a fresh nullary-ctor instance for regular classes (Google's
 *    `com.android.compose.screenshot` style: `class ScreenshotTest { @Preview fun …}`);
 *  - `null` for top-level functions — those compile into static methods on
 *    the file's synthetic `FooKt` class, and `ComposableMethod.invoke`
 *    accepts a null receiver for static methods.
 *
 * Matches how Compose tooling's `ComposeViewAdapter` resolves receivers in
 * the Studio preview pane.
 */
internal fun resolvePreviewReceiver(clazz: Class<*>): Any? {
    runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()?.let { return it }
    // Regular class: instantiate via nullary ctor. `setAccessible(true)` so
    // private/internal classes work too (Google's screenshotTest classes
    // are typically package-private or internal).
    return runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    }.getOrNull()
}

/**
 * Tiles strategy: invoke the non-composable preview function, drive the
 * returned [androidx.wear.tiles.tooling.preview.TilePreviewData] through
 * `TileRenderer`, and host the inflated View via an `AndroidView`. See
 * [TilePreviewComposable] for the heavy lifting.
 */
private object TilePreviewStrategy : PreviewRenderStrategy {
    @Composable
    override fun Render(preview: RenderPreviewEntry, widthDp: Int, heightDp: Int, previewArgs: List<Any?>) {
        // `@PreviewParameter` doesn't apply to tile previews — discovery
        // drops the provider FQN for `PreviewKind.TILE`, so this list is
        // always empty here.
        TilePreviewComposable(preview, widthDp, heightDp)
    }
}
