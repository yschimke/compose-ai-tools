package ee.schimke.composeai.data.layoutinspector

import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Turns a [FigmaLayeredSvg] export into an **exploded axonometric view**: the same vector drawing,
 * tilted away from the viewer and pulled apart into one floating sheet per level of composable
 * nesting, with a leader line and a label naming the composables on each sheet.
 *
 * The point is to make a screen's *construction* legible in one still image — which `Surface` sits
 * under which `Scaffold`, what the `Card` contributes versus the `Text` inside it — the way a
 * hardware exploded diagram shows a phone as enclosure ↑ screen ↑ board ↑ battery. A flat render
 * answers "what does it look like"; this answers "what is it made of", and unlike a tree view it
 * keeps every layer's real pixels.
 *
 * **It is a pure SVG→SVG transform, deliberately.** The layered export already carries exactly the
 * structure this needs — every composable is a `<g id="…">` nested the way the composables nest —
 * so nothing has to be re-rendered, no new data product is captured, and the result is a *static
 * SVG*: it embeds in a PR body, opens in a browser, rasterizes in the visual-diff bot, and pastes
 * into Figma, none of which a WebGL scene does. It also composes with every existing lane, because
 * it is a rewrite of bytes those lanes already produce (`?exploded=1` on `/render/<id>.svg`).
 *
 * ## How the split works
 *
 * Each *drawing* element (`rect` / `path` / `text` / `image` / `use` / …) is assigned to the plane
 * matching its **named-group depth** — the number of `<g id="…">` ancestors it has. A group's own
 * fills and text therefore land one plane above the group that contains it, which is precisely the
 * "tied to composables" grouping: plane 0 is the frame, plane 1 the root composable's own drawing,
 * plane 2 its children's, and so on. Depths past [Options.maxDepth] fold into the last plane rather
 * than producing a tower of near-empty sheets.
 *
 * Each plane is then re-emitted as a *structural copy* of the original tree with only that plane's
 * drawing elements retained — the enclosing `<g transform=…>` / `clip-path=…` chain is preserved so
 * every element still lands where it did, and `<defs>` / `<clipPath>` / `<style>` are carried over
 * once at the top so gradients, shadow filters, hoisted Material icons and embedded `@font-face`
 * blocks keep resolving from every plane.
 *
 * ## The projection
 *
 * A plane is flat, so an axonometric camera reduces to a plain affine `matrix(…)` — no 3D engine,
 * no per-point maths, one attribute per sheet. Spin the drawing in its own plane by ψ
 * ([Options.spinDeg]), lean it away from the viewer by φ ([Options.tiltDeg]), project
 * orthographically:
 * ```
 *   X = cosψ·u − sinψ·v
 *   Y = cosφ·(sinψ·u + cosψ·v) − k·gap         (k = plane index)
 * ```
 *
 * which is `matrix(cosψ, cosφ·sinψ, −sinψ, cosφ·cosψ, 0, −k·gap)` about the drawing's centre. φ = 0
 * is the screen face-on — a portrait preview still reads as a portrait preview — and larger φ leans
 * it further back; the default is a shallow lean that shows the sheets are stacked without laying
 * the screen down on a table.
 *
 * **The separation is a screen-space offset, not a distance along the plane normal**, and that is a
 * deliberate exaggeration rather than an oversight. Physically the sheets separate along the
 * normal, which projects to `sinφ` — vanishing exactly as the view approaches the face-on angle a
 * UI is meant to be read at, so a physically-honest camera can only explode a screen by first
 * flattening it. Every printed exploded diagram cheats the same way. Decoupling them also makes
 * both sliders mean what their labels say: lean changes only the lean, separation only the spacing.
 *
 * Planes are emitted in ascending k, which is also back-to-front under this camera, so the
 * painter's order is document order and no depth sorting is needed.
 */
object ExplodedSvg {

