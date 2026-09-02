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
