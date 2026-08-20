package ee.schimke.composeai.designpages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `DesignPagesManifest` — whole **pages of a design file**, cached as SVG, with the node id of
 * every component on them joined back to the code that implements it.
 *
 * A design file's pages are its specimen sheets: the shape set, the type scale, every button
 * variant, laid out the way the designer means them to be read. Caching one as SVG rather than as a
 * raster is what makes this more than a screenshot — an SVG exported with node ids is a *document a
 * consumer can address*. Given a node id, the preview server finds that shape in the markup, hides
 * the design's own drawing of it, and puts this catalog's render in the hole it leaves. Same sheet,
 * same layout, our pixels.
 *
 * ## What this replaced, and why the shape changed
 *
 * Version 1 was a **foreign** contract: design-parity's `@design-parity/page-backdrop`, which
 * imported one composed *screen* as a flat PNG and carried a rectangle per component instance on
 * it. It is retired here rather than extended, for a reason that is a property of the data and not
 * of the code: in the Material 3 kit exactly one page holds instances at all, most of each screen
 * on it is hand-drawn rather than assembled from the kit, and the densest screen in the file yields
 * eleven placements of which two are OS chrome. The definition sheets — the other thirty pages —
 * are where the design actually says what a component should look like.
 *
 * So version 2 is **first-party**: this repo's own producer writes it
 * (`scripts/design-artifacts/emit-design-pages.mjs`, from a repo's committed import), and this file
 * is the contract rather than a mirror of someone else's. Two shape changes fall out of that:
 *
 * - **An image is an SVG, not a raster.** [PageImage.format] exists to say so out loud rather than
 *   to offer a choice; a consumer that cannot address the markup gets nothing this surface is for.
 * - **A node carries no bounding box.** Version 1 had to — a flat raster has no structure to ask.
 *   An SVG does, and the element's own box is the answer. Recording Figma's `absoluteBoundingBox`
 *   alongside would give one question two answers that disagree by a few pixels on anything with a
 *   shadow (the export box is the *render* box, effect bleed included), and a consumer choosing
 *   between them would silently pick the wrong one.
 *
 * Version 1 manifests are **not** read. They describe a surface that no longer exists, and their
 * PNGs would paint a stage with nothing addressable on it; [supportsDesignPagesVersion] refuses
 * them so a stale delivery branch shows no pages rather than a page that does nothing.
 */
public const val DESIGN_PAGES_VERSION: Int = 2

/**
 * Whether a manifest at [version] is one we can read.
 *
 * Exact, unlike the range check the foreign v1 contract needed: the producer is in this repository
 * and ships in the same release as its consumer, so "newer than us" is not a state that can arise
 * from someone else's release cadence. Additive fields still parse — that is [DesignPagesJson]'s
 * job, not the version's.
 */
public fun supportsDesignPagesVersion(version: Int): Boolean = version == DESIGN_PAGES_VERSION

/**
 * The decoder every consumer of this contract should use.
 *
 * `ignoreUnknownKeys` because a manifest is read from a **delivery branch**, which is regenerated
 * on its own schedule and can easily be newer than the server reading it. Refusing to parse a
 * manifest that grew a field would turn every additive release into an outage on the last-deployed
 * server.
 */
public val DesignPagesJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/** How a node on the page was linked to code. */
@Serializable
public enum class PageNodeLink {
  /** Figma Code Connect — the machine link. */
  @SerialName("code-connect") CODE_CONNECT,

  /** An explicit entry in the repo's `design-map.json`. */
  @SerialName("manifest") MANIFEST,

  /** Best-effort name match; always low confidence. */
  @SerialName("convention") CONVENTION,

  /**
   * Nothing matched. Not an omission — a component on a specimen sheet with no code behind it is
   * the finding a whole-page view exists to surface, so the producer keeps it.
   */
  @SerialName("unlinked") UNLINKED,
}

/** How much to trust a node's [PageNode.code]. */
@Serializable
public enum class PageNodeConfidence {
  @SerialName("high") HIGH,
  @SerialName("low") LOW,
}

/** One addressable component node on the page, and the code it maps to. */
@Serializable
public data class PageNode(
  /**
   * The node's id in the design file — and the `data-node-id` attribute the export carries for it.
   *
   * This is the whole join. Everything else on this record is a label; this is the handle a
   * consumer uses to find the node in the SVG and take it out of the picture.
   */
  val nodeId: String,
  /** The node's layer name, e.g. `"Shape=Circle"`. Free text authored in the design tool. */
  val name: String = "",
  /** Nesting depth below the page, `1` for a direct child. A layout hint, nothing more. */
  val depth: Int = 0,
  /**
   * The node's own design ref, `"figma:<fileKey>/<nodeId>"` — what lets a node deep-link into the
   * design tool even where no code implements it. Prefer [DesignPagesManifest.refFor], which fills
   * the gap from the manifest's `fileKey` for a producer that wrote none.
   */
  val ref: String? = null,
  /** Code handle, e.g. `"catalog/…/Shapes.kt#CircleShape"`. Null when [link] is [UNLINKED]. */
  val code: String? = null,
  /**
   * Serve preview id, when the repo's `design-map.json` named one. This is what lets us draw the
   * component ourselves — at the node's own size, in the theme the visitor picked — rather than
   * show a baked screenshot.
   */
  val previewId: String? = null,
  val link: PageNodeLink = PageNodeLink.UNLINKED,
  /** Null when unlinked. Stated by the producer so we don't hardcode which methods are weak. */
  val confidence: PageNodeConfidence? = null,
  /**
   * This node is a GROUPING whose contents are listed below it — a Figma `COMPONENT_SET`, whose
   * children are the variants a definition sheet is a grid of.
   *
   * Stated by the producer rather than worked out here, because only the import has the real tree.
   * A manifest lists components and nothing else, so an unlisted frame between two of them lets a
   * shallower node be followed by a deeper one that is NOT inside it — and depth ordering alone
   * would call the shallower one a grouping. Nothing implements a component set (a reference names
   * one of its variants), so it is drawn as structure and left out of the coverage count.
   */
  val container: Boolean = false,
  /**
   * The node type reported by the design tool, for example `COMPONENT` or `COMPONENT_SET`.
   *
   * Kept as free text so a new design-tool type remains an additive manifest change. Consumers only
   * attach meaning to the grouping type they understand; see [isContainer].
   */
  val type: String? = null,
  /**
   * Whether this node is part of the design system's **published inventory** — something a catalog
   * could be expected to implement.
   *
   * `false` is for the kit's own internals: the base parts each published set is assembled from
   * (`Base / SelectionControl / Switch`, `Base / Loading Icon`), which a consumer of the kit never
   * places and no catalog owes an implementation. They are the same kind of thing as [isPrivate],
   * reached by a different convention — the Material 3 Expressive Wear kit states them by a `Base
   * /` name prefix rather than by Figma's leading dot — and `kit-sets.json` in the catalog repos
   * already excludes both from the kit walk. Counting them made a Buttons sheet report 24 missing
   * components that nothing could ever clear.
   *
   * **Stated by the producer, never inferred here**, for the same reason [container] is: the flat
   * node list has no ancestors, so a consumer cannot tell which set a `Selected=Yes, Disabled=No`
   * variant came out of. The importer walks the real tree and knows; see the note on [coverageGaps]
   * about why the depth-ordering shortcut is unsound in exactly the direction that hides a gap.
   *
   * Defaults to `true`, so every manifest published before this field existed keeps counting
   * exactly what it counted before.
   */
  val inventory: Boolean = true,
) {
  /**
   * A component the design file marks as **private** — Figma's leading-dot convention, used for the
   * internal furniture of a sheet: `.Header`, `.Legend`, the swatch a specimen grid repeats.
   *
   * Private components are not published to the design system's consumers, so no catalog is
   * expected to implement one, and counting them as missing coverage makes a complete sheet look
   * incomplete. They stay on the page and stay addressable — they are just not gaps.
   */
  public val isPrivate: Boolean
    get() = name.startsWith(".")

  /**
   * Whether this is a family/grouping rather than one concrete component.
   *
   * Current imports preserve Figma's exact `COMPONENT_SET` type. [container] remains as the
   * backwards-compatible producer hint, but reading [type] is essential: otherwise a whole grid of
   * variants becomes one enormous "missing component" hotspot over the actual components inside it.
   */
  public val isContainer: Boolean
    get() = container || type.equals("COMPONENT_SET", ignoreCase = true)

  /**
   * A **placement** of a component rather than a definition of one — Figma's `INSTANCE`.
   *
   * A specimen sheet is a grid of definitions with placements scattered around it: the page's own
   * `Header`, the `Toolbar` and `FAB` an example composition is assembled from, the `Side Sheet`
   * drawn beside the variant grid that defines it. None of those is a thing a catalog can implement
   * — you implement the component, and an instance only points at one — so an unlinked placement is
   * never a gap.
   *
   * Dropping one is not a blind spot, because a placement is not where a kit *states* a component.
   * A specimen sheet states its components as `COMPONENT` / `COMPONENT_SET`, and those are counted
   * on their own; the instances scattered around them are the page header, the parts of an example
   * composition, and the illustration beside a variant grid. The kit's Sheets page shows the last
   * of those plainly: four `Side Sheet` instances sit beside the `Side Sheet` `COMPONENT_SET` whose
   * four variants are already counted, so counting both reported one missing component twice. Its
   * Toolbars page shows the middle one: the `Toolbar`+`FAB` pairs are a demo of a toolbar with a
   * button beside it, and the toolbars themselves are stated — and implemented — further up.
   *
   * This deliberately does NOT try to prove the definition exists before dropping the placement.
   * The definition is often on another sheet (`Scrim` is drawn on Sheets and defined on Utilities),
   * so a same-page lookup would be wrong; and matching a placement to its definition by layer name
   * is the class of guess this whole change exists to remove — the leading-dot rule it replaces
   * failed exactly because a name is not a fact. Figma's own answer is an `INSTANCE`'s
   * `componentId`, which the manifest does not carry yet; recording it is what would turn this from
   * a sound default into a decision per node.
   *
   * Until then the exposure is bounded and one-directional: a component the kit shows ONLY as an
   * instance stops being counted, rather than being counted as done. The numerator cannot move —
   * across the whole Material 3 kit this changes the total on 27 sheets and the implemented count
   * on none of them.
   *
   * A **linked** instance is the exception and stays a component. Naming an instance's node id in
   * `design-map.json` is a deliberate claim that this placement is the thing we draw, and the
   * Snackbar sheet does exactly that for six of its ten snackbars. So only [isComponent] reads
   * this, and only together with [isUnlinked]: the node's type never overrides an authored mapping.
   */
  public val isPlacement: Boolean
    get() = type.equals("INSTANCE", ignoreCase = true)

  /**
   * A concrete, public component that the page should count and highlight.
   *
   * Four kinds of node are not one, and on a real specimen sheet they are most of it: the sheet's
   * private furniture ([isPrivate]), the kit's own base parts ([inventory] `= false`), the variant
   * sets ([isContainer]), and the placements no mapping claims ([isPlacement]). Everything the page
   * draws a mark for — the outline, the hit area, the audit row, the coverage tally — starts here,
   * so a node excluded here is not merely uncounted: it stops being something the reader can point
   * at, which is the right outcome for all four.
   */
  public val isComponent: Boolean
    get() = inventory && !isPrivate && !isContainer && !(isPlacement && isUnlinked)

  /** Whether this node can be drawn by us, i.e. it names a preview we could ask for. */
  public val isRenderable: Boolean
    get() = previewId != null

  /** True when nothing in the repo claims this part of the sheet. The interesting case. */
  public val isUnlinked: Boolean
    get() = link == PageNodeLink.UNLINKED

  /**
   * The preview this node may be drawn with, or null.
   *
   * Gated on the **link** as well as the id, deliberately. A manifest can carry `link: unlinked`
   * alongside a stale `previewId`, and drawing that would put a render on a node the same page
   * marks "no code behind this" — the two halves of the page contradicting each other. The link is
   * the claim; the id is only how to draw it.
   */
  public val renderablePreviewId: String?
    get() = if (isUnlinked) null else previewId
}

/** The cached export of a page. */
@Serializable
public data class PageImage(
  /** Path to the SVG, relative to the manifest file. */
  val uri: String,
  /**
   * Always `"svg"` today, and stated rather than assumed: a consumer that meets some other format
   * must refuse the page outright rather than inline bytes it cannot address, which is exactly what
   * a defaulted-and-unchecked field would let it do by accident.
   */
  val format: String = SVG,
) {
  public companion object {
    public const val SVG: String = "svg"
  }
}

/**
 * The page's coordinate space, read off the exported SVG's own `viewBox`.
 *
 * Taken from the export rather than computed from the node tree precisely so that the number a
 * consumer lays its stage out with is the number the picture was drawn at.
 */
@Serializable public data class PageFrame(val width: Double, val height: Double)

/** One imported page. */
@Serializable
public data class DesignPage(
  /** Stable slug, unique within the manifest; also the cached SVG's basename. */
  val id: String,
  /** The page's name in the design file. */
  val name: String,
  /** Node id of the page itself. */
  val nodeId: String,
  val frame: PageFrame,
  val image: PageImage,
  /** In the design file's own order, so a re-import diffs cleanly. */
  val nodes: List<PageNode> = emptyList(),
  /**
   * Whether this sheet is a **component inventory** — a page whose contents a catalog is measured
   * against.
   *
   * `false` is the kit's icon page: 499 `COMPONENT` nodes that are an icon set, not a component
   * inventory, and that no Compose catalog implements one-by-one. Counting them reported 499
   * missing components — a third of the whole kit's apparent gap — and drowned every real one.
   * `kit-sets.json` already excludes that page by name from the kit walk; this is the same
   * exclusion, stated where the page view can read it.
   *
   * The page is still imported, still drawn, and still browsable — this changes what the page
   * *claims*, not what it shows. [coverageTotal] is 0 for such a page and the view says what it is
   * instead of scoring it.
   *
   * Page-level rather than a `false` on all 499 nodes because it is a fact about the sheet, and
   * because 499 stamped nodes is a fact repeated 499 times that a re-import can get half-right.
   */
  val inventory: Boolean = true,
) {
  /**
   * Nodes with code behind them — the numerator of the page's coverage.
   *
   * Empty for a page that is not an [inventory], together with [coverageGaps] and [coverageTotal]:
   * these three are the one fraction, and a consumer that read a numerator against a zero
   * denominator would print `12 of 0`. Use [nodes] for an honest node query.
   */
  public val linked: List<PageNode>
    get() = if (!inventory) emptyList() else nodes.filter { it.isComponent && !it.isUnlinked }

  /** Nodes with no code behind them. Not the same as [coverageGaps] — see there. */
  public val unlinked: List<PageNode>
    get() = nodes.filter { it.isUnlinked }

  /**
   * The nodes a reader means by *what we haven't implemented yet*: unlinked, and actually a
   * component someone could implement.
   *
   * Three kinds of unlinked node are not gaps, and on a real specimen sheet they are most of them:
   *
   * 1. **Private components** ([PageNode.isPrivate]) — the sheet's own furniture, never published.
   * 2. **Containers** ([PageNode.container]) — a `COMPONENT_SET`'s variants are the components; the
   *    set is the box they came in. Its variants are listed here in their own right, so counting
   *    the set as well reports one missing component for a family that is fully implemented.
   * 3. **Placements** ([PageNode.isPlacement]) — an `INSTANCE` is a use of a component, not a
   *    definition of one, and the definition is listed here in its own right too. See there; the
   *    kit's page headers are the case that named it.
   *
   * All three are read off the node, never inferred. An earlier cut worked container-ness out from
   * the walk's depth ordering — a node immediately followed by a deeper one — so a manifest
   * published before the producer stated it would still read correctly. That inference is unsound
   * in the direction that matters: a manifest lists components only, so an unlisted frame between
   * two of them lets a shallower node be followed by a deeper one that is not inside it, and a
   * genuinely missing component would then be swallowed as "structure". A stale manifest
   * over-counting a container is visible and harmless; a gap that quietly disappears is neither.
   */
  public val coverageGaps: List<PageNode>
    get() = if (!inventory) emptyList() else nodes.filter { it.isComponent && it.isUnlinked }

  /**
   * How many components on this page a catalog could implement — the denominator behind "N of M
   * implemented", and deliberately not `nodes.size`.
   *
   * Zero for a page that is not an [inventory], which is how a consumer tells "this sheet is fully
   * implemented" from "this sheet is not the kind of thing you implement": the first has a
   * numerator, and the second has no fraction to state at all.
   */
  public val coverageTotal: Int
    get() = if (!inventory) 0 else linked.size + coverageGaps.size
}

/** A committed design-page import. */
@Serializable
public data class DesignPagesManifest(
  val version: Int,
  /** Design source. Only Figma exposes a page-level read API today. */
  val source: String = "figma",
  /** The design-tool file the pages came from. */
  val fileKey: String,
  val pages: List<DesignPage> = emptyList(),
) {
  /** Whether this build understands the manifest's version. */
  public val isSupported: Boolean
    get() = supportsDesignPagesVersion(version)

  /**
   * The design ref for [node], deriving it when the producer didn't write one.
   *
   * Use this rather than [PageNode.ref] directly. A ref is `"figma:<fileKey>/<nodeId>"` and both
   * halves are already here, so reconstructing it is exact rather than a guess — and an unlinked
   * node's deep link into the design tool is the only link it has.
   */
  public fun refFor(node: PageNode): String = node.ref ?: "figma:$fileKey/${node.nodeId}"
}
