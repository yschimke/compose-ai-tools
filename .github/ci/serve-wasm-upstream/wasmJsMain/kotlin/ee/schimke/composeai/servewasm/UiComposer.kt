package ee.schimke.composeai.servewasm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.designcatalogm3.shared.CatalogComponent
import com.example.designcatalogm3.shared.LocalWasmCatalogKnobs
import com.example.designcatalogm3.shared.catalogComponentIds
import com.example.designcatalogm3.shared.catalogTypography
import ee.schimke.composeai.preview.slots.LocalPreviewSlotHost
import ee.schimke.composeai.preview.slots.PreviewSlotHost
import ee.schimke.composeai.preview.slots.PreviewSlotInfo
import ee.schimke.composeai.preview.slots.PreviewSlotSizing
import kotlin.math.roundToInt

internal data class ComposerItem(
  val key: Int,
  val componentId: String,
  val slots: Map<String, ComposerItem> = emptyMap(),
)

internal data class ComposerSlotTarget(val hostKey: Int, val slotName: String)

private data class ComposerDrag(
  val componentId: String,
  val sourceKey: Int?,
  val position: Offset,
)

/** Move one item to the insertion point represented by [targetIndex]. */
internal fun moveComposerItem(
  items: List<ComposerItem>,
  sourceIndex: Int,
  targetIndex: Int,
): List<ComposerItem> {
  if (sourceIndex !in items.indices) return items
  val moving = items[sourceIndex]
  val remaining = items.toMutableList().also { it.removeAt(sourceIndex) }
  val adjustedTarget =
    (if (targetIndex > sourceIndex) targetIndex - 1 else targetIndex).coerceIn(0, remaining.size)
  remaining.add(adjustedTarget, moving)
  return remaining
}

internal fun composerDropIndex(y: Float, itemCenters: List<Float>): Int =
  itemCenters.indexOfFirst { y < it }.takeIf { it >= 0 } ?: itemCenters.size

internal fun composerItemByKey(items: List<ComposerItem>, key: Int): ComposerItem? =
  items.firstNotNullOfOrNull { item ->
    if (item.key == key) item else composerItemByKey(item.slots.values.toList(), key)
  }

internal fun removeComposerItem(items: List<ComposerItem>, key: Int): List<ComposerItem> =
  items.mapNotNull { item ->
    when {
      item.key == key -> null
      else ->
        item.copy(
          slots =
            item.slots
              .mapNotNull { (name, child) ->
                if (child.key == key) null
                else name to removeComposerItem(listOf(child), key).single()
              }
              .toMap()
        )
    }
  }

internal fun putComposerItemInSlot(
  items: List<ComposerItem>,
  target: ComposerSlotTarget,
  child: ComposerItem,
): List<ComposerItem> = items.map { item ->
  when {
    item.key == target.hostKey -> item.copy(slots = item.slots + (target.slotName to child))
    else ->
      item.copy(
        slots =
          item.slots.mapValues { (_, nested) ->
            putComposerItemInSlot(listOf(nested), target, child).single()
          }
      )
  }
}

internal fun putComposerSlots(
  items: List<ComposerItem>,
  hostKey: Int,
  slots: Map<String, ComposerItem>,
): List<ComposerItem> = items.map { item ->
  when {
    item.key == hostKey -> item.copy(slots = slots)
    else ->
      item.copy(
        slots =
          item.slots.mapValues { (_, child) ->
            putComposerSlots(listOf(child), hostKey, slots).single()
          }
      )
  }
}

internal fun composerSlotAt(
  position: Offset,
  bounds: Map<ComposerSlotTarget, Rect>,
): ComposerSlotTarget? =
  bounds
    .filterValues { it.contains(position) }
    .minByOrNull { (_, rect) -> rect.width * rect.height }
    ?.key

internal fun composerItemCount(items: List<ComposerItem>): Int = items.sumOf { item ->
  1 + composerItemCount(item.slots.values.toList())
}

