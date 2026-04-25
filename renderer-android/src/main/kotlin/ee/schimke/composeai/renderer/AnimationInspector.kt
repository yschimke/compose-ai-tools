@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.tooling.data.UiToolingDataApi::class,
)

package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.tooling.data.asTree
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge to Compose UI Tooling's animation-inspection surface — the same
 * one `ComposeViewAdapter` exposes to Android Studio's animation inspector.
 *
 * Three pieces glue together:
 *
 * 1. [SlotTreeCapture] — opaque holder of the active composition's
 *    [CompositionData]. The renderer creates one up front, hands it to
 *    [InspectablePreviewContent] inside `setContent`, and reads the slot
 *    table out of it after composition has run at least once.
 * 2. [InspectablePreviewContent] (composable) — calls
 *    `currentComposer.collectParameterInformation()` so the slot table
 *    captures the call-site parameter info `AnimationSearch` needs, then
 *    snapshots `currentComposer.compositionData` into the holder. Sidesteps
 *    `androidx.compose.ui.tooling.Inspectable` (internal in 1.10.6+) by
 *    going straight at the `@InternalComposeApi` Composer hooks Inspectable
 *    itself uses.
 * 3. [attach] — after composition settles, walks the captured
 *    [CompositionData], runs `AnimationSearch` against its slot tree, and
 *    returns an inspector that drives `PreviewAnimationClock` for sampling.
 *
 * The `PreviewAnimationClock` / `AnimationSearch` surface is `internal` to
 * compose-ui-tooling; `ComposeAnimation` / `ComposeAnimatedProperty` live
 * in `animation-tooling-internal`. All accessed via reflection so the
 * renderer compiles without taking a hard dep on the internal classes.
 * Compose UI Tooling 1.10.6+ is required; older versions miss the
 * required classes and are rejected at attach time with a clear message.
 */
