package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * One `key=value` pair from a `@BuilderComponent` array parameter, split on the **first** `=` so a
 * value may contain one.
 *
 * The same shape and the same reason as a catalog variant's props: annotations cannot hold a `Map`,
 * and splitting once at discovery beats every consumer growing its own splitter.
 */
@Serializable data class BuilderPair(val key: String, val value: String)

/**
 * UI-builder policy for one component, discovered from `@BuilderComponent` in the
 * `preview-annotations` artifact — what a catalog says about how its component behaves in a drawing
 * tool, as opposed to what its signature already says.
 *
 * The component record is derived: parameters, slots, call site and opt-in markers all come from
 * `@kotlin.Metadata` or from discovery's inference, so nothing in it is typed twice. This is the
 * residue that no signature holds — that `onCheckedChange` updates a `checked` state rather than
 * merely firing, that a new instance should arrive with a label in it, that the canvas may draw
 * this one through a real adapter and must draw that one as a placeholder. It used to be written in
 * Kotlin in the preview server, per catalog, by hand; the contract that moves it here is
 * [UI_BUILDER_CATALOG_CONTRACT.md](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_CATALOG_CONTRACT.md).
 *
 * ### One type, two files
 *
 * Declared in the shared source `preview-discovery` and `:screen-model` both compile, so
 * `previews.json` ([PreviewInfo.builder], where the annotation lands) and `components.json`
 * ([ComponentRecord.builder], where a consumer reads it) carry the *same* type rather than a
 * manifest shape and a record shape that mean the same thing and drift apart on their third field.
 *
 * ### Nothing is defaulted on the catalog's behalf
 *
 * A blank annotation argument records `null`, not the value a generator would pick: "the catalog
 * did not say" and "the catalog said `placeholder`" are different facts, and a report of which
 * components nobody has looked at is only true if the record can tell them apart. Every consumer
 * applies its own defaults on read, and says so.
 */
@Serializable
data class BuilderPolicy(
  /**
   * `@BuilderComponent.id` — the id this component is known by in the builder, and the string a
   * saved design stores in a node. Null derives one from the catalog identity.
   */
  val id: String? = null,
  /**
   * `@BuilderComponent.component` — which component this policy is about, when its sticker renders
   * several. A callable FQN or a simple name; null when the sticker renders one.
   */
  val component: String? = null,
  /** Insert-panel group override; null keeps the catalog's own `@CatalogGroup`. */
  val group: String? = null,
  /** Insert-panel label; null derives one from the id's last segment. */
  val displayName: String? = null,
  /**
   * Canvas adapter id this catalog claims for the component, or `"placeholder"`.
   *
   * Null means the catalog did not say, which a builder treats as placeholder and a generator
   * reports as unclaimed — a shelf drawn entirely in placeholders is a fact worth being able to
   * see. A builder that ships no adapter by this name draws the placeholder and logs it once; it
   * never refuses the catalog, because the file is published once and read by builders of several
   * vintages that the publisher cannot upgrade.
   */
  val canvas: String? = null,
  /**
   * `<callback>=<state>:<type>` — the lambdas that update state rather than merely firing, so an
   * export hoists a `remember` above the call instead of emitting a picture of a checkbox.
   */
  val stateCallbacks: List<BuilderPair> = emptyList(),
  /** `<parameter>=<value>` — what a freshly inserted instance arrives holding. */
  val starter: List<BuilderPair> = emptyList(),
  /**
   * `<slot>=<traits>`, `|`-separated — what each of the component's slots accepts. The record
   * already says which parameters *are* slots; this says what each will take, which is a design
   * decision rather than a type.
   */
  val slots: List<BuilderPair> = emptyList(),
  /**
   * Traits this component offers to other components' slots. Deliberately free-form: slot
   * acceptance is a within-catalog relation and no reader compares traits across catalogs.
   */
  val traits: List<String> = emptyList(),
  /** The parameter whose value drives the builder's variant control, when it has one. */
  val variantProperty: String? = null,
  /** `<label>=<value>` variant-control entries; empty offers the property's own allowed values. */
  val variants: List<BuilderPair> = emptyList(),
  /**
   * Renders only in the native lane. The builder still offers, exports and natively renders it; the
   * canvas shows a placeholder that says so rather than one that looks like a failure. Distinct
   * from a null [canvas], which means nobody has claimed an adapter for it yet.
   */
  val nativeOnly: Boolean = false,
  /** Why this component is kept off the builder's shelf; null leaves the pack rules to decide. */
  val exclude: String? = null,
  /**
   * The preview ids that declared this policy, in order.
   *
   * A component is routinely rendered by several previews, and any of them may carry the
   * annotation. Recording who declared it makes two things answerable that a merged policy
   * otherwise hides: which sticker to edit, and — when [conflicting] is non-empty — that more than
   * one sticker declared a *different* policy for the same component.
   */
  val declaredBy: List<String> = emptyList(),
  /**
   * Preview ids whose declared policy disagreed with this one and was dropped.
   *
   * Empty in the ordinary case, including the case where several previews declare the *same*
   * policy. Non-empty means one component's stickers contradict each other and the lowest preview
   * id won — recorded rather than resolved silently, because the resolution is arbitrary and the
   * disagreement is the thing somebody needs to fix.
   */
  val conflicting: List<String> = emptyList(),
  /**
   * Other components the sticker renders that this policy could equally have been about.
   *
   * Empty in the ordinary case. Non-empty means the sticker renders several components, the
   * annotation named none of them with `component`, and the policy was bound to the first — a guess
   * discovery is honest about rather than a rule. The generator reports it and names the fix.
   */
  val ambiguousWith: List<String> = emptyList(),
  /**
   * `key=value` entries the annotation carried that could not be read, verbatim.
   *
   * A malformed entry costs that entry rather than the build, and the reason that is an acceptable
   * trade is that it is *reported*. Dropping the raw string at the point of the split would leave
   * nothing to report with, and the component would keep a default nobody meant it to have with no
   * symptom at all — which is the failure the leniency was supposed to be cheaper than.
   */
  val malformed: List<String> = emptyList(),
)
