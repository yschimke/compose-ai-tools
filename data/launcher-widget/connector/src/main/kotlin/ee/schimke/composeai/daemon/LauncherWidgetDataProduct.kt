package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.LauncherResizeOrder
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetPayload
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `AroundComposable` extension that wraps a preview in a launcher-widget-shaped grid-cell
 * container.
 *
 * The wrapper resolves its dp size by snapping the requested [LauncherWidgetOverride.cells] into
 * the `minCells`..`maxCells` bounds (defaulting to `1×1`..`5×5`) and multiplying by the configured
 * cell edge (`72.dp` default) plus inter-cell spacing (`8.dp` default). Same arithmetic Android's
 * launcher uses when it lays a widget into its `N×M` cell grid, so a held preview can be reviewed
 * at the exact dp footprint the runtime grid would assign.
 *
 * **Single-shot behaviour.** A single `renderNow` snaps directly to the clamped target. The
 * stepped-resize animation the original feature request described (walk through whole-cell stops
 * with brief pauses) lives one level up — a future daemon-side orchestrator drives multiple
 * `renderNow` calls walking the [launcherWidgetStops] path. The connector here is intentionally
 * stateless so the orchestrator owns the loop and the connector stays a pure-projection
 * around-composable.
 *
 * Phase ordering: [DataExtensionPhase.OuterEnvironment] so the launcher-widget size box sits
 * outside theme / wallpaper wrappers — the widget's bounding cell box is conceptually part of the
 * launcher chrome, not the preview's own surface chemistry.
 */
class LauncherWidgetExtension(private val override: LauncherWidgetOverride) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {

  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    val resolved = override.resolve()
    val widthDp =
      (resolved.cellSizeDp * resolved.cells.width +
          resolved.cellSpacingDp * maxOf(0, resolved.cells.width - 1))
        .dp
    val heightDp =
      (resolved.cellSizeDp * resolved.cells.height +
          resolved.cellSpacingDp * maxOf(0, resolved.cells.height - 1))
        .dp
    Box(modifier = Modifier.size(widthDp, heightDp), content = { content() })
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("compose/launcher-widget")
  }
}

/**
 * Planner that maps [PreviewOverrides.launcherWidget] to a [LauncherWidgetExtension] — abstains
 * when the field is null. Registered alongside the other connector planners in `RobolectricHost`'s
 * `previewOverrideExtensions` list (Android) and `buildDesktopExtensions(...)` (desktop).
 */
class LauncherWidgetPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = LauncherWidgetExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.launcherWidget?.let(::LauncherWidgetExtension)
}

/** Connector-side defaults applied when the override leaves a knob `null`. */
private const val DEFAULT_CELL_SIZE_DP: Int = 72
private const val DEFAULT_CELL_SPACING_DP: Int = 8
private val DEFAULT_MIN_CELLS: LauncherWidgetSize = LauncherWidgetSize(1, 1)
private val DEFAULT_MAX_CELLS: LauncherWidgetSize = LauncherWidgetSize(5, 5)

/**
 * Resolution of an incoming [LauncherWidgetOverride] into the concrete values the connector uses
 * for layout. Caller-supplied bounds are honoured; missing bounds fall back to the connector's
 * defaults. Cells are clamped into the bounds before being handed to the layout pass.
 */
internal data class ResolvedLauncherWidget(
  val cells: LauncherWidgetSize,
  val cellSizeDp: Int,
  val cellSpacingDp: Int,
)

internal fun LauncherWidgetOverride.resolve(): ResolvedLauncherWidget {
  val min = minCells ?: DEFAULT_MIN_CELLS
  val max = maxCells ?: DEFAULT_MAX_CELLS
  require(min.width <= max.width && min.height <= max.height) {
    "LauncherWidgetOverride bounds are inverted: min=$min max=$max"
  }
  val clamped =
    LauncherWidgetSize(
      cells.width.coerceIn(min.width, max.width),
      cells.height.coerceIn(min.height, max.height),
    )
  return ResolvedLauncherWidget(
    cells = clamped,
    cellSizeDp = (cellSizeDp ?: DEFAULT_CELL_SIZE_DP).coerceAtLeast(0),
    cellSpacingDp = (cellSpacingDp ?: DEFAULT_CELL_SPACING_DP).coerceAtLeast(0),
  )
}

