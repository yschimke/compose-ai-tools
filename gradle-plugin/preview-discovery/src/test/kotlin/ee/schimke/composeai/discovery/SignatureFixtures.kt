package ee.schimke.composeai.discovery

import androidx.compose.runtime.Composable

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
