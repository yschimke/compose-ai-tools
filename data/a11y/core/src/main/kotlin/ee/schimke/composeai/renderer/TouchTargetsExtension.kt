package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Derives clickable target sizes + overlap findings from an [AccessibilityHierarchyPayload]
 * snapshot. Pure platform-agnostic transformation — Android and Desktop hierarchy producers can
 * both feed this without duplicating the math.
 *
 * Inputs: [AccessibilityDataProducts.Hierarchy] (nodes), [CommonDataProducts.Density] (dp scale).
 * Output: [AccessibilityDataProducts.TouchTargets].
 */
class TouchTargetsExtension : PostCaptureProcessor {
  override val id: DataExtensionId = DataExtensionId(EXTENSION_ID)
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.PostProcess)
  override val inputs: Set<DataProductKey<*>> =
    setOf(AccessibilityDataProducts.Hierarchy, CommonDataProducts.Density)
  override val outputs: Set<DataProductKey<*>> = setOf(AccessibilityDataProducts.TouchTargets)

  override fun process(context: ExtensionPostCaptureContext) {
    val hierarchy = context.products.require(AccessibilityDataProducts.Hierarchy)
    val density = context.products.require(CommonDataProducts.Density)
    context.products.put(
      AccessibilityDataProducts.TouchTargets,
      AccessibilityTouchTargetsPayload(buildTouchTargets(hierarchy.nodes, density.density)),
    )
  }

  companion object {
    const val EXTENSION_ID: String = "a11y-touch-targets"
  }
}

/**
 * Public counterpart to the previously-internal `AccessibilityDataProducer.buildTouchTargets`.
 * Kept here so both the extension and the on-disk producer call into the same logic until the
 * connector layer migrates to consuming the typed product directly.
 */
fun buildTouchTargets(
  nodes: List<AccessibilityNode>,
  density: Float,
): List<AccessibilityTouchTarget> {
  val scale = density.takeIf { it > 0f } ?: 1f
  val candidates =
    nodes.mapIndexedNotNull { index, node ->
      if (!node.states.any { it == "clickable" || it == "long-clickable" }) return@mapIndexedNotNull null
      val rect = parseBounds(node.boundsInScreen) ?: return@mapIndexedNotNull null
      TouchTargetCandidate(
        nodeId = "node-$index",
        boundsInScreen = node.boundsInScreen,
        rect = rect,
        widthDp = rect.widthPx / scale,
        heightDp = rect.heightPx / scale,
      )
    }

  for (i in candidates.indices) {
    for (j in i + 1 until candidates.size) {
      val a = candidates[i]
      val b = candidates[j]
      if (a.rect.overlaps(b.rect) && !a.rect.contains(b.rect) && !b.rect.contains(a.rect)) {
        a.overlappingNodeIds += b.nodeId
        b.overlappingNodeIds += a.nodeId
      }
    }
  }

  return candidates.map { candidate ->
    val findings = mutableListOf<String>()
    if (candidate.widthDp < MIN_TOUCH_TARGET_DP || candidate.heightDp < MIN_TOUCH_TARGET_DP) {
      findings += FINDING_BELOW_MINIMUM
    }
    if (candidate.overlappingNodeIds.isNotEmpty()) findings += FINDING_OVERLAPPING
    AccessibilityTouchTarget(
      nodeId = candidate.nodeId,
      boundsInScreen = candidate.boundsInScreen,
      widthDp = candidate.widthDp,
      heightDp = candidate.heightDp,
      findings = findings,
      overlappingNodeIds = candidate.overlappingNodeIds.takeIf { it.isNotEmpty() },
    )
  }
}

private data class TouchTargetCandidate(
  val nodeId: String,
  val boundsInScreen: String,
  val rect: BoundsRect,
  val widthDp: Float,
  val heightDp: Float,
  val overlappingNodeIds: MutableList<String> = mutableListOf(),
)

private data class BoundsRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val widthPx: Int = (right - left).coerceAtLeast(0)
  val heightPx: Int = (bottom - top).coerceAtLeast(0)

  fun overlaps(other: BoundsRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

  fun contains(other: BoundsRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

private fun parseBounds(bounds: String): BoundsRect? {
  val parts = bounds.split(',')
  if (parts.size != 4) return null
  val values = parts.map { it.trim().toIntOrNull() ?: return null }
  return BoundsRect(values[0], values[1], values[2], values[3])
}

private const val MIN_TOUCH_TARGET_DP = 48f
private const val FINDING_BELOW_MINIMUM = "belowMinimum"
private const val FINDING_OVERLAPPING = "overlapping"