  /** Knobs for [render]. Defaults are the "reads like a hardware exploded diagram" preset. */
  data class Options(
    /** In-plane rotation ψ, degrees. Negative spins the drawing anticlockwise on the page. */
    val spinDeg: Double = -16.0,
    /**
     * How far the sheets lean away from the viewer, in degrees. `0` is face-on — a portrait screen
     * still reads as a portrait screen, just spun by [spinDeg] — and `90` would be edge-on. The
     * default is a shallow lean: enough to show the sheets are stacked in depth, not so much that
     * the screen is lying on a table and its content has to be read sideways.
     */
    val tiltDeg: Double = 28.0,
    /**
     * Screen-space separation between adjacent sheets, in the output SVG's user units (see the
     * class doc on why this is a screen offset rather than a distance along the plane normal).
     * `null` derives one from the drawing's own size, so a 48px icon and a 900px tablet screen both
     * come out proportioned.
     */
    val gap: Double? = null,
    /**
     * Plane cap. Composables nested deeper than this fold into the last sheet — a real screen is
     * 15+ levels deep, and a sheet per level is a tower nobody can read.
     */
    val maxDepth: Int = 6,
    /** Draw the leader lines + composable names beside the stack. */
    val labels: Boolean = true,
    /** Outline each sheet, so an empty or sparse plane still reads as a plane. */
    val plates: Boolean = true,
  ) {
    init {
      require(maxDepth in 1..MAX_PLANES) { "maxDepth must be in 1..$MAX_PLANES, was $maxDepth" }
    }
  }

  /**
   * Hard cap on [Options.maxDepth]. Each plane is a structural copy of the source tree, so this
   * bounds both the work and the output size for a hostile or pathologically deep input.
   */
  const val MAX_PLANES: Int = 16

  /**
   * Clamp for a caller-supplied [Options.tiltDeg]. Past ~75° the sheets are so foreshortened that
   * the drawing on them stops being readable, which is the one thing this view exists to preserve.
   */
  private val TILT_RANGE = 0.0..75.0

  /** Clamp for a caller-supplied [Options.spinDeg]. */
  private val SPIN_RANGE = -80.0..80.0

  /**
   * Layer ids that name no composable — the layout tree's fallbacks when source-info resolution
   * didn't produce a real name. Kept out of the labels so a stack doesn't read
   * "ReusableComposeNode" six times.
   */
  private val UNINFORMATIVE_LAYER_NAMES = setOf("ReusableComposeNode", "Layer", "Root")

  /** Elements that paint. Assigned to a plane; never descended into. */
  private val DRAWING_TAGS =
    setOf(
      "rect",
      "circle",
      "ellipse",
      "line",
      "polyline",
      "polygon",
      "path",
      "text",
      "image",
      "use",
      "foreignObject",
    )

  /**
   * Resource elements. Carried over once, verbatim, at the top of the output — every plane still
   * resolves `url(#…)` / `href="#…"` against them because they live in the same document.
   */
  private val RESOURCE_TAGS =
    setOf(
      "defs",
      "clipPath",
      "linearGradient",
      "radialGradient",
      "pattern",
      "filter",
      "mask",
      "marker",
      "symbol",
      "style",
      "metadata",
    )

  /** Container elements whose children carry the drawing. */
  private val GROUP_TAGS = setOf("g", "a", "switch")

  private const val SVG_NS = "http://www.w3.org/2000/svg"