/**
 * Whole-cell stops a future orchestrator walks when animating between two whole-cell sizes under
 * the requested [order].
 *
 * For [LauncherResizeOrder.DIAGONAL] the number of stops is `max(|dw|, |dh|) + 1` — the diagonal
 * distance, not the L1 sum, so a `1×1 → 4×2` resize visits four cells (`1×1, 2×1, 3×2, 4×2`). For
 * [LauncherResizeOrder.WIDTH_FIRST] / [LauncherResizeOrder.HEIGHT_FIRST] the stops walk one axis to
 * completion before touching the other — number of stops is `|dw| + |dh| + 1`, one cell per
 * launcher-handle drag.
 *
 * Returned list always includes both endpoints. When `from == to` it collapses to a single stop so
 * the caller's loop terminates cleanly without special-casing zero-delta resizes.
 *
 * Exposed publicly so the daemon-side resize-loop orchestrator (and any client building its own)
 * can share the algorithm.
 */
fun launcherWidgetStops(
  from: LauncherWidgetSize,
  to: LauncherWidgetSize,
  order: LauncherResizeOrder = LauncherResizeOrder.WIDTH_FIRST,
): List<LauncherWidgetSize> {
  if (from == to) return listOf(from)
  return when (order) {
    LauncherResizeOrder.DIAGONAL -> diagonalStops(from, to)
    LauncherResizeOrder.WIDTH_FIRST -> axisFirstStops(from, to, widthFirst = true)
    LauncherResizeOrder.HEIGHT_FIRST -> axisFirstStops(from, to, widthFirst = false)
  }
}

private fun diagonalStops(
  from: LauncherWidgetSize,
  to: LauncherWidgetSize,
): List<LauncherWidgetSize> {
  val dw = to.width - from.width
  val dh = to.height - from.height
  val n = maxOf(abs(dw), abs(dh))
  return (0..n).map { i ->
    LauncherWidgetSize(
      from.width + Math.round(dw.toDouble() * i / n).toInt(),
      from.height + Math.round(dh.toDouble() * i / n).toInt(),
    )
  }
}

private fun axisFirstStops(
  from: LauncherWidgetSize,
  to: LauncherWidgetSize,
  widthFirst: Boolean,
): List<LauncherWidgetSize> {
  val stops = mutableListOf(from)
  if (widthFirst) {
    walkAxis(from.width, to.width) { w -> stops.add(LauncherWidgetSize(w, from.height)) }
    walkAxis(from.height, to.height) { h -> stops.add(LauncherWidgetSize(to.width, h)) }
  } else {
    walkAxis(from.height, to.height) { h -> stops.add(LauncherWidgetSize(from.width, h)) }
    walkAxis(from.width, to.width) { w -> stops.add(LauncherWidgetSize(w, to.height)) }
  }
  return stops
}

private inline fun walkAxis(from: Int, to: Int, emit: (Int) -> Unit) {
  if (from == to) return
  val step = if (to > from) 1 else -1
  var v = from
  while (v != to) {
    v += step
    emit(v)
  }
}

/**
 * Daemon-side registry adapter for `compose/launcher-widget`.
 *
 * Captures the resolved (post-clamp) cell count + dp footprint last applied via
 * `renderNow.overrides.launcherWidget` per preview. A `data/fetch` after a launcher-widget-aware
 * render returns the captured payload; before any render or after the override is dropped, it
 * returns [DataProductRegistry.Outcome.NotAvailable]. There is no re-render mode — clients update
 * the cells by sending a fresh `renderNow.overrides.launcherWidget`.
 *
 * Mirrors the shape of [WallpaperDataProductRegistry] one-for-one.
 */
class LauncherWidgetDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, LauncherWidgetPayload>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  fun capture(previewId: String?, payload: LauncherWidgetPayload) {
    if (previewId == null) return
    latestPayloads[previewId] = payload
  }

  fun clear(previewId: String?) {
    if (previewId == null) return
    latestPayloads.remove(previewId)
  }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latestPayloads[previewId] ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(LauncherWidgetPayload.serializer(), payload),
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = latestPayloads[previewId] ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(LauncherWidgetPayload.serializer(), payload),
      )
    )
  }

  override fun onRender(previewId: String, result: RenderResult) {
    onRender(previewId, result, overrides = null, previewContext = result.previewContext)
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    val applied = overrides?.launcherWidget
    if (applied == null) {
      clear(previewId)
      return
    }
    val resolved = applied.resolve()
    val widthDp =
      resolved.cellSizeDp * resolved.cells.width +
        resolved.cellSpacingDp * maxOf(0, resolved.cells.width - 1)
    val heightDp =
      resolved.cellSizeDp * resolved.cells.height +
        resolved.cellSpacingDp * maxOf(0, resolved.cells.height - 1)
    capture(
      previewId,
      LauncherWidgetPayload(
        cells = resolved.cells,
        cellSizeDp = resolved.cellSizeDp,
        cellSpacingDp = resolved.cellSpacingDp,
        widthDp = widthDp,
        heightDp = heightDp,
        resizeOrder = applied.resizeOrder,
      ),
    )
  }

  companion object {
    const val KIND: String = "compose/launcher-widget"
    const val SCHEMA_VERSION: Int = 1

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
