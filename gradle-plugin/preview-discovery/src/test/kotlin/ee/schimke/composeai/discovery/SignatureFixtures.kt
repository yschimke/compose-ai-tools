package ee.schimke.composeai.discovery

import androidx.compose.runtime.Composable
import ee.schimke.composeai.preview.KnobValue

// Fixtures for ComposableSignatureTest. Deliberately NOT @Composable — that would drag the Compose
// runtime onto the discovery test classpath, and ComposableSignature reads only Kotlin @Metadata,
// which every Kotlin declaration carries regardless. A top-level function compiles into the file
// facade class `SignatureFixturesKt`, exercising the FileFacade metadata path.
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
 * The shape `LazyColumn` has: a receiver lambda that is **not** `@Composable`, whose children are
 * declared through members of the receiver rather than composed into it.
 */
@Suppress("unused", "UNUSED_PARAMETER")
fun scopeDslComponent(content: TestListScope.() -> Unit) {}

/** An ordinary callback, to prove the signal is about the receiver and not about being a lambda. */
@Suppress("unused", "UNUSED_PARAMETER")
fun callbackComponent(onValueChange: (String) -> Unit) {}

class TestListScope

@Suppress("unused") fun noParams() {}

/**
 * Every parameter defaulted — the shape a production composable annotated `@Preview` in place
 * almost always has (`modifier: Modifier = Modifier`). Discovery admits these.
 */
@Suppress("unused", "UNUSED_PARAMETER")
fun allDefaultedComponent(modifier: String = "", count: Int = 1) {}

// --- Knob fixtures (the secondary override format) -----------------------------------------

/**
 * The shape the parameter override format is *for*: every parameter defaulted, and every type one
 * the harness can build from a seed string. All six become knobs, in declaration order.
 */
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
 * The common production shape: defaulted and renderable, but `modifier` is not constructible from a
 * seed, so only `count` is a knob — and its index is its position in the FULL parameter list, which
 * is what the renderer needs to place the argument.
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
 * Constants whose seed text is not their name — including one, `extra-large`, that is not a legal
 * Kotlin identifier at all, which is why the alias has to exist rather than the constant being
 * renamed to the value.
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

// A nullable function-typed parameter — the shape material3's
// `Checkbox(onCheckedChange: ((Boolean) -> Unit)?)` has, and the one whose rendering used to be
// ambiguous with a non-null callback returning `Unit?`.
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
 * A marker that is **not itself** an opt-in requirement but whose class is guarded by one — exactly
 * `@ComposableInferredTarget`, which the Compose compiler stamps onto every composable and which is
 * declared `@InternalComposeApi`. Reading the meta-annotation closure of a method carrying this
 * reports [InternalFixtureApi], which no caller has to opt into.
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

// No context-receiver fixture: this module's language version cannot express either spelling
// (`context(Foo)` needs -Xcontext-receivers, `context(f: Foo)` needs language version 2.4). The
// refusal a recorded context produces is covered in ComponentSnippetsTest instead; the metadata
// read itself has no fixture here, which ComposableSignature.hasContextRequirement says plainly.

/**
 * A parameter written through a type alias, for the question of what `typeFqn` records for one.
 *
 * Kotlin's metadata **expands** an alias — the docs for `KmType.abbreviatedType` say so outright —
 * so the recorded classifier should be the aliased class rather than the alias, and a value
 * claiming that class should match. Asserted rather than assumed, because a review finding claimed
 * the opposite and the difference is a whole category of false refusals.
 */
typealias AliasedLabel = String

@Suppress("unused", "UNUSED_PARAMETER") fun aliasedComponent(label: AliasedLabel = "") {}

// --- Constructibility fixtures (issue #5067) -------------------------------------------------
//
// Each names one clause of `ComposableSignature.isNoArgConstructible`. The point of a fixture per
// clause is that the rule is checked against a real compiler's output rather than against a belief
// about what Kotlin emits: an all-defaulted constructor exists as a zero-arg call only in SOURCE
// (the JVM sees a `(…, int, DefaultConstructorMarker)` bridge), which is exactly the distinction a
// hand-written record could not have proved.

/**
 * The `TextFieldState` shape: every constructor parameter defaulted, so `DefaultedState()`
 * compiles.
 */
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

// --- the `remember…` factory convention ---------------------------------------------------------
// Each names one clause of `ComposableSignature.noArgFactoryFor`. The convention is Compose's, but
// what is under test is that it is LOOKED UP rather than spelled from a type name: every fixture
// below is a real `remember…` the scan can either accept or reject on its declared shape, and a
// resolver that matched on the name alone would accept all of them.

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
 * A factory whose JVM name is **mangled**, because it takes an inline value class parameter.
 *
 * The case the real classpath had and the fixtures above did not: `rememberTextFieldState` takes a
 * defaulted `TextRange`, so Kotlin emits it as `rememberTextFieldState-Le-punE`. A resolver that
 * looks the method up under its source name finds nothing and refuses a factory that exists — which
 * is what the functional test caught, and what this pins.
 */
@Suppress("unused") class MangledFactoryState(val text: String = "")

@Suppress("unused")
@Composable
fun rememberMangledFactoryState(range: ValueState = ValueState()): MangledFactoryState =
  MangledFactoryState(range.text)
