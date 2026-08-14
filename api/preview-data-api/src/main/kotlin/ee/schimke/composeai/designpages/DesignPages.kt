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
   * The node's type in the design file — `COMPONENT`, `COMPONENT_SET`, `INSTANCE`.
   *
   * A fact, not a judgement: it is what tells a container apart from the components inside it,
   * which is the difference between "35 shapes, all implemented" and "36 things, one missing". Kept
   * as free text rather than an enum so a design tool can grow a type without this refusing to
   * parse; a producer that emits none is handled by [DesignPage.coverageGaps].
   */
  val type: String? = null,
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
) {
  /** Nodes with code behind them. */
  public val linked: List<PageNode>
    get() = nodes.filter { !it.isUnlinked }

  /** Nodes with no code behind them. Not the same as [coverageGaps] — see there. */
  public val unlinked: List<PageNode>
    get() = nodes.filter { it.isUnlinked }

  /**
   * The nodes a reader means by *what we haven't implemented yet*: unlinked, and actually a
   * component someone could implement.
   *
   * Two kinds of unlinked node are not gaps, and on a real specimen sheet they are most of them:
   *
   * 1. **Private components** ([PageNode.isPrivate]) — the sheet's own furniture, never published.
   * 2. **Containers.** A `COMPONENT_SET`'s variants are the components; the set is the box they
   *    came in. Its variants are listed here in their own right, so counting the set as well
   *    reports one missing component for a family that is fully implemented.
   *
   * Container-ness is read from [PageNode.type] when the producer states it, and otherwise inferred
   * from the walk: nodes are emitted depth-first in document order, so a node immediately followed
   * by a DEEPER one has component children on this page and is therefore a box rather than a leaf.
   * The inference exists so that a manifest imported before `type` was recorded still reads
   * correctly — a published page should not have to wait for a re-import to stop miscounting.
   */
  public val coverageGaps: List<PageNode>
    get() = nodes.filterIndexed { index, node ->
      val hasNestedChildren = nodes.getOrNull(index + 1)?.let { it.depth > node.depth } ?: false
      val isContainer =
        when (node.type?.uppercase()) {
          "COMPONENT_SET" -> true
          null -> hasNestedChildren
          else -> false
        }
      node.isUnlinked && !node.isPrivate && !isContainer
    }

  /**
   * How many components on this page a catalog could implement — the denominator behind "N of M
   * implemented", and deliberately not `nodes.size`.
   */
  public val coverageTotal: Int
    get() = linked.size + coverageGaps.size
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
