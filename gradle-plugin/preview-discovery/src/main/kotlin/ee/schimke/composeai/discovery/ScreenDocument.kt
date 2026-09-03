package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * A screen a builder composed out of components, in the shape a code generator can consume.
 *
 * This is deliberately **not** the UI builder's own document model. It is the narrow subset
 * [ScreenGenerator] needs — which component, what the user set, what went in each slot — so that
 * the generator can be tested and reasoned about without dragging an editor's undo stack, presence
 * state and collaboration protocol along with it. A builder projects its document onto this; the
 * projection is the builder's job and the interesting decisions are not in it.
 */
@Serializable
data class ScreenDocument(
  /** The generated composable's name. Must be a valid Kotlin identifier — the generator checks. */
  val name: String,
  val root: ScreenNode,
)

/**
 * One placed component: which one, the arguments the user set, and what they dropped into its
 * slots.
 *
 * @property componentId a [ComponentRecord.canonicalId], or one of its
 *   [ComponentRecord.componentIds] catalog aliases. Both are accepted because the two sides name
 *   components differently and neither can be made to give: discovery's key is derived
 *   (`app/androidx.compose.material3.TextKt.Text`) precisely so an ordinary preview that carries no
 *   catalog identity still gets one, while a builder's document holds the authored id its palette
 *   was built from (`m3/text`). The record already carries that mapping, so resolving it here beats
 *   asking every builder to rebuild it. An alias claimed by two records is a refusal rather than a
 *   pick, and an id matching nothing is a refusal too — a builder pinned to a catalog it no longer
 *   has is exactly the case where inventing a call site produces confident nonsense.
 * @property arguments values the user set, by **source parameter name**. A name the component does
 *   not declare is a refusal: silently dropping it would generate a screen that compiles and is not
 *   the one that was designed.
 * @property slots children by slot name. A slot the component does not declare is likewise refused.
 */
@Serializable
data class ScreenNode(
  val componentId: String,
  val arguments: Map<String, ScreenValue> = emptyMap(),
  val slots: Map<String, List<ScreenNode>> = emptyMap(),
)

/**
 * A value a builder can set on a parameter.
 *
 * ## Two halves, with different guarantees
 *
 * [Text], [Bool], [Whole] and [Fractional] are **self-evidently typed**: the generator knows what
 * `"hello"` and `false` are without being told, so it can reject `text = true` from the record
 * alone. That is the whole reason the first version of this interface held nothing else.
 *
 * It held nothing else for one release. A real builder document sets colours, dimensions, theme
 * tokens, enum entries, paddings and modifier chains, and measured against one — 19 `m3/text`
 * nodes, 18 colour tokens, 14 typography tokens, 11 paddings, 10 layout modifiers — the four
 * literal kinds covered under a third of the values placed. A generator that refuses two thirds of
 * a screen is not a generator anybody uses, so the vocabulary widened to the three shapes below:
 * [Reference] (`MaterialTheme.colorScheme.primary`), [Construct] (`PaddingValues(16.dp)`) and
 * [Chain] (`Modifier.fillMaxWidth()`).
 *
 * ## What the widening costs, stated plainly
 *
 * Those three carry a **claimed** [ScreenValue.typeFqn] rather than an evident one. The generator
 * still checks it against [TargetParameter.typeFqn] and still refuses a mismatch, so a colour
 * handed to a `String` parameter is caught exactly as `text = true` was — but it cannot check that
 * `MaterialTheme.colorScheme.primary` really *is* a `Color`. It takes the projection's word for
 * that one fact.
 *
 * That trade is deliberate and it is the smaller of two evils. The alternative was a table of
 * Material 3's token names inside a library that knows nothing about Material 3, maintained by
 * hand, wrong the first time a design system renamed a token. Instead the design system's own
 * knowledge stays with whoever has it, and what arrives here is a **restricted expression shape**:
 * a dotted path, a call, a chain of extension links. Not free text — every name is checked as
 * writable Kotlin and every reference is emitted fully qualified, so a projection cannot smuggle in
 * `run { System.exit(0) }` or shadow an import.
 *
 * A projection that gets a claimed type wrong produces source that does not compile. That failure
 * is loud, local and lands on the projection — which is the right place for it, and is why the
 * server-side compile check exists.
 */
@Serializable
sealed interface ScreenValue {

  /**
   * The fully-qualified classifier this value has, or null when the value is its own evidence.
   *
   * Null for the four literal kinds — nothing sensible could be claimed for `Whole(1)`, which fits
   * `Int` and `Long` alike and is checked against whichever the parameter declares.
   */
  val typeFqn: String?
    get() = null