  /**
   * Rewrite [svg] — a layered `compose/figma-svg` export — as an exploded axonometric view.
   *
   * Returns [svg] unchanged when it isn't parseable as SVG or carries no geometry to project, so a
   * caller can apply this unconditionally: the worst case is the ordinary flat export, never a
   * broken response.
   */
  fun render(svg: String, options: Options = Options()): String {
    val doc = parse(svg) ?: return svg
    val root = doc.documentElement ?: return svg
    if (root.localNameOf() != "svg") return svg
    val box = viewBoxOf(root) ?: return svg
    if (box.width <= 0.0 || box.height <= 0.0) return svg

    // Assign every drawing element a plane, and remember which composable names live on each. The
    // walk skips <defs>/<clipPath>/… so a hoisted Material-icon `<g id="material-icon-…">` inside
    // <defs> never counts as a nesting level.
    val scan = Scan(options.maxDepth)
    for (child in root.childElements()) scan.walk(child, 0)
    val planeCount = scan.planeCount()
    if (planeCount == 0) return svg

    val spin = options.spinDeg.coerceIn(SPIN_RANGE) * PI / 180.0
    val tilt = options.tiltDeg.coerceIn(TILT_RANGE) * PI / 180.0
    // The separation is bounded relative to the drawing rather than by an absolute number, because
    // "how far apart is too far" only means anything next to the sheet's own size. Past this the
    // sheets are specks at the ends of a ribbon of whitespace — and far enough past it, the canvas
    // dimensions stop being representable at all (see [fmt]).
    val gap =
      options.gap?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(maxGap(box))
        ?: autoGap(box, planeCount)

    val a = cos(spin)
    val b = cos(tilt) * sin(spin)
    val c = -sin(spin)
    val d = cos(tilt) * cos(spin)
    // Centre the projection on the drawing so the tilt doesn't also slide it across the canvas.
    val cx = box.minX + box.width / 2.0
    val cy = box.minY + box.height / 2.0
    val baseE = -(a * cx + c * cy)
    val baseF = -(b * cx + d * cy)
    // Plane 0 is the outermost frame; each further plane floats one `gap` toward the viewer, drawn
    // as a straight-up offset on the page so the sheets stay legible at a near-portrait lean (see
    // the class doc).
    fun planeOffsetY(plane: Int): Double = -(plane * gap)

    val fontSize = (max(box.width, box.height) * 0.035).coerceIn(10.0, 26.0)

    // Bounding box of every projected sheet corner — the canvas has to hold the whole stack, which
    // is both wider (the spin) and much taller (the separation) than the flat drawing.
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    val corners =
      listOf(
        box.minX to box.minY,
        box.minX + box.width to box.minY,
        box.minX to box.minY + box.height,
        box.minX + box.width to box.minY + box.height,
      )
    for (plane in 0 until planeCount) {
      val dy = planeOffsetY(plane)
      for ((x, y) in corners) {
        val px = a * x + c * y + baseE
        val py = b * x + d * y + baseF + dy
        minX = min(minX, px)
        maxX = max(maxX, px)
        minY = min(minY, py)
        maxY = max(maxY, py)
      }
    }

    // The label column: a leader line out to a fixed x, then the names. Laid out BEFORE the canvas
    // is sized, because collision avoidance moves labels off their sheet's own y — at a small
    // separation on a deep stack the run of nudges carries the last label well past the lowest
    // sheet corner, and an SVG has no overflow to scroll into, so a canvas measured from the sheets
    // alone would simply crop them.
    val leaderLength = fontSize * 3.5
    val labelX = maxX + leaderLength
    val labels =
      if (!options.labels) emptyList()
      else
        layoutLabels(
          planeCount = planeCount,
          label = scan::labelFor,
          box = box,
          a = a,
          b = b,
          c = c,
          d = d,
          baseE = baseE,
          baseF = baseF,
          planeOffsetY = ::planeOffsetY,
          fontSize = fontSize,
        )
    val labelWidth = labels.maxOfOrNull { estimateTextWidth(it.text, fontSize) } ?: 0.0
    for (placement in labels) {
      // Half a line either side of the baseline: the text is centred on `labelY`
      // (`dominant-baseline: middle`).
      minY = min(minY, placement.labelY - fontSize)
      maxY = max(maxY, placement.labelY + fontSize)
    }
    val pad = max(16.0, fontSize)
    val canvasMinX = minX - pad
    val canvasMinY = minY - pad
    val canvasWidth =
      (maxX - minX) + 2 * pad + if (labels.isEmpty()) 0.0 else leaderLength + labelWidth
    val canvasHeight = (maxY - minY) + 2 * pad

    val out = newDocument()
    val outSvg = out.createElementNS(SVG_NS, "svg")
    out.appendChild(outSvg)
    // Carry the source root's presentation attributes (font-family, text-rendering, xmlns:xlink,
    // …) so text still resolves the faces the <style> block embeds; geometry is ours.
    copyAttributesExcept(root, outSvg, setOf("width", "height", "viewBox"))
    outSvg.setAttribute("width", fmt(canvasWidth))
    outSvg.setAttribute("height", fmt(canvasHeight))
    outSvg.setAttribute(
      "viewBox",
      "${fmt(canvasMinX)} ${fmt(canvasMinY)} ${fmt(canvasWidth)} ${fmt(canvasHeight)}",
    )
    outSvg.setAttribute("data-exploded", "true")
    outSvg.setAttribute("data-exploded-planes", planeCount.toString())

    // Resources, verbatim and exactly once. Duplicating them per plane would multiply the embedded
    // WOFF2 payloads by the plane count for no benefit.
    //
    // **Hoisted from wherever they sit, not just the root.** A `Modifier.clip` layer's `<clipPath>`
    // is emitted by `FigmaLayeredSvg` *inline*, as a sibling of the named group it masks, deep in
    // the content tree — so collecting only the root's resource children would drop every one of
    // them while the copied `<g clip-path="url(#clip-N)">` wrapper kept pointing at the missing id,
    // and a rounded image would spill out of its mask (or vanish outright, depending on how the
    // renderer treats a dangling reference). Hoisting is safe because a `clipPath`'s contents
    // resolve in the user space of the element that *references* it, not the one it is nested in.
    for (resource in scan.resources()) outSvg.appendChild(out.importNode(resource, true))
    outSvg.appendChild(chromeStyle(out))
    outSvg.appendChild(canvasRect(out, canvasMinX, canvasMinY, canvasWidth, canvasHeight))

    val planesGroup = out.createElementNS(SVG_NS, "g")
    planesGroup.setAttribute("class", "cp-exploded-planes")
    outSvg.appendChild(planesGroup)

    for (plane in 0 until planeCount) {
      val g = out.createElementNS(SVG_NS, "g")
      g.setAttribute("class", "cp-exploded-plane")
      g.setAttribute("data-plane", plane.toString())
      val names = scan.namesOn(plane)
      if (names.isNotEmpty()) g.setAttribute("data-layers", names.joinToString(", "))
      g.setAttribute(
        "transform",
        "matrix(${fmt(a)} ${fmt(b)} ${fmt(c)} ${fmt(d)} ${fmt(baseE)} " +
          "${fmt(baseF + planeOffsetY(plane))})",
      )
      // The sheet outline sits behind the plane's own drawing and outside the source's device
      // clip, so a plane that paints nothing at its edges still reads as a plane.
      if (options.plates) {
        val plate = out.createElementNS(SVG_NS, "rect")
        plate.setAttribute("class", "cp-exploded-plate")
        plate.setAttribute("x", fmt(box.minX))
        plate.setAttribute("y", fmt(box.minY))
        plate.setAttribute("width", fmt(box.width))
        plate.setAttribute("height", fmt(box.height))
        g.appendChild(plate)
      }
      for (child in root.childElements()) {
        if (child.localNameOf() in RESOURCE_TAGS) continue
        copyForPlane(out, child, depth = 0, plane = plane, scan = scan)?.let { g.appendChild(it) }
      }
      planesGroup.appendChild(g)
    }

    if (labels.isNotEmpty()) outSvg.appendChild(labelsGroup(out, labels, labelX, fontSize))

    return serialize(out)
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Plane assignment
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * One pass over the content tree recording, per plane, whether anything paints there and which
   * composables own it. Run before any copying so the output knows its plane count (and therefore
   * its canvas) up front.
   */
  private class Scan(private val maxDepth: Int) {
    /** Plane index → whether any drawing element landed on it. */
    private val occupied = BooleanArray(MAX_PLANES + 1)
    /** Plane index → composable names owning that plane's drawing, in document order. */
    private val names = Array(MAX_PLANES + 1) { LinkedHashSet<String>() }
    /**
     * Every resource element in the source, at whatever depth it sits, in document order — the
     * `<defs>`/`<style>` at the root *and* the `<clipPath>`s `FigmaLayeredSvg` emits inline beside
     * the layers they mask. Collected here so they can be hoisted once into the output.
     */
    private val resourceElements = ArrayList<Element>()

    fun planeOf(depth: Int): Int = min(depth, maxDepth)

    fun resources(): List<Element> = resourceElements

    /**
     * Named group → the shallowest plane any drawing in its subtree lands on. Equals the group's
     * own plane whenever it paints anything itself; for an elevated wrapper that paints only
     * through a nested child, it is that child's plane. Keyed by identity, since layer ids repeat.
     */
    private val firstPlane = java.util.IdentityHashMap<Element, Int>()

    /**
     * The one plane a group's rendered whole-group effects (its `filter`) belong on — the
     * shallowest plane it is retained on, which is where it first appears in the stack. Equal to
     * its nesting level whenever the group paints anything itself.
     */
    fun owningPlane(group: Element): Int = firstPlane[group] ?: -1

    /** Shallowest plane any drawing under [el] occupies, or null when it paints nothing at all. */
    fun walk(el: Element, depth: Int): Int? {
      val tag = el.localNameOf()
      // Recorded, then never descended into: a `<g id="material-icon-…">` inside `<defs>` is a
      // drawing to `<use>`, not a level of composable nesting.
      if (tag in RESOURCE_TAGS) {
        resourceElements.add(el)
        return null
      }
      if (tag in DRAWING_TAGS) {
        occupied[planeOf(depth)] = true
        return planeOf(depth)
      }
      if (tag !in GROUP_TAGS) return null
      val id = el.getAttribute("id").takeIf { it.isNotBlank() }
      val childDepth = if (id != null) depth + 1 else depth
      var first: Int? = null
      for (child in el.childElements()) {
        val childPlane = walk(child, childDepth) ?: continue
        if (first == null || childPlane < first) first = childPlane
      }
      if (id != null) {
        firstPlane[el] = first ?: planeOf(childDepth)
        // The LABEL stays on the nesting level, not on `first`. The label column's whole claim is
        // "this sheet is depth N of the composable tree", so a non-painting wrapper belongs at its
        // own level — moving its name down to wherever its child happens to draw would leave a
        // nameless sheet above it and misreport the structure.
        val label = displayName(el, id)
        if (label != null) names[planeOf(childDepth)].add(label)
      }
      return first
    }

    /**
     * The name to show for a layer. `data-material-icon` is preferred over the layer id whenever
     * the id is one of the tree's fallbacks, because "menu" says more than "ReusableComposeNode"; a
     * fallback id with no icon annotation names nothing and is dropped.
     */
    private fun displayName(el: Element, id: String): String? {
      if (id !in UNINFORMATIVE_LAYER_NAMES) return id
      val icon = el.getAttribute("data-material-icon").takeIf { it.isNotBlank() } ?: return null
      return "$icon icon"
    }

    /** Number of planes that actually paint, counted from plane 0 up to the last occupied one. */
    fun planeCount(): Int = (occupied.indexOfLast { it } + 1).coerceAtMost(MAX_PLANES + 1)

    fun namesOn(plane: Int): List<String> = names[plane].toList()

    /** The label for a plane: its first few composables, then a count of the rest. */
    fun labelFor(plane: Int): String {
      val all = namesOn(plane)
      if (all.isEmpty()) return if (plane == 0) "Frame" else "Layer $plane"
      val shown = all.take(LABEL_NAME_LIMIT).joinToString(" · ") { it.ellipsize(LABEL_NAME_CHARS) }
      val rest = all.size - LABEL_NAME_LIMIT
      return if (rest > 0) "$shown +$rest" else shown
    }
  }

  /**
   * Composables named per label before the rest become a `+N` count, and the length each name is
   * held to. Both exist to bound the *canvas*: the label column is reserved from the longest label,
   * so one `OutlinedTextFieldDecorationBox` on a busy plane would otherwise double the width of
   * every exploded view. The full list stays on the plane's `data-layers` attribute.
   */
  private const val LABEL_NAME_LIMIT = 2

  private const val LABEL_NAME_CHARS = 22

  private fun String.ellipsize(limit: Int): String =
    if (length <= limit) this else take(limit - 1).trimEnd() + "…"

  /**
   * Structural copy of [src] retaining only the drawing elements on [plane]. Returns null when
   * nothing on this plane survives, so empty scaffolding is never emitted.
   *
   * Groups are copied for their `transform` / `clip-path` / `opacity`, which is what keeps a
   * plane's elements in the position and shape they had in the flat drawing. Two attributes
   * describe the group *as a whole* and so must not be repeated onto every fragment; they answer
   * different questions, so they get different rules (see the call site).
   */
  private fun copyForPlane(
    out: Document,
    src: Element,
    depth: Int,
    plane: Int,
    scan: Scan,
  ): Element? {
    val tag = src.localNameOf()
    if (tag in RESOURCE_TAGS) return null
    if (tag in DRAWING_TAGS) {
      return if (scan.planeOf(depth) == plane) out.importNode(src, true) as Element else null
    }
    if (tag !in GROUP_TAGS) return null
    val id = src.getAttribute("id").takeIf { it.isNotBlank() }
    val childDepth = if (id != null) depth + 1 else depth
    val kept = src.childElements().mapNotNull { copyForPlane(out, it, childDepth, plane, scan) }
    if (kept.isEmpty()) return null
    val copy = out.createElementNS(SVG_NS, tag)
    // Two different "once" rules, because the two attributes answer different questions.
    val skip = HashSet<String>(2)
    // `id` names the composable at its nesting level, exactly where the label column puts it.
    if (scan.planeOf(childDepth) != plane) skip.add("id")
    // `filter` is a rendered effect and must survive: it rides the shallowest plane the group is
    // retained on, which is the nesting level whenever the group paints anything itself.
    if (id != null && scan.owningPlane(src) != plane) skip.add("filter")
    copyAttributesExcept(src, copy, skip)
    for (child in kept) copy.appendChild(child)
    return copy
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Labels
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * The leader lines + names beside the stack. Each leader starts at the projected midpoint of its
   * sheet's right edge and runs out to a shared x, so the labels form one column — the same reading
   * order as the sheets. A label that would collide with the one above it is nudged down and its
   * leader gains a bend rather than overlapping.
   */
  @Suppress("LongParameterList")
  /** One label's resolved geometry: where its leader starts, and where its text ends up. */
  private data class LabelPlacement(
    val plane: Int,
    val text: String,
    val anchorX: Double,
    val anchorY: Double,
    val labelY: Double,
  )

  /**
   * Resolve every label's position without emitting anything, so the caller can size the canvas
   * around the result.
   *
   * A label starts level with the projected midpoint of its sheet's right edge. Sheets are emitted
   * bottom-up but read top-down, so the pass runs from the topmost and pushes each subsequent label
   * down to keep a minimum line spacing — which means the column can extend past the lowest sheet
   * corner whenever the separation is smaller than a line of text. That overflow is exactly what
   * the caller has to include in the viewBox.
   */
  @Suppress("LongParameterList")
  private fun layoutLabels(
    planeCount: Int,
    label: (Int) -> String,
    box: ViewBox,
    a: Double,
    b: Double,
    c: Double,
    d: Double,
    baseE: Double,
    baseF: Double,
    planeOffsetY: (Int) -> Double,
    fontSize: Double,
  ): List<LabelPlacement> {
    val edgeX = box.minX + box.width
    val edgeY = box.minY + box.height / 2.0
    val anchorX = a * edgeX + c * edgeY + baseE
    val minSpacing = fontSize * 1.6
    val out = ArrayList<LabelPlacement>(planeCount)
    var previousY: Double? = null
    for (plane in (planeCount - 1) downTo 0) {
      val anchorY = b * edgeX + d * edgeY + baseF + planeOffsetY(plane)
      val labelY = previousY?.let { max(anchorY, it + minSpacing) } ?: anchorY
      previousY = labelY
      out.add(LabelPlacement(plane, label(plane), anchorX, anchorY, labelY))
    }
    return out
  }

  private fun labelsGroup(
    out: Document,
    placements: List<LabelPlacement>,
    labelX: Double,
    fontSize: Double,
  ): Element {
    val group = out.createElementNS(SVG_NS, "g")
    group.setAttribute("class", "cp-exploded-labels")
    group.setAttribute("font-size", fmt(fontSize))

    for ((plane, text, anchorX, anchorY, labelY) in placements) {
      val dot = out.createElementNS(SVG_NS, "circle")
      dot.setAttribute("class", "cp-exploded-leader-dot")
      dot.setAttribute("cx", fmt(anchorX))
      dot.setAttribute("cy", fmt(anchorY))
      dot.setAttribute("r", fmt(max(1.5, fontSize * 0.14)))
      group.appendChild(dot)

      val leader = out.createElementNS(SVG_NS, "path")
      leader.setAttribute("class", "cp-exploded-leader")
      val bendX = anchorX + (labelX - anchorX) * 0.55
      leader.setAttribute(
        "d",
        if (abs(labelY - anchorY) < 0.5)
          "M${fmt(anchorX)} ${fmt(anchorY)} H${fmt(labelX - fontSize * 0.4)}"
        else
          "M${fmt(anchorX)} ${fmt(anchorY)} H${fmt(bendX)} L${fmt(labelX - fontSize * 0.4)} " +
            fmt(labelY),
      )
      group.appendChild(leader)

      val label = out.createElementNS(SVG_NS, "text")
      label.setAttribute("class", "cp-exploded-label")
      label.setAttribute("data-plane", plane.toString())
      label.setAttribute("x", fmt(labelX))
      label.setAttribute("y", fmt(labelY))
      label.setAttribute("dominant-baseline", "middle")
      label.appendChild(out.createTextNode(text))
      group.appendChild(label)
    }
    return group
  }

  /**
   * The exploded view's own chrome, as a stylesheet rather than per-element attributes: it is a
   * handful of rules shared by every plate, leader and label, and keeping it in one block lets the
   * dark-scheme override be two lines.
   *
   * **Fixed light chrome, not `prefers-color-scheme`** — the one deliberate call here. Unlike the
   * flat export (a drawing meant to be dropped onto someone else's canvas), this is a **diagram**,
   * so it paints its own [canvasRect] backdrop and stays readable wherever it is embedded: a PR
   * body, a doc, a viewer stage. Making that pair scheme-aware sounds better and is worse, because
   * `prefers-color-scheme` answers for the reader's *OS* while the background the SVG actually
   * lands on is decided by the host — the viewer's stage is light whatever the OS says, so an
   * OS-dark reader got a dark card floating in a white stage, and before the backdrop existed at
   * all, light-grey labels invisible on white. One fixed pairing can be wrong about the
   * surroundings; it can never be wrong about itself.
   */
  private fun chromeStyle(out: Document): Element {
    val style = out.createElementNS(SVG_NS, "style")
    style.appendChild(
      out.createTextNode(
        ".cp-exploded-canvas{fill:#ffffff}" +
          ".cp-exploded-plate{fill:none;stroke:#9aa0a6;stroke-width:1;stroke-dasharray:5 5;" +
          "opacity:.55}" +
          ".cp-exploded-leader{fill:none;stroke:#9aa0a6;stroke-width:1}" +
          ".cp-exploded-leader-dot{fill:#5f6368}" +
          ".cp-exploded-label{fill:#3c4043;font-family:sans-serif}"
      )
    )
    return style
  }

  /**
   * The diagram's backdrop, spanning the whole canvas and drawn under every sheet. See
   * [chromeStyle] for why an exploded view carries one where the flat export deliberately doesn't.
   */
  private fun canvasRect(
    out: Document,
    minX: Double,
    minY: Double,
    width: Double,
    height: Double,
  ): Element {
    val rect = out.createElementNS(SVG_NS, "rect")
    rect.setAttribute("class", "cp-exploded-canvas")
    rect.setAttribute("x", fmt(minX))
    rect.setAttribute("y", fmt(minY))
    rect.setAttribute("width", fmt(width))
    rect.setAttribute("height", fmt(height))
    return rect
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Geometry + XML plumbing
  // ───────────────────────────────────────────────────────────────────────────

  private data class ViewBox(
    val minX: Double,
    val minY: Double,
    val width: Double,
    val height: Double,
  )

  /**
   * Separation derived from the drawing itself, used when the caller names no [Options.gap].
   *
   * Scaled off the **long** edge, because that is what the projected sheet's own height tracks: a
   * separation set from a phone preview's narrow width leaves the sheets overlapping by most of
   * their area, which reads as a smeared stack rather than an exploded one. A quarter of the long
   * edge puts roughly that fraction of a sheet's height between neighbours — the proportion a
   * printed exploded diagram uses, where sheets overlap substantially and are told apart by their
   * offset edges rather than by standing clear of each other. The whole stack is then held to
   * [STACK_BUDGET] long-edges so a deep tree stays a picture instead of a ribbon, and floored so it
   * never collapses to nothing.
   */
  private fun autoGap(box: ViewBox, planeCount: Int): Double {
    val long = max(box.width, box.height)
    val budget = long * STACK_BUDGET / max(1, planeCount - 1)
    return min(long * 0.25, budget).coerceAtLeast(long * 0.06)
  }

  /** Total sheet separation, in long-edges of the source drawing, that [autoGap] will spend. */
  private const val STACK_BUDGET = 1.6

  /**
   * Ceiling for a caller-supplied [Options.gap], in long-edges of the source drawing. Generous —
   * four sheet-heights apart is already far looser than anything the viewer's slider offers — but
   * finite, so a hand-typed or stale `?explodeGap=3000000` produces a very spread-out picture
   * instead of a canvas whose numbers no longer fit the format.
   */
  private fun maxGap(box: ViewBox): Double = max(box.width, box.height) * 4.0

  private fun viewBoxOf(root: Element): ViewBox? {
    val raw = root.getAttribute("viewBox").trim()
    if (raw.isNotEmpty()) {
      val parts = raw.split(Regex("[,\\s]+")).mapNotNull { it.toDoubleOrNull() }
      if (parts.size == 4) return ViewBox(parts[0], parts[1], parts[2], parts[3])
    }
    val w = lengthOf(root.getAttribute("width")) ?: return null
    val h = lengthOf(root.getAttribute("height")) ?: return null
    return ViewBox(0.0, 0.0, w, h)
  }

  /** `"486"` / `"486px"` → `486.0`; anything relative (`50%`) or unparseable → null. */
  private fun lengthOf(raw: String): Double? {
    val trimmed = raw.trim().removeSuffix("px")
    return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
  }

  /**
   * Width a label will occupy, without a font metric to ask. Deliberately generous — this only
   * sizes the canvas gutter, and under-reserving clips the text where over-reserving costs
   * whitespace.
   */
  private fun estimateTextWidth(text: String, fontSize: Double): Double =
    text.length * fontSize * 0.58

  private fun parse(svg: String): Document? =
    try {
      val factory = DocumentBuilderFactory.newInstance()
      // Untrusted input (a catalog's baked export, or a stranger's uploaded bundle): no DTDs, no
      // external entities, no schema resolution — the parse must not become a fetch.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      factory.isNamespaceAware = true
      factory.isExpandEntityReferences = false
      factory.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) {
      null
    }

  private fun newDocument(): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    return factory.newDocumentBuilder().newDocument()
  }

  private fun serialize(doc: Document): String {
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    transformer.setOutputProperty(OutputKeys.INDENT, "no")
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    return writer.toString()
  }

  private fun copyAttributesExcept(src: Element, dst: Element, skip: Set<String>) {
    val attrs = src.attributes
    for (i in 0 until attrs.length) {
      val attr = attrs.item(i)
      val name = attr.nodeName
      if (name in skip) continue
      if (attr.namespaceURI != null && attr.namespaceURI != SVG_NS) {
        dst.setAttributeNS(attr.namespaceURI, name, attr.nodeValue)
      } else {
        dst.setAttribute(name, attr.nodeValue)
      }
    }
  }

  private fun Element.localNameOf(): String = localName ?: tagName

  private fun Element.childElements(): List<Element> {
    val out = ArrayList<Element>()
    var node: Node? = firstChild
    while (node != null) {
      if (node is Element) out.add(node)
      node = node.nextSibling
    }
    return out
  }

  /**
   * Compact number formatting — SVG attributes carry a lot of these and trailing zeros add up.
   *
   * Rounds through `Long`, not `Int`: `(value * 1000).roundToInt()` saturates at `Int.MAX_VALUE`
   * for anything past ~2.1e6, so a single absurd coordinate would not merely be ugly but would
   * *silently collapse* every dimension it touched onto 2147483.647 — a cropped or empty picture
   * rather than a large one. [Options.gap] is clamped upstream so this is defence in depth, but the
   * formatter is the last place a bad number can still turn into a wrong drawing.
   */
  private fun fmt(value: Double): String {
    if (!value.isFinite()) return "0"
    val rounded = Math.round(value * 1000.0) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else rounded.toString()
  }
}
