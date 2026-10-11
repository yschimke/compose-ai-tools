package ee.schimke.composeai.discovery

import androidx.compose.runtime.Composable
import ee.schimke.composeai.preview.KnobValue

// Fixtures for ComposableSignatureTest. Not @Composable, which would need the Compose runtime;
// ComposableSignature only reads Kotlin metadata. Top-level functions exercise the
// `SignatureFixturesKt` file-facade path.
@Suppress("unused", "UNUSED_PARAMETER")
fun sampleComponent(
  state: String,
  count: Int = 3,
  labels: List<String>,
  onClick: () -> Unit,
  note: String? = null,
) {}

@Suppress("unused", "UNUSED_PARAMETER")
fun scopedSlotComponent(content: @Composable TestRowScope.(Int) -> Unit) {}

class TestRowScope

/**
 * The `LazyColumn` shape: a non-`@Composable` receiver lambda whose children are declared through
 * the receiver.
 */
@Suppress("unused", "UNUSED_PARAMETER") fun scopeDslComponent(content: TestListScope.() -> Unit) {}

/** An ordinary callback, to prove the signal is about the receiver and not about being a lambda. */
@Suppress("unused", "UNUSED_PARAMETER") fun callbackComponent(onValueChange: (String) -> Unit) {}

/** The shape a determinate progress indicator has: a lambda returning a value. */
@Suppress("unused", "UNUSED_PARAMETER") fun valueReturningLambdaComponent(progress: () -> Float) {}

/** The same return type behind an argument, which a bare `{ … }` must not be accepted for. */
@Suppress("unused", "UNUSED_PARAMETER")
fun argumentTakingLambdaComponent(measure: (Int) -> Float) {}

class TestListScope

@Suppress("unused") fun noParams() {}

/**
 * Every parameter defaulted, like production composables annotated in place; discovery admits
 * these.
 */
@Suppress("unused", "UNUSED_PARAMETER")
fun allDefaultedComponent(modifier: String = "", count: Int = 1) {}

// --- Knob fixtures (the secondary override format) -----------------------------------------

/** Every parameter defaulted and seedable: all six become knobs, in declaration order. */
@Suppress("unused", "UNUSED_PARAMETER")
fun knobComponent(
  label: String = "Filled",
  enabled: Boolean = true,
  count: Int = 3,
  big: Long = 4L,
  ratio: Float = 0.5f,
  precise: Double = 1.5,
) {}

/**
 * `modifier` isn't seedable, so only `count` is a knob, indexed by its position in the full list.
 */
@Suppress("unused", "UNUSED_PARAMETER")
fun mixedKnobComponent(modifier: List<String> = emptyList(), count: Int = 1) {}

/** The closed-value-set knob: an `enum class` parameter, defaulted to one of its constants. */
enum class Emphasis {
  Filled,
  Tonal,
  Outlined,
}

/**
 * Constants whose seed text differs from their name, including `extra-large`, which no identifier
 * can spell.
 */
enum class KitIconSize {
  @KnobValue("default") Default,
  @KnobValue("large") Large,
  @KnobValue("extra-large") ExtraLarge,
}