@Composable
internal fun UiComposer(compact: Boolean) {
  var items by remember {
    mutableStateOf(
      listOf(
        ComposerItem(0, "text-maxlines-truncated"),
        ComposerItem(1, "card-slots"),
        ComposerItem(2, "switch-on"),
        ComposerItem(3, "button-filled"),
      )
    )
  }
  var nextKey by remember { mutableStateOf(4) }
  var selectedKey by remember { mutableStateOf<Int?>(1) }
  var dark by remember { mutableStateOf(false) }
  var previewMode by remember { mutableStateOf(false) }
  var spacing by remember { mutableStateOf(16f) }
  var padding by remember { mutableStateOf(24f) }
  var composerBounds by remember { mutableStateOf(Rect.Zero) }
  var canvasBounds by remember { mutableStateOf(Rect.Zero) }
  val itemBounds = remember { mutableMapOf<Int, Rect>() }
  val slotBounds = remember { mutableMapOf<ComposerSlotTarget, Rect>() }
  val slotInfos = remember { mutableStateMapOf<ComposerSlotTarget, PreviewSlotInfo>() }
  var drag by remember { mutableStateOf<ComposerDrag?>(null) }

  fun add(componentId: String, index: Int = items.size) {
    val item = ComposerItem(nextKey++, componentId)
    items = items.toMutableList().also { it.add(index.coerceIn(0, it.size), item) }
    selectedKey = item.key
  }

  fun finishDrag() {
    val dropped = drag ?: return
    val slotTarget = composerSlotAt(dropped.position, slotBounds)
    if (slotTarget != null && composerItemByKey(items, slotTarget.hostKey) != null) {
      val sourceItem = dropped.sourceKey?.let { composerItemByKey(items, it) }
      val wouldCreateCycle =
        sourceItem?.let { composerItemByKey(listOf(it), slotTarget.hostKey) } != null
      if (!wouldCreateCycle) {
        val child = sourceItem ?: ComposerItem(nextKey++, dropped.componentId)
        val withoutSource =
          dropped.sourceKey?.let { sourceKey -> removeComposerItem(items, sourceKey) } ?: items
        items = putComposerItemInSlot(withoutSource, slotTarget, child)
        selectedKey = slotTarget.hostKey
      }
      drag = null
      return
    }
    if (canvasBounds.contains(dropped.position)) {
      val centers = items.mapNotNull { itemBounds[it.key]?.center?.y }
      val target = composerDropIndex(dropped.position.y, centers)
      val source = dropped.sourceKey?.let { key -> items.indexOfFirst { it.key == key } }
      if (source != null && source >= 0) {
        items = moveComposerItem(items, source, target)
        selectedKey = dropped.sourceKey
      } else {
        add(dropped.componentId, target)
      }
    }
    drag = null
  }

  val startDrag: (String, Int?, Offset) -> Unit = { id, key, position ->
    drag = ComposerDrag(id, key, position)
  }
  val updateDrag: (Offset) -> Unit = { amount ->
    drag = drag?.let { it.copy(position = it.position + amount) }
  }

  Box(Modifier.fillMaxSize().onGloballyPositioned { composerBounds = it.boundsInRoot() }) {
    if (compact) {
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        ComposerPalette(
          Modifier.fillMaxWidth().height(420.dp),
          onAdd = ::add,
          startDrag,
          updateDrag,
          ::finishDrag,
        )
        ComposerCanvas(
          items = items,
          selectedKey = selectedKey,
          dark = dark,
          previewMode = previewMode,
          spacing = spacing,
          padding = padding,
          modifier = Modifier.fillMaxWidth().height(620.dp),
          onSelect = { selectedKey = it },
          onBounds = { canvasBounds = it },
          onItemBounds = { key, bounds -> itemBounds[key] = bounds },
          onSlotBounds = { target, bounds -> slotBounds[target] = bounds },
          onSlotDiscovered = { target, info -> slotInfos[target] = info },
          hoveredSlot = drag?.position?.let { composerSlotAt(it, slotBounds) },
          onDragStart = startDrag,
          onDrag = updateDrag,
          onDragEnd = ::finishDrag,
        )
        ComposerInspector(
          items = items,
          selectedKey = selectedKey,
          dark = dark,
          previewMode = previewMode,
          spacing = spacing,
          padding = padding,
          modifier = Modifier.fillMaxWidth().height(650.dp),
          onDark = { dark = it },
          onPreviewMode = { previewMode = it },
          onSpacing = { spacing = it },
          onPadding = { padding = it },
          onItems = { items = it },
          onSelect = { selectedKey = it },
          onDuplicate = { id -> add(id, items.indexOfFirst { it.key == selectedKey } + 1) },
          slotInfos = slotInfos,
          onClearSlot = { target ->
            items =
              composerItemByKey(items, target.hostKey)?.let { host ->
                putComposerSlots(items, host.key, host.slots - target.slotName)
              } ?: items
          },
        )
      }
    } else {
      Row(Modifier.fillMaxSize()) {
        ComposerPalette(
          Modifier.width(260.dp).fillMaxHeight(),
          onAdd = ::add,
          onDragStart = startDrag,
          onDrag = updateDrag,
          onDragEnd = ::finishDrag,
        )
        HorizontalDivider(
          Modifier.fillMaxHeight().width(1.dp),
          color = MaterialTheme.colorScheme.outline,
        )
        ComposerCanvas(
          items = items,
          selectedKey = selectedKey,
          dark = dark,
          previewMode = previewMode,
          spacing = spacing,
          padding = padding,
          modifier = Modifier.weight(1f).fillMaxHeight(),
          onSelect = { selectedKey = it },
          onBounds = { canvasBounds = it },
          onItemBounds = { key, bounds -> itemBounds[key] = bounds },
          onSlotBounds = { target, bounds -> slotBounds[target] = bounds },
          onSlotDiscovered = { target, info -> slotInfos[target] = info },
          hoveredSlot = drag?.position?.let { composerSlotAt(it, slotBounds) },
          onDragStart = startDrag,
          onDrag = updateDrag,
          onDragEnd = ::finishDrag,
        )
        HorizontalDivider(
          Modifier.fillMaxHeight().width(1.dp),
          color = MaterialTheme.colorScheme.outline,
        )
        ComposerInspector(
          items = items,
          selectedKey = selectedKey,
          dark = dark,
          previewMode = previewMode,
          spacing = spacing,
          padding = padding,
          modifier = Modifier.width(300.dp).fillMaxHeight(),
          onDark = { dark = it },
          onPreviewMode = { previewMode = it },
          onSpacing = { spacing = it },
          onPadding = { padding = it },
          onItems = { items = it },
          onSelect = { selectedKey = it },
          onDuplicate = { id -> add(id, items.indexOfFirst { it.key == selectedKey } + 1) },
          slotInfos = slotInfos,
          onClearSlot = { target ->
            items =
              composerItemByKey(items, target.hostKey)?.let { host ->
                putComposerSlots(items, host.key, host.slots - target.slotName)
              } ?: items
          },
        )
      }
    }

    drag?.let { active ->
      Surface(
        modifier =
          Modifier.offset {
              IntOffset(
                (active.position.x - composerBounds.left).roundToInt() - 90,
                (active.position.y - composerBounds.top).roundToInt() - 28,
              )
            }
            .width(180.dp)
            .zIndex(10f),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 10.dp,
      ) {
        Text(
          componentLabel(active.componentId),
          Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ComposerPalette(
  modifier: Modifier,
  onAdd: (String) -> Unit,
  onDragStart: (String, Int?, Offset) -> Unit,
  onDrag: (Offset) -> Unit,
  onDragEnd: () -> Unit,
) {
  Column(
    modifier.background(Color(0xFF15181D)).padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Components", style = MaterialTheme.typography.titleMedium)
    Text(
      "Drag onto the canvas or use Add.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.secondary,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Column(
      Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      catalogComponentIds.forEach { id -> PaletteItem(id, onAdd, onDragStart, onDrag, onDragEnd) }
    }
  }
}

@Composable
private fun PaletteItem(
  id: String,
  onAdd: (String) -> Unit,
  onDragStart: (String, Int?, Offset) -> Unit,
  onDrag: (Offset) -> Unit,
  onDragEnd: () -> Unit,
) {
  var bounds by remember { mutableStateOf(Rect.Zero) }
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier =
      Modifier.fillMaxWidth()
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .pointerInput(id) {
          detectDragGestures(
            onDragStart = { onDragStart(id, null, bounds.topLeft + it) },
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
          ) { change, amount ->
            change.consume()
            onDrag(amount)
          }
        },
  ) {
    Row(
      Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DragHandle()
      Text(
        componentLabel(id),
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      OutlinedButton(onClick = { onAdd(id) }) { Text("Add") }
    }
  }
}

@Composable
private fun ComposerCanvas(
  items: List<ComposerItem>,
  selectedKey: Int?,
  dark: Boolean,
  previewMode: Boolean,
  spacing: Float,
  padding: Float,
  modifier: Modifier,
  onSelect: (Int) -> Unit,
  onBounds: (Rect) -> Unit,
  onItemBounds: (Int, Rect) -> Unit,
  onSlotBounds: (ComposerSlotTarget, Rect) -> Unit,
  onSlotDiscovered: (ComposerSlotTarget, PreviewSlotInfo) -> Unit,
  hoveredSlot: ComposerSlotTarget?,
  onDragStart: (String, Int?, Offset) -> Unit,
  onDrag: (Offset) -> Unit,
  onDragEnd: () -> Unit,
) {
  Column(
    modifier.background(Color(0xFF101217)).padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Composition canvas", style = MaterialTheme.typography.titleMedium)
        Text(
          if (previewMode) "Interactive preview" else "Drop components to insert and reorder",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      }
      StatusPill(if (previewMode) "PREVIEW" else "EDIT", Color(0xFF65D6A3))
    }
    Surface(
      modifier =
        Modifier.width(390.dp).weight(1f).onGloballyPositioned { onBounds(it.boundsInRoot()) },
      color = if (dark) Color(0xFF1C1B1F) else Color(0xFFFFFBFE),
      shape = RoundedCornerShape(22.dp),
      shadowElevation = 10.dp,
    ) {
      CatalogCompositionTheme(dark) {
        if (items.isEmpty()) {
          Box(
            Modifier.fillMaxSize().border(2.dp, Color(0xFF6750A4), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("Drop components here", style = MaterialTheme.typography.titleMedium)
              Text(
                "Build a screen from the native CMP catalog",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
          }
        } else {
          Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
          ) {
            items.forEach { item ->
              if (previewMode) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                  ComposerCatalogItem(
                    item = item,
                    previewMode = true,
                    hoveredSlot = hoveredSlot,
                    onSlotBounds = onSlotBounds,
                    onSlotDiscovered = onSlotDiscovered,
                  )
                }
              } else {
                EditableCanvasItem(
                  item = item,
                  selected = item.key == selectedKey,
                  onSelect = { onSelect(item.key) },
                  onBounds = { onItemBounds(item.key, it) },
                  onDragStart = onDragStart,
                  onDrag = onDrag,
                  onDragEnd = onDragEnd,
                  hoveredSlot = hoveredSlot,
                  onSlotBounds = onSlotBounds,
                  onSlotDiscovered = onSlotDiscovered,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EditableCanvasItem(
  item: ComposerItem,
  selected: Boolean,
  onSelect: () -> Unit,
  onBounds: (Rect) -> Unit,
  onDragStart: (String, Int?, Offset) -> Unit,
  onDrag: (Offset) -> Unit,
  onDragEnd: () -> Unit,
  hoveredSlot: ComposerSlotTarget?,
  onSlotBounds: (ComposerSlotTarget, Rect) -> Unit,
  onSlotDiscovered: (ComposerSlotTarget, PreviewSlotInfo) -> Unit,
) {
  var bounds by remember { mutableStateOf(Rect.Zero) }
  Row(
    Modifier.fillMaxWidth()
      .onGloballyPositioned {
        bounds = it.boundsInRoot()
        onBounds(bounds)
      }
      .border(
        if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        RoundedCornerShape(12.dp),
      )
      .padding(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.width(32.dp).fillMaxHeight().clickable(onClick = onSelect).pointerInput(item.key) {
        detectDragGestures(
          onDragStart = {
            onSelect()
            onDragStart(item.componentId, item.key, bounds.topLeft + it)
          },
          onDragEnd = onDragEnd,
          onDragCancel = onDragEnd,
        ) { change, amount ->
          change.consume()
          onDrag(amount)
        }
      },
      contentAlignment = Alignment.Center,
    ) {
      DragHandle()
    }
    Box(
      Modifier.weight(1f).padding(10.dp).clickable(onClick = onSelect),
      contentAlignment = Alignment.CenterStart,
    ) {
      ComposerCatalogItem(
        item = item,
        previewMode = false,
        hoveredSlot = hoveredSlot,
        onSlotBounds = onSlotBounds,
        onSlotDiscovered = onSlotDiscovered,
      )
    }
  }
}

@Composable
private fun ComposerCatalogItem(
  item: ComposerItem,
  previewMode: Boolean,
  hoveredSlot: ComposerSlotTarget?,
  onSlotBounds: (ComposerSlotTarget, Rect) -> Unit,
  onSlotDiscovered: (ComposerSlotTarget, PreviewSlotInfo) -> Unit,
) {
  val slotHost =
    object : PreviewSlotHost {
      override fun onPositioned(slot: PreviewSlotInfo, bounds: Rect) {
        val target = ComposerSlotTarget(item.key, slot.name)
        onSlotDiscovered(target, slot)
        onSlotBounds(target, bounds)
      }

      @Composable
      override fun Content(slot: PreviewSlotInfo, defaultContent: @Composable () -> Unit) {
        val child = item.slots[slot.name]
        if (previewMode) {
          if (child == null) {
            defaultContent()
          } else {
            SlotChild(
              child = child,
              slot = slot,
              previewMode = true,
              hoveredSlot = hoveredSlot,
              onSlotBounds = onSlotBounds,
              onSlotDiscovered = onSlotDiscovered,
            )
          }
        } else {
          val target = ComposerSlotTarget(item.key, slot.name)
          val hovered = hoveredSlot == target
          SlotEditor(
            child = child,
            slot = slot,
            hovered = hovered,
            previewMode = false,
            hoveredSlot = hoveredSlot,
            onSlotBounds = onSlotBounds,
            onSlotDiscovered = onSlotDiscovered,
          )
        }
      }
    }
  CompositionLocalProvider(LocalPreviewSlotHost provides slotHost) {
    CatalogComponent(item.componentId)
  }
}

@Composable
private fun SlotEditor(
  child: ComposerItem?,
  slot: PreviewSlotInfo,
  hovered: Boolean,
  previewMode: Boolean,
  hoveredSlot: ComposerSlotTarget?,
  onSlotBounds: (ComposerSlotTarget, Rect) -> Unit,
  onSlotDiscovered: (ComposerSlotTarget, PreviewSlotInfo) -> Unit,
) {
  val emptyHeight = if (slot.constraints.vertical == PreviewSlotSizing.Fixed) 16.dp else 32.dp
  Box(
    Modifier.fillMaxWidth()
      .then(if (child == null) Modifier.height(emptyHeight) else Modifier)
      .clipToBounds()
      .background(
        if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = .28f)
        else MaterialTheme.colorScheme.primary.copy(alpha = .10f)
      )
      .border(
        1.dp,
        if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
      )
      .padding(
        start = slot.constraints.padding.startDp.dp,
        top = slot.constraints.padding.topDp.dp,
        end = slot.constraints.padding.endDp.dp,
        bottom = slot.constraints.padding.bottomDp.dp,
      ),
    contentAlignment = Alignment.CenterStart,
  ) {
    if (child == null) {
      Text(
        slot.name,
        Modifier.padding(horizontal = 3.dp),
        fontSize = 8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.primary,
      )
    } else {
      SlotChild(
        child = child,
        slot = slot,
        previewMode = previewMode,
        hoveredSlot = hoveredSlot,
        onSlotBounds = onSlotBounds,
        onSlotDiscovered = onSlotDiscovered,
      )
    }
  }
}

@Composable
private fun SlotChild(
  child: ComposerItem,
  slot: PreviewSlotInfo,
  previewMode: Boolean,
  hoveredSlot: ComposerSlotTarget?,
  onSlotBounds: (ComposerSlotTarget, Rect) -> Unit,
  onSlotDiscovered: (ComposerSlotTarget, PreviewSlotInfo) -> Unit,
) {
  Box(
    Modifier.then(
        if (slot.constraints.horizontal == PreviewSlotSizing.Fill) Modifier.fillMaxWidth()
        else Modifier
      )
      .clipToBounds(),
    contentAlignment = Alignment.CenterStart,
  ) {
    ComposerCatalogItem(
      item = child,
      previewMode = previewMode,
      hoveredSlot = hoveredSlot,
      onSlotBounds = onSlotBounds,
      onSlotDiscovered = onSlotDiscovered,
    )
  }
}

@Composable
private fun CatalogCompositionTheme(dark: Boolean, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalWasmCatalogKnobs provides emptyMap()) {
    MaterialTheme(
      colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
      typography = catalogTypography(FontFamily.SansSerif),
      content = content,
    )
  }
}

@Composable
private fun ComposerInspector(
  items: List<ComposerItem>,
  selectedKey: Int?,
  dark: Boolean,
  previewMode: Boolean,
  spacing: Float,
  padding: Float,
  modifier: Modifier,
  onDark: (Boolean) -> Unit,
  onPreviewMode: (Boolean) -> Unit,
  onSpacing: (Float) -> Unit,
  onPadding: (Float) -> Unit,
  onItems: (List<ComposerItem>) -> Unit,
  onSelect: (Int?) -> Unit,
  onDuplicate: (String) -> Unit,
  slotInfos: Map<ComposerSlotTarget, PreviewSlotInfo>,
  onClearSlot: (ComposerSlotTarget) -> Unit,
) {
  val selectedIndex = items.indexOfFirst { it.key == selectedKey }
  val selected = items.getOrNull(selectedIndex)
  val selectedSlots =
    selected
      ?.let { host ->
        slotInfos
          .filterKeys { target -> target.hostKey == host.key }
          .toList()
          .sortedBy { it.first.slotName }
      }
      .orEmpty()
  Column(
    modifier.background(Color(0xFF15181D)).padding(18.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Properties", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Preview mode", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Switch(checked = previewMode, onCheckedChange = onPreviewMode)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = !dark, onClick = { onDark(false) }, label = { Text("Light") })
      FilterChip(selected = dark, onClick = { onDark(true) }, label = { Text("Dark") })
    }
    LabeledSlider("Screen padding", padding, 8f..48f, onPadding)
    LabeledSlider("Item spacing", spacing, 0f..40f, onSpacing)
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    if (selected == null) {
      Text("Select a component to edit it.", color = MaterialTheme.colorScheme.secondary)
    } else {
      Text("Selected", style = MaterialTheme.typography.labelLarge)
      Text(componentLabel(selected.componentId), style = MaterialTheme.typography.titleSmall)
      Text(
        selected.componentId,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
      )
      if (selectedSlots.isNotEmpty()) {
        Text("Slots", style = MaterialTheme.typography.labelLarge)
        selectedSlots.forEach { (target, info) ->
          val child = selected.slots[target.slotName]
          Column(
            Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
              .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.bodySmall)
                Text(
                  "${info.constraints.horizontal.name.lowercase()} × " +
                    info.constraints.vertical.name.lowercase(),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                )
              }
              if (child != null) {
                OutlinedButton(onClick = { onClearSlot(target) }) { Text("Clear") }
              }
            }
            Text(
              child?.let { componentLabel(it.componentId) } ?: "Drop a component here",
              style = MaterialTheme.typography.labelSmall,
              color =
                if (child == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondary,
            )
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { onItems(moveComposerItem(items, selectedIndex, selectedIndex - 1)) },
          enabled = selectedIndex > 0,
        ) {
          Text("Up")
        }
        OutlinedButton(
          onClick = { onItems(moveComposerItem(items, selectedIndex, selectedIndex + 2)) },
          enabled = selectedIndex in 0 until items.lastIndex,
        ) {
          Text("Down")
        }
      }
      OutlinedButton(
        onClick = { onDuplicate(selected.componentId) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Duplicate")
      }
      Button(
        onClick = {
          val remaining = items.filterNot { it.key == selected.key }
          onItems(remaining)
          onSelect(remaining.getOrNull(selectedIndex.coerceAtMost(remaining.lastIndex))?.key)
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Remove")
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text("${composerItemCount(items)} components", style = MaterialTheme.typography.labelLarge)
    Text(
      "Rendered natively by Compose Multiplatform in this browser.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.secondary,
    )
    if (items.isNotEmpty()) {
      OutlinedButton(
        onClick = {
          onItems(emptyList())
          onSelect(null)
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Clear canvas")
      }
    }
  }
}

@Composable
private fun LabeledSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValue: (Float) -> Unit,
) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodySmall)
      Text("${value.roundToInt()} dp", color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onValue, valueRange = range)
  }
}

private fun componentLabel(id: String): String =
  id.split('-').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

@Composable
private fun DragHandle() {
  val color = MaterialTheme.colorScheme.primary
  Box(
    Modifier.size(18.dp).drawBehind {
      val radius = 1.5.dp.toPx()
      val left = size.width * .35f
      val right = size.width * .65f
      for (row in 1..3) {
        val y = size.height * row / 4f
        drawCircle(color, radius, Offset(left, y))
        drawCircle(color, radius, Offset(right, y))
      }
    }
  )
}