  /**
   * `@RequiresOptIn` markers the expression needs, declared with `kotlin.RequiresOptIn`.
   *
   * The generator unions these into the wrapper's `@OptIn` exactly as it does for a component's
   * [ComponentCode.requiredOptIns]. It has to be declared rather than derived: a component's
   * markers come from the record, which discovery read off the class file, but a value names an
   * arbitrary allowed callable and nothing here has that callable's annotations. So a projection
   * reaching for an experimental accessor says so, and one that forgets gets a file the compiler
   * rejects — the same trade as [typeFqn], and the same reason it is written down here.
   */
  val requiredOptIns: List<String>
    get() = emptyList()

  /**
   * The subset of [requiredOptIns] declared with `androidx.annotation.RequiresOptIn`, which needs
   * `@androidx.annotation.OptIn(markerClass = […])` rather than `@kotlin.OptIn` — see
   * [ComponentCode.androidxOptIns] for why the two are not interchangeable.
   */
  val androidxOptIns: List<String>
    get() = emptyList()

  @Serializable data class Text(val value: String) : ScreenValue

  @Serializable data class Bool(val value: Boolean) : ScreenValue

  @Serializable data class Whole(val value: Long) : ScreenValue

  @Serializable data class Fractional(val value: Double) : ScreenValue

  /**
   * A read through a fully-qualified path — `androidx.compose.material3.MaterialTheme.colorScheme
   * .primary`, `androidx.compose.ui.text.style.TextAlign.Center`.
   *
   * Emitted fully qualified and therefore imported nowhere. That is not tidiness: an import can be
   * shadowed by a same-named declaration in the package the caller chose for the generated file,
   * and a qualified path cannot. It is also what keeps this case honest — there is no receiver to
   * infer and no overload to resolve, so the only thing the generator is trusting is [typeFqn].
   *
   * @property rootFqn the qualified name the path starts from. A class (`…MaterialTheme`), an
   *   object, or a top-level property.
   * @property members further members read from it, in order. Empty for a bare object read.
   */
  @Serializable
  data class Reference(
    val rootFqn: String,
    val members: List<String> = emptyList(),
    override val typeFqn: String,
    override val requiredOptIns: List<String> = emptyList(),
    override val androidxOptIns: List<String> = emptyList(),
  ) : ScreenValue

  /**
   * A call to a fully-qualified callable — `androidx.compose.ui.graphics.Color(0xFF6750A4)`,
   * `androidx.compose.foundation.layout.PaddingValues(16.dp)`.
   *
   * Qualified for the same reason [Reference] is. A constructor and a top-level factory function
   * are the same shape here on purpose: `Color(…)` is a function and `PaddingValues(…)` a
   * constructor, the distinction is invisible at the call site, and a record that made a projection
   * declare which one would only give it a second thing to get wrong.
   *
   * @property positional arguments in declaration order.
   * @property named arguments by parameter name, appended after [positional].
   */
  @Serializable
  data class Construct(
    val callableFqn: String,
    val positional: List<ScreenValue> = emptyList(),
    val named: Map<String, ScreenValue> = emptyMap(),
    override val typeFqn: String,
    override val requiredOptIns: List<String> = emptyList(),
    override val androidxOptIns: List<String> = emptyList(),
  ) : ScreenValue

  /**
   * A receiver followed by extension links — `Modifier.fillMaxWidth().padding(16.dp)`, `16.dp`.
   *
   * The one case that **must** import, and the reason it is a case of its own rather than a
   * [Reference] with a longer path: an extension is resolved from the importing file's scope, so
   * `Modifier.androidx.compose.foundation.layout.fillMaxWidth()` is not a spelling of anything.
   * Each link's callable is imported and called by its simple name, and two links claiming one
   * simple name from different packages are refused — Kotlin calls that a conflicting import, and
   * picking one would silently generate a chain nobody wrote.
   *
   * @property receiver what the links apply to. `Modifier` as a [Reference], or a literal for the
   *   `16` in `16.dp`.
   */
  @Serializable
  data class Chain(
    val receiver: ScreenValue,
    val links: List<ChainLink>,
    override val typeFqn: String,
    override val requiredOptIns: List<String> = emptyList(),
    override val androidxOptIns: List<String> = emptyList(),
  ) : ScreenValue
}

/**
 * One link in a [ScreenValue.Chain].
 *
 * @property callableFqn the extension's fully-qualified name. Imported, then called by simple name.
 * @property property a property read (`.dp`) rather than a call (`.padding(8.dp)`). A link that is
 *   both — a property with arguments — is a refusal, not a call.
 */
@Serializable
data class ChainLink(
  val callableFqn: String,
  val positional: List<ScreenValue> = emptyList(),
  val named: Map<String, ScreenValue> = emptyMap(),
  val property: Boolean = false,
)
