/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.remote.player.compose.embedded

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.core.operations.ComponentValue
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.core.operations.layout.LayoutComponent
import androidx.compose.remote.core.operations.layout.RootLayoutComponent
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.core.operations.layout.managers.CanvasLayout
import androidx.compose.remote.core.operations.layout.managers.ColumnLayout
import androidx.compose.remote.core.operations.layout.managers.CoreText
import androidx.compose.remote.core.operations.layout.managers.Custom
import androidx.compose.remote.core.operations.layout.managers.FitBoxLayout
import androidx.compose.remote.core.operations.layout.managers.FlowLayout
import androidx.compose.remote.core.operations.layout.managers.ImageLayout
import androidx.compose.remote.core.operations.layout.managers.RowLayout
import androidx.compose.remote.core.operations.layout.managers.StateLayout
import androidx.compose.remote.core.operations.layout.managers.TextLayout
import androidx.compose.remote.core.operations.layout.modifiers.ComponentVisibilityOperation
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerBox
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerColumn
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerFitBoxLayout
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerFlowRow
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerImageLayout
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerRow
import androidx.compose.remote.player.compose.embedded.layout.RcPlayerStateLayout
import androidx.compose.remote.player.compose.embedded.state.rememberRemoteIntAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

/*
 * The component-tree dispatch half of the embedded player, split out of `RcPlayer.kt`.
 *
 * `RcPlayer.kt` is the player's Android entry point — it names `android.annotation.SuppressLint`,
 * `android.app.PendingIntent` and `androidx.compose.remote.player.core.platform.AndroidRemoteContext`
 * for the document-setup / interactive-dispatch surface (none of which the pixel path touches). The
 * four composables here — the raw-document and root-layout entry points plus the recursive
 * `RcPlayerComponent` / `RcPlayerChildren` walk — reference only neutral types (the composition
 * locals from `RcPlayerCompositionLocals.kt`, `executeOperations` from the image/text-seamed
 * `RcPlayerDrawing.kt`, and the per-layout composables), so moving them here keeps `RcPlayer.kt`'s
 * Android coupling off the draw/layout dispatch path. This is a **move, not a change**: the bodies
 * are verbatim from `RcPlayer.kt`.
 *
 * It stays in `androidMain` for now — `RcPlayerComponent`'s `when` reaches `RcPlayerText` (google
 * fonts) and `RcPlayerImageLayout` (the `Drawable`-typed loader), both still Android-only — but it no
 * longer drags `RcPlayer.kt` behind it. See `PROVENANCE.md` ("Next: the draw dispatcher").
 */

/**
 * Renders a document that has no root layout component — a raw draw-list document whose draw
 * operations live at the top level of `mOperations` rather than inside a component tree (e.g.
 * shader / canvas demos built without the layout DSL). The View player renders these via
 * `CoreDocument.paint`; here the document's operations are executed directly in a Canvas. Without
 * this fallback the layout-tree renderer dereferenced a null `rootLayoutComponent` and crashed
 * (NPE) for such documents.
 */
@Composable
internal fun RcPlayerRawDocument(size: IntSize) {
  val document = LocalCoreDocument.current
  val remoteContext = LocalRemoteContext.current
  val graph = LocalGraphContext.current
  val textMeasurer = rememberTextMeasurer()
  Canvas(modifier = Modifier.fillMaxSize()) {
    // Publish the on-screen size as the document dimensions before painting, mirroring
    // CoreDocument.paint, so draws positioned by the document size resolve.
    document.setWidth(size.width)
    document.setHeight(size.height)
    executeOperations(
      document.getOperationsReflection(),
      remoteContext,
      graph = graph,
      textMeasurer = textMeasurer,
    )
  }
}

@Composable
internal fun RcPlayerRootLayoutComponent(size: IntSize) {
  val document = LocalCoreDocument.current
  val root: RootLayoutComponent = document.rootLayoutComponent!!
  val remoteContext = LocalRemoteContext.current

  root.setWidth(size.width.toFloat())
  root.setHeight(size.height.toFloat())
  root.updateVariables(remoteContext)

  RcPlayerChildren(root)
}