internal class AnimationInspector private constructor(
    private val getMaxDuration: () -> Long,
    private val setClockTimeFn: (Long) -> Unit,
    private val getAnimations: () -> Set<Any>,
    private val getAnimatedProperties: (Any) -> List<AnimatedSample>,
    private val getAnimationLabel: (Any) -> String,
) {

    /**
     * One `(label, value)` reading from a Compose animation at a single
     * clock instant. `value` is the result of `ComposeAnimatedProperty.value`,
     * which can be any animation type (Float, Color, Dp, …); the plotter
     * coerces what it can to a Double via [coerceToDouble].
     */
    data class AnimatedSample(val label: String, val value: Any?)

    /** Total duration the inspector reports across all tracked animations, in ms. */
    val maxDurationMs: Long get() = getMaxDuration()

    fun setClockTime(timeMs: Long) = setClockTimeFn(timeMs)

    /**
     * Reads each tracked animation's animated properties at the current
     * clock time. Outer list: one entry per animation. Inner list: each
     * property the inspector exposes for that animation (e.g. a Transition
     * with multiple `animateFloat` children produces multiple samples).
     */
    fun snapshot(): List<TrackedAnimation> = getAnimations().map { anim ->
        TrackedAnimation(
            label = getAnimationLabel(anim),
            samples = getAnimatedProperties(anim),
        )
    }

    data class TrackedAnimation(val label: String, val samples: List<AnimatedSample>)

    companion object {
        private const val PREVIEW_ANIMATION_CLOCK_FQN =
            "androidx.compose.ui.tooling.animation.PreviewAnimationClock"
        private const val ANIMATION_SEARCH_FQN =
            "androidx.compose.ui.tooling.animation.AnimationSearch"
        // Lives in `androidx.compose.animation:animation-tooling-internal`,
        // NOT in compose-ui-tooling. PreviewAnimationClock.getAnimatedProperties
        // returns `List<ComposeAnimatedProperty>` from this package.
        private const val COMPOSE_ANIMATED_PROPERTY_FQN =
            "androidx.compose.animation.tooling.ComposeAnimatedProperty"

        /**
         * Construct an inspector against [capture], which must already have
         * been populated by the active composition (i.e. [InspectablePreviewContent]
         * wrapped the preview content and at least one frame has run).
         *
         * Returns `null` when the composition contained no animations the
         * inspector could discover (clean opt-out — `showCurves = true` on a
         * static preview); throws with a Compose-version diagnostic if the
         * tooling API isn't available.
         */
        fun attach(capture: SlotTreeCapture): AnimationInspector? {
            val clockClass = loadClass(PREVIEW_ANIMATION_CLOCK_FQN)
                ?: failNotInstalled(PREVIEW_ANIMATION_CLOCK_FQN)
            val searchClass = loadClass(ANIMATION_SEARCH_FQN)
                ?: failNotInstalled(ANIMATION_SEARCH_FQN)
            val animatedPropertyClass = loadClass(COMPOSE_ANIMATED_PROPERTY_FQN)
                ?: failNotInstalled(COMPOSE_ANIMATED_PROPERTY_FQN)

            val compositionData = capture.compositionData
            if (compositionData == null) {
                System.err.println(
                    "@AnimatedPreview(showCurves=true): composition didn't populate the slot " +
                        "table holder — did InspectablePreviewContent run? Falling back to GIF only.",
                )
                return null
            }
            val rootGroup: Any = compositionData.asTree()
            val slotTrees = listOf(rootGroup)

            // PreviewAnimationClock(setAnimationsTimeCallback: () -> Unit).
            // Some Compose versions also overload to accept Function1<Long, Unit>;
            // we look up by arity rather than exact type to absorb that drift.
            val clockCtor = clockClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 &&
                    kotlin.jvm.functions.Function0::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.also { it.isAccessible = true }
                ?: error(
                    "PreviewAnimationClock has no single-arg Function0 constructor — Compose UI " +
                        "Tooling version is incompatible with @AnimatedPreview(showCurves=true).",
                )
            val clock = clockCtor.newInstance(NO_OP_CALLBACK)

            // AnimationSearch(clock: () -> PreviewAnimationClock, onSeek: () -> Unit)
            // is internal in compose-ui-tooling.
            val clockProvider = Function0Adapter { clock }
            val onSeek = Function0Adapter { Unit }
            val search = searchClass
                .getDeclaredConstructor(
                    kotlin.jvm.functions.Function0::class.java,
                    kotlin.jvm.functions.Function0::class.java,
                )
                .also { it.isAccessible = true }
                .newInstance(clockProvider, onSeek)

            val searchAny = searchClass.declaredMethods.firstOrNull {
                it.name == "searchAny" && it.parameterTypes.size == 1 &&
                    Collection::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.also { it.isAccessible = true }
                ?: error(
                    "AnimationSearch.searchAny(Collection) not found — Compose UI Tooling " +
                        "version is incompatible with @AnimatedPreview(showCurves=true).",
                )
            val attachAllAnimations = searchClass.declaredMethods.firstOrNull {
                it.name == "attachAllAnimations" && it.parameterTypes.size == 1 &&
                    Collection::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.also { it.isAccessible = true }

            val anyFound = (searchAny.invoke(search, slotTrees) as? Boolean) ?: false
            if (!anyFound) {
                System.err.println(
                    "@AnimatedPreview(showCurves=true): no animations discovered in composition.",
                )
                return null
            }
            attachAllAnimations?.invoke(search, slotTrees)

            // PreviewAnimationClock public API:
            //   getMaxDuration(): Long
            //   setClockTime(animationTimeMs: Long): Unit
            //   getAnimatedProperties(animation: ComposeAnimation): List<ComposeAnimatedProperty>
            //
            // Tracked animations live across several typed clock maps
            // (transitions / animatedVisibility / animateXAsState / infinite /
            // animatedContent), each exposed via a `get*Clocks$ui_tooling()`
            // accessor — JVM-public, Kotlin-`internal`. We gather their map
            // keys (which ARE the ComposeAnimation instances) into one Set.
            val getMaxDurationMethod = clockClass.getMethod("getMaxDuration")
            val setClockTimeMethod = findSetClockTimeMethod(clockClass)
            val getAnimatedPropertiesMethod = findGetAnimatedPropertiesMethod(clockClass)
            val animationKeyAccessors = clockClass.declaredMethods
                .filter { m ->
                    val name = m.name
                    m.parameterTypes.isEmpty() &&
                        java.util.Map::class.java.isAssignableFrom(m.returnType) &&
                        (name.startsWith("get") && name.endsWith("Clocks\$ui_tooling"))
                }
                .onEach { it.isAccessible = true }
            val getTrackedUnsupported = runCatching {
                clockClass.getMethod("getTrackedUnsupportedAnimations")
            }.getOrNull()

            val labelMethod = animatedPropertyClass.getMethod("getLabel")
            val valueMethod = animatedPropertyClass.getMethod("getValue")

            return AnimationInspector(
                getMaxDuration = { getMaxDurationMethod.invoke(clock) as Long },
                setClockTimeFn = { ms -> setClockTimeMethod.invoke(clock, ms) },
                getAnimations = {
                    val collected = LinkedHashSet<Any>()
                    for (accessor in animationKeyAccessors) {
                        val map = accessor.invoke(clock) as? Map<*, *> ?: continue
                        for (key in map.keys) {
                            if (key != null) collected += key
                        }
                    }
                    if (getTrackedUnsupported != null) {
                        val unsupported = getTrackedUnsupported.invoke(clock) as? Set<*>
                        if (unsupported != null) {
                            for (item in unsupported) {
                                if (item != null) collected += item
                            }
                        }
                    }
                    collected
                },
                getAnimatedProperties = { anim ->
                    @Suppress("UNCHECKED_CAST")
                    val props = getAnimatedPropertiesMethod.invoke(clock, anim) as List<Any>
                    props.map { p ->
                        AnimatedSample(
                            label = labelMethod.invoke(p) as String,
                            value = valueMethod.invoke(p),
                        )
                    }
                },
                getAnimationLabel = { anim -> readAnimationLabel(anim) },
            )
        }

        private fun failNotInstalled(missingClass: String): Nothing {
            error(
                "@AnimatedPreview(showCurves=true) requires Compose UI Tooling 1.10.6+ on the " +
                    "test classpath. Missing class: $missingClass.\n" +
                    "  Add to the consumer module's `dependencies { … }`:\n" +
                    "    testImplementation(\"androidx.compose.ui:ui-tooling\")\n" +
                    "    testImplementation(\"androidx.compose.animation:animation-tooling-internal\")\n" +
                    "  (Both are required — ui-tooling exposes PreviewAnimationClock; " +
                    "animation-tooling-internal exposes ComposeAnimation / ComposeAnimatedProperty " +
                    "which the clock returns. The compose-bom pins matching versions.)",
            )
        }

        private fun findSetClockTimeMethod(clockClass: Class<*>): Method =
            runCatching { clockClass.getMethod("setClockTime", java.lang.Long.TYPE) }
                .getOrElse {
                    clockClass.declaredMethods.firstOrNull {
                        (it.name == "setClockTime" || it.name == "setAnimationsTime") &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == java.lang.Long.TYPE
                    }?.also { it.isAccessible = true }
                        ?: error(
                            "PreviewAnimationClock has no setClockTime(Long) — Compose UI Tooling " +
                                "version is incompatible with @AnimatedPreview(showCurves=true).",
                        )
                }

        private fun findGetAnimatedPropertiesMethod(clockClass: Class<*>): Method =
            clockClass.declaredMethods.firstOrNull {
                it.name == "getAnimatedProperties" && it.parameterTypes.size == 1
            }?.also { it.isAccessible = true }
                ?: error(
                    "PreviewAnimationClock has no getAnimatedProperties(...) — Compose UI Tooling " +
                        "version is incompatible with @AnimatedPreview(showCurves=true).",
                )

        private fun readAnimationLabel(anim: Any): String {
            val labelMethod = runCatching { anim.javaClass.getMethod("getLabel") }.getOrNull()
            val raw = labelMethod?.invoke(anim) as? String
            return raw?.takeIf { it.isNotBlank() } ?: anim.javaClass.simpleName
        }

        private fun loadClass(fqn: String): Class<*>? = runCatching { Class.forName(fqn) }.getOrNull()

        private val NO_OP_CALLBACK = object : kotlin.jvm.functions.Function0<Unit> {
            override fun invoke() = Unit
        }
    }

    private class Function0Adapter<T>(private val produce: () -> T) : kotlin.jvm.functions.Function0<T> {
        override fun invoke(): T = produce()
    }
}

/**
 * Renderer-side holder for a [CompositionData] reference captured during
 * composition. The active composition writes itself in via
 * [InspectablePreviewContent]; [AnimationInspector.attach] reads it back
 * to walk the slot tree.
 */
internal class SlotTreeCapture {
    private val ref = AtomicReference<CompositionData?>()
    var compositionData: CompositionData?
        get() = ref.get()
        set(value) {
            ref.set(value)
        }
}

/**
 * Wraps [content] in a tiny composable that snapshots the active
 * composition's slot table into [capture]. Replaces the `Inspectable(...)`
 * wrapper that compose-ui-tooling marks `internal`. The two operations
 * — `collectParameterInformation()` + capturing `compositionData` — are
 * what `Inspectable` itself does, exposed through the
 * `@InternalComposeApi`-annotated (but Kotlin-public) Composer surface.
 *
 * The renderer enters this branch only when at least one capture on the
 * preview asks for `showCurves = true`; otherwise the plain content is
 * composed without the parameter-info collection overhead.
 */
@Composable
internal fun InspectablePreviewContent(
    capture: SlotTreeCapture,
    content: @Composable () -> Unit,
) {
    currentComposer.collectParameterInformation()
    capture.compositionData = currentComposer.compositionData
    content()
}

/**
 * Best-effort coercion of a Compose animation value to a plottable Double.
 * Floats / Ints / Longs / Booleans go straight; `Color` objects use a
 * stable hash code; `Dp` / unit-bearing Float wrappers use their `.value`
 * field. Returns `null` when the value can't be reduced — the plotter
 * draws a label-only legend entry rather than a curve in that case.
 */
internal fun coerceToDouble(value: Any?): Double? = when (value) {
    null -> null
    is Number -> value.toDouble()
    is Boolean -> if (value) 1.0 else 0.0
    else -> {
        runCatching {
            val v = value::class.java.getMethod("getValue").invoke(value)
            (v as? Number)?.toDouble()
        }.getOrNull() ?: runCatching {
            value.hashCode().toDouble()
        }.getOrNull()
    }
}