/** Two constants claiming one seed text — an ambiguous seed, which must disable the knob. */
enum class AmbiguousChoice {
  @KnobValue("same") First,
  @KnobValue("same") Second,
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun aliasedKnobComponent(iconSize: KitIconSize = KitIconSize.ExtraLarge) {}

@Composable
@Suppress("UNUSED_PARAMETER")
fun ambiguousKnobComponent(choice: AmbiguousChoice = AmbiguousChoice.First, tag: String = "t") {}

@Composable
@Suppress("UNUSED_PARAMETER")
fun enumKnobComponent(emphasis: Emphasis = Emphasis.Tonal, label: String = "hi") {}

/** A nullable knob type is excluded: `null` is how the renderer says "take the author default". */
@Suppress("unused", "UNUSED_PARAMETER")
fun nullableKnobComponent(label: String? = null, enabled: Boolean = true) {}

// The material3 `Checkbox(onCheckedChange: ((Boolean) -> Unit)?)` shape.
@Suppress("unused")
fun nullableCallbackComponent(onCheckedChange: ((Boolean) -> Unit)?, onClick: (() -> Unit)?) {}

// --- Opt-in marker fixtures ------------------------------------------------------------------

/** An author-written opt-in marker, the shape `@ExperimentalMaterial3Api` has. */
@RequiresOptIn("Fixture-only marker.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class ExperimentalFixtureApi

/** An author-written marker guarding internals, the shape `@InternalComposeApi` has. */
@RequiresOptIn("Fixture-only marker.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class InternalFixtureApi

/**
 * A marker that isn't itself an opt-in requirement but is guarded by one, like
 * `@ComposableInferredTarget`; reading the meta-annotation closure would wrongly report
 * [InternalFixtureApi].
 */
@InternalFixtureApi
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class FixtureInferredTarget

/** The trap: one real marker, one compiler-shaped marker that must not contribute its own. */
@OptIn(InternalFixtureApi::class)
@ExperimentalFixtureApi
@FixtureInferredTarget
@Suppress("unused", "UNUSED_PARAMETER")
fun optInComponent(label: String) {}

/** An author opting *their own* declaration into internals — the caller really must opt in. */
@Suppress("unused", "UNUSED_PARAMETER") @InternalFixtureApi fun deliberatelyInternalComponent() {}

/**
 * A marker declared inside a class, so its binary name carries a `$` the source spelling has not.
 */
class MarkerHolder {
  @RequiresOptIn("Fixture-only marker.")
  @Retention(AnnotationRetention.BINARY)
  @Target(AnnotationTarget.FUNCTION)
  annotation class NestedApi
}

@Suppress("unused") @MarkerHolder.NestedApi fun nestedMarkerComponent() {}

/** A top-level marker whose backticked name legitimately contains a `$`. */
@RequiresOptIn("Fixture-only marker.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class `Api$Experimental`

@Suppress("unused") @`Api$Experimental` fun dollarMarkerComponent() {}

// No context-receiver fixture: this module's language version can't express either spelling. The
// refusal is covered in ComponentSnippetsTest; ComposableSignature.hasContextRequirement notes the
// gap.

/**
 * A type-aliased parameter. Metadata expands aliases (see `KmType.abbreviatedType`), so the
 * recorded classifier is the aliased class; asserted because a review claimed otherwise.
 */
typealias AliasedLabel = String

@Suppress("unused", "UNUSED_PARAMETER") fun aliasedComponent(label: AliasedLabel = "") {}

// --- Constructibility fixtures (#5067) ---
// One per clause of `ComposableSignature.isNoArgConstructible`, checked against real compiler
// output: an all-defaulted constructor is zero-arg only in source (the JVM sees a
// `DefaultConstructorMarker` bridge).

/** The `TextFieldState` shape: all constructor parameters defaulted. */
class DefaultedState(val text: String = "", val cursor: Int = 0)

/** No constructor parameters at all — the other way to be callable with none. */
@Suppress("unused") class EmptyState

/** A required constructor parameter. `RequiredArgState()` does not compile, so it is refused. */
@Suppress("unused") class RequiredArgState(val text: String)

/** Abstract: has a constructor, cannot be instantiated. */
@Suppress("unused") abstract class AbstractState(val text: String = "")

/** An `object` is referenced as `SingletonState`, never called as `SingletonState()`. */
@Suppress("unused") object SingletonState

/**
 * Generic: `GenericState()` leaves `T` uninferable, the same reason a generic composable refuses.
 */
@Suppress("unused") class GenericState<T>(val items: List<T> = emptyList())

/** Value class: its constructor is name-mangled, so the JVM signature is not what source calls. */
@Suppress("unused") @JvmInline value class ValueState(val text: String = "")

/** `Inner()` needs an outer instance, which a generated file has no way to produce. */
@Suppress("unused")
class OuterHost {
  inner class InnerState(val text: String = "")
}

/** Not public: a generated file in another package cannot name it. */
@Suppress("unused") internal class InternalState(val text: String = "")

/** A marker that guards a TYPE, which the function-targeted fixture markers above cannot. */
@RequiresOptIn("Fixture-only marker.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ExperimentalStateApi

/**
 * Constructible in every other way, but declaring one requires a marker the call site cannot carry.
 */
@Suppress("unused") @ExperimentalStateApi class GatedState(val text: String = "")

/**
 * The wiring case: a required parameter whose type is constructible, read through `signatureOf`.
 */
@Suppress("unused", "UNUSED_PARAMETER") fun defaultedStateComponent(state: DefaultedState) {}

/** A defaulted parameter is omitted from the call, so nothing is constructed for it. */
@Suppress("unused", "UNUSED_PARAMETER")
fun defaultedParameterComponent(state: DefaultedState = DefaultedState()) {}

// --- `remember…` factory fixtures ---
// One per clause of `ComposableSignature.noArgFactoryFor`; each is a real function accepted or
// rejected on its declared shape, so a name-only resolver would fail.

/** The `rememberTextFieldState` shape: `@Composable`, fully defaulted, returning its type. */
@Suppress("unused")
@Composable
fun rememberDefaultedState(text: String = "", cursor: Int = 0): DefaultedState =
  DefaultedState(text, cursor)

/** A type whose package ships no factory at all. Its constructor is the only answer. */
@Suppress("unused") class FactorylessState(val text: String = "")

/** Named right and shaped right, but not `@Composable` — so it is not the convention. */
@Suppress("unused") class PlainFactoryState(val text: String = "")

@Suppress("unused")
fun rememberPlainFactoryState(text: String = ""): PlainFactoryState = PlainFactoryState(text)

/** Named right, but takes a value nothing here can supply, so `remember…()` does not compile. */
@Suppress("unused") class RequiredFactoryState(val text: String = "")

@Suppress("unused")
@Composable
fun rememberRequiredFactoryState(text: String): RequiredFactoryState = RequiredFactoryState(text)

/** Named right, returns something else: a name collision, not a factory for this type. */
@Suppress("unused") class MismatchedFactoryState(val text: String = "")

@Suppress("unused")
@Composable
fun rememberMismatchedFactoryState(): DefaultedState = DefaultedState()

/** Named right, but gated: emitting the call would need a marker the wrapper cannot carry. */
@Suppress("unused") class GatedFactoryState(val text: String = "")

@Suppress("unused")
@Composable
@ExperimentalFixtureApi
fun rememberGatedFactoryState(): GatedFactoryState = GatedFactoryState()

/** The wiring case: a required parameter whose type has a factory, read through `signatureOf`. */
@Suppress("unused", "UNUSED_PARAMETER") fun factoryStateComponent(state: DefaultedState) {}

/**
 * A factory whose JVM name is mangled by an inline value class parameter, like
 * `rememberTextFieldState-Le-punE`; lookup by source name finds nothing.
 */
@Suppress("unused") class MangledFactoryState(val text: String = "")

@Suppress("unused")
@Composable
fun rememberMangledFactoryState(range: ValueState = ValueState()): MangledFactoryState =
  MangledFactoryState(range.text)
