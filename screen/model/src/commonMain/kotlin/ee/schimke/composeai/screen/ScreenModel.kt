package ee.schimke.composeai.screen

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** The schema tag every [Screen] carries, so a reader can refuse a document it cannot parse. */
public const val SCREEN_SCHEMA: String = "compose-ai-screen/v1"

/**
 * A composition assembled from catalog components — the document a UI builder edits and the input
 * to [ScreenCodegen].
 *
 * Deliberately **data**: no Compose types, no catalog types, no renderer. The same document is
 * edited in a browser, rendered by any host that knows the component ids, and turned into Kotlin —
 * three consumers that must not have to agree on anything but this shape.
 *
 * @property name the composition's own name; becomes the generated composable's function name.
 * @property roots the top-level children, in order. A list rather than a single root because a
 *   screen is a stack of things far more often than it is one thing, and forcing an artificial
 *   wrapper node would make every generated file carry a `Column` nobody asked for.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class Screen(
  val name: String,
  val roots: List<ScreenNode> = emptyList(),
  /**
   * Always written, unlike the other defaults: a schema tag a reader cannot see is not a schema
   * tag. `knobs` and `children` stay omitted when empty, which keeps a document readable.
   */
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val schema: String = SCREEN_SCHEMA,
)

/**
 * One component instance in a [Screen].
 *
 * @property componentId the catalog id this instance renders — `button-filled`, `column`. Meaning
 *   is the host's; this model never interprets it.
 * @property knobs the values **this instance** carries, by knob key. Per-instance is the whole
 *   point: two `button-filled` nodes in one screen have different labels, which is why a renderer
 *   addresses them by an indexed seed key rather than a bare one.
 * @property children nested instances. Empty for a leaf; a host that does not know [componentId] as
 *   a container should render the node and drop them rather than fail.
 * @property slot the parent's named slot this node fills (`topBar`, `leadingIcon`), or null to sit
 *   in the parent's ordinary child list. Named slots and ordered children are different things — a
 *   `Scaffold` has the first, a `Column` has the second, and a `Card` has both.
 */
@Serializable
public data class ScreenNode(
  val componentId: String,
  val knobs: Map<String, String> = emptyMap(),
  val children: List<ScreenNode> = emptyList(),
  val slot: String? = null,
)

/**
 * Every node of [Screen.roots] in **pre-order**, paired with the instance index a renderer seeds
 * its knobs under.
 *
 * Pre-order and nothing else: the index is what makes one instance's `label` distinguishable from
 * another's, so it has to be derived from the document identically by whoever renders it and
 * whoever edits it. A traversal order that depended on the host would silently give the two
 * different answers, and the symptom — one node showing another's text — would look like a knob bug
 * rather than an ordering one.
 */
public fun Screen.flatten(): List<IndexedScreenNode> {
  val out = ArrayList<IndexedScreenNode>()
  fun walk(node: ScreenNode, parent: Int?) {
    val index = out.size
    out.add(IndexedScreenNode(index, node, parent))
    node.children.forEach { walk(it, index) }
  }
  roots.forEach { walk(it, null) }
  return out
}

/** A [ScreenNode] with its pre-order [index] and its parent's, from [flatten]. */
public data class IndexedScreenNode(
  val index: Int,
  val node: ScreenNode,
  val parentIndex: Int?,
)

/**
 * The seed key a knob takes for the instance at [index] — `key[index]`, the same scheme
 * `PreviewOverrideHost.seedKey` and the wasm catalog's knob map already use.
 *
 * Reusing that scheme rather than inventing one is what lets a screen render with **no renderer
 * change at all**: the host flattens the document into the knob map its catalog already reads.
 */
public fun seedKeyFor(key: String, index: Int): String = "$key[$index]"

/**
 * Every knob in [Screen], flattened to the `key[index] -> value` map a host seeds its catalog with.
 *
 * This is the whole of the per-instance story: a screen with two buttons produces `label[0]` and
 * `label[3]`, and the catalog body that reads `label` under instance 3 gets the second one's text.
 */
public fun Screen.knobSeeds(): Map<String, String> = buildMap {
  flatten().forEach { (index, node, _) ->
    node.knobs.forEach { (key, value) -> put(seedKeyFor(key, index), value) }
  }
}