@Composable
internal fun RcPlayerComponent(component: Component, modifier: Modifier = Modifier) {
  if (component is LayoutComponent) {
    val remoteContext = LocalRemoteContext.current
    val componentValueMap = LocalComponentValueMap.current
    val componentValueStateMap = LocalComponentValueStateMap.current

    val visibilityOp =
      component.componentModifiers.list.find { it is ComponentVisibilityOperation }
        as? ComponentVisibilityOperation
    if (visibilityOp != null) {
      val visible by rememberRemoteIntAsState(visibilityOp.getVisibilityIdReflection())
      if (visible == Component.Visibility.GONE) {
        return
      }
    }

    var modifier =
      component.componentModifiers
        .toModifier(component.getDrawContentOperationsListReflection())
        .then(modifier)

    // Publish the component's measured WIDTH/HEIGHT (read by ComponentValue expressions) from
    // an onSizeChanged callback rather than a custom Modifier.layout that wrote snapshot state
    // during the measure pass (a relayout hazard) and sat ahead of the real modifiers (which
    // disturbed constraint propagation, e.g. FILL children collapsing to wrap size). As the
    // outermost modifier, onSizeChanged reports the full component size and fires after layout.
    val sizeFeedbackOps = componentValueMap[component.getId()]
    if (!sizeFeedbackOps.isNullOrEmpty()) {
      modifier =
        Modifier.onSizeChanged { sz ->
            sizeFeedbackOps.forEach { op ->
              val state = componentValueStateMap[op.valueId] ?: return@forEach
              val w = sz.width.toFloat()
              val h = sz.height.toFloat()
              if (op.type == ComponentValue.WIDTH && abs(w - state.value) > 2.0f) {
                state.value = w
              } else if (op.type == ComponentValue.HEIGHT && abs(h - state.value) > 2.0f) {
                state.value = h
              }
            }
          }
          .then(modifier)
    }

    // The component's draw ops (background/border chrome + the DrawContent marker) are already
    // rendered by `toModifier` above — its `DrawContentOperation` branch (or its fallback when a
    // component carries no explicit marker) wraps them in the `drawWithContent`. Executing the
    // same `drawOpsList` a second time here re-drew the chrome at a different point in the
    // modifier chain: for a component with content padding (e.g. an outlined card) the second
    // pass landed *inside* the padding and stroked a spurious inner outline hugging the content,
    // on top of the correct card-bounds outline. Components without padding drew the two passes
    // at the same size, so the duplication was invisible until a padded, bordered one hit it.
    when (component) {
      is CanvasLayout -> RcPlayerCanvas(component, modifier)
      is ColumnLayout -> RcPlayerColumn(component, modifier)
      is FlowLayout -> RcPlayerFlowRow(component, modifier)
      is RowLayout -> RcPlayerRow(component, modifier)
      is CoreText -> RcPlayerText(component, modifier)
      is TextLayout -> RcPlayerText(component, modifier)
      is FitBoxLayout -> RcPlayerFitBoxLayout(component, modifier)
      is StateLayout -> RcPlayerStateLayout(component, modifier)
      is ImageLayout -> RcPlayerImageLayout(component, modifier)
      is Custom -> RcPlayerCustom(component, modifier)
      // Last as others are often BoxLayout subclasses
      is BoxLayout -> RcPlayerBox(component, modifier)
      else -> {
        // Unsupported layout type. Render nothing rather than crash; see
        // operation_coverage.md. The modifier (incl. any drawContent) was still applied
        // above.
        println(
          "Warning: unsupported layout component ${component::class.java.simpleName}; rendering nothing"
        )
      }
    }
  } else {
    // Non-LayoutComponent component reached dispatch — skip gracefully instead of crashing.
    println(
      "Warning: unsupported component ${component?.let { it::class.java.simpleName }}; rendering nothing"
    )
  }
}

@Composable
internal fun RcPlayerChildren(
  layout: Component,
  modifierProvider: @Composable (Component) -> Modifier = { Modifier },
) {
  if (layout is LayoutComponent) {
    layout.childrenComponents.fastForEach { child ->
      val scopeModifier = modifierProvider(child)
      RcPlayerComponent(child, scopeModifier)
    }
  } else {
    val children = remember { ArrayList<Component>().apply { layout.getComponents(this) } }
    children.fastForEach { op -> RcPlayerComponent(op) }
  }
}
