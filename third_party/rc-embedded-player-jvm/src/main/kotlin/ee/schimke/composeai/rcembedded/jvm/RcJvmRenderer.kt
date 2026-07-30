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

@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.SystemClock
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.core.operations.ColorConstant
import androidx.compose.remote.core.operations.ColorTheme
import androidx.compose.remote.core.operations.FloatConstant
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.core.operations.layout.LayoutComponent
import androidx.compose.remote.player.compose.embedded.GraphContext
import androidx.compose.remote.player.compose.embedded.JvmRemoteContext
import androidx.compose.remote.player.compose.embedded.LocalCoreDocument
import androidx.compose.remote.player.compose.embedded.LocalCurrentTimeMillis
import androidx.compose.remote.player.compose.embedded.LocalGraphContext
import androidx.compose.remote.player.compose.embedded.LocalRemoteContext
import androidx.compose.remote.player.compose.embedded.RcPlayerRawDocument
import androidx.compose.remote.player.compose.embedded.RcPlayerRootLayoutComponent
import androidx.compose.remote.player.compose.embedded.SnapshotRemoteComposeState
import androidx.compose.remote.player.compose.embedded.applyOperationsReflection
import androidx.compose.remote.player.compose.embedded.buildComputedOpIndex
import androidx.compose.remote.player.compose.embedded.getOperationsReflection
import androidx.compose.remote.player.compose.embedded.recollectCollectionsReflection
import androidx.compose.remote.player.compose.embedded.registerVariablesReflection
import androidx.compose.remote.player.compose.embedded.updateTimeReflection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayInputStream
import org.jetbrains.skia.EncodedImageFormat

/*
 * The desktop/JVM render entry point for a captured Remote Compose document — the jvm counterpart of
 * the Android `RcPlayer` composable (`RcPlayer.kt`), reaching the *same* shared draw/layout dispatch
 * (`RcPlayerRootLayoutComponent` / `RcPlayerRawDocument` in `RcPlayerDispatch.kt`) that the Android
 * player does. It exists so the embedded player can produce a PNG on a plain JVM over skiko, with no
 * Android and no Robolectric — the missing half that makes the "cmp-jvm" render lane real.
 *
 * ## Relationship to `RcPlayer`
 *
 * `RcPlayer` is `@Composable fun RcPlayer(document, ...)`; it stands up an `AndroidRemoteContext`
 * (font resolver, choreographer), initializes the document onto it, builds a `GraphContext`, runs an
 * infinite-animation frame loop, and provides ~10 composition locals before dispatching. This file
 * reproduces the parts that are **platform-neutral and needed to draw a captured (static) document**:
 * document parse, context init, the constant/data apply passes, the `GraphContext`, and the locals
 * the shared draw path actually reads. It deliberately drops the Android-only and interaction-only
 * surface — the framework typeface resolver (the jvm text seam resolves fonts itself), the
 * choreographer, the pluggable `Drawable` image loader (`LocalRcImageLoader` stays androidMain; the
 * jvm image path decodes to an `ImageBitmap` via the image seam), and the action handlers — none of
 * which a still capture exercises. The neutral setup below is a line-for-line mirror of `RcPlayer`'s
 * `remember(document)` init block so the two players initialize a document identically.
 *
 * ## Parity note — density
 *
 * The document is laid out in px into a canvas of exactly [widthPx] × [heightPx], but font size and
 * dp-denominated modifiers scale with [density]. The Android embedded rc-compare lane rasterizes at
 * xhdpi (density 2.0); pass the same density here for a like-for-like comparison against that lane
 * and, through it, against the View player the goal targets.
 */

/**
 * Render a captured Remote Compose document (`.rc` bytes) to PNG bytes on the JVM, at exactly
 * [widthPx] × [heightPx] pixels. [density] scales text and dp-based modifiers (see the parity
 * note).
 *
 * Requires skiko's native library at runtime (the caller supplies it — e.g. `compose.desktop
 * .currentOs`); it is not needed to compile.
 */
public fun renderRemoteDocumentToPng(
  bytes: ByteArray,
  widthPx: Int,
  heightPx: Int,
  density: Float = 2f,
): ByteArray {
  val scene =
    ImageComposeScene(width = widthPx, height = heightPx, density = Density(density)) {
      val document = remember(bytes) { parseDocument(bytes) }
      RcPlayerJvm(document, Modifier.fillMaxSize())
    }
  try {
    val image = scene.render()
    val data =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: error("skiko could not encode the rendered image to PNG")
    return data.bytes
  } finally {
    scene.close()
  }
}

/** Parse `.rc` bytes into a [CoreDocument]. Mirrors `RcPlayer(capturedDocument)`'s buffer load. */
internal fun parseDocument(bytes: ByteArray): CoreDocument =
  CoreDocument(RemoteClock.SYSTEM).apply {
    ByteArrayInputStream(bytes).use { stream ->
      initFromBuffer(RemoteComposeBuffer.fromInputStream(stream))
    }
  }

/**
 * The jvm player composable: initialize [document] onto a [JvmRemoteContext], build the
 * [GraphContext], provide the composition locals the shared dispatch reads, and draw. A faithful,
 * static-document subset of Android `RcPlayer` — see the file header.
 */
@Composable
internal fun RcPlayerJvm(document: CoreDocument, modifier: Modifier = Modifier) {
  val clock: RemoteClock =
    remember(document) { document.clock.takeUnless { it is SystemClock } ?: RemoteClock.SYSTEM }

  // Mirror Android `RcPlayer`, which reads `LocalDensity.current` and seeds it onto the context.
  // `ImageComposeScene` sets `LocalDensity` to the requested render density, so this forwards that
  // density (and the platform font scale) into context init below.
  val density = LocalDensity.current
  val remoteContext =
    remember(document) { initDrawContext(document, clock, density.density, density.fontScale) }

  // Static capture: no frame loop. The time state is still provided so time-reading resolvers have
  // a value to read (0), matching a document settled at t=0.
  val currentTimeMillisState = remember { mutableFloatStateOf(0f) }

  val graphContext =
    remember(document) {
      (remoteContext.mRemoteComposeState as? SnapshotRemoteComposeState)?.let { snapshotState ->
        GraphContext(
          snapshotState,
          buildComputedOpIndex(document.getOperationsReflection()),
          currentTimeMillisState,
          clock,
        )
      }
    }

  BoxWithConstraints(modifier) {
    CompositionLocalProvider(
      LocalCoreDocument provides document,
      LocalRemoteContext provides remoteContext,
      LocalCurrentTimeMillis provides currentTimeMillisState,
      LocalGraphContext provides graphContext,
    ) {
      val rootSize = IntSize(constraints.maxWidth, constraints.maxHeight)
      if (document.rootLayoutComponent != null) {
        RcPlayerRootLayoutComponent(rootSize)
      } else {
        RcPlayerRawDocument(rootSize)
      }
    }
  }
}

/**
 * Stand up the draw [RemoteContext] for [document]. A line-for-line mirror of the neutral half of
 * `RcPlayer`'s `remember(document)` init block, over a [JvmRemoteContext] instead of an
 * `AndroidRemoteContext` and without the framework typeface resolver / choreographer.
 */
private fun initDrawContext(
  document: CoreDocument,
  clock: RemoteClock,
  density: Float,
  fontScale: Float,
): JvmRemoteContext =
  JvmRemoteContext(clock = clock).also { context ->
    // Back the document's reactive scalar state with Compose snapshot state, so variables
    // resolve reactively; swap before initializeContext propagates document state onto the
    // context, and re-gather the collections the loader put in the previous state.
    if (document.remoteComposeState !is SnapshotRemoteComposeState) {
      document.setRemoteComposeState(SnapshotRemoteComposeState())
      document.recollectCollectionsReflection()
    }
    // Seed the density built-ins before initializeContext, exactly as the Android player does: a
    // document reading `ID_DENSITY` / `ID_FONT_SIZE` (e.g. a dp→px expression or default text
    // sizing)
    // must resolve at the render density, not the store default, or a density-driven layout would
    // diff on geometry against the requested (xhdpi) size.
    context.loadFloat(RemoteContext.ID_FONT_SIZE, 14f * fontScale * density)
    context.loadFloat(RemoteContext.ID_DENSITY, density)
    context.density = density
    document.initializeContext(context)

    // Register each bitmap's metadata (id + declared size) WITHOUT decoding pixels; the decode
    // is deferred to first draw (resolveImage drives BitmapData.apply -> loadBitmap).
    val bitmaps = ArrayList<BitmapData>()
    findBitmaps(document.getOperationsReflection(), bitmaps)
    bitmaps.forEach { bitmap -> context.putObject(bitmap.mImageId, bitmap) }

    document.setLayoutCallback {}
    document.updateTimeReflection(context)
    document.registerVariablesReflection(context, document.getOperationsReflection())

    // Global setup ops (everything up to the root layout component): color/float constants,
    // named variables, top-level data collections. The layout tree's internal ops are applied
    // in data order below and re-evaluated reactively at draw.
    val rootComponent = document.rootLayoutComponent
    val globalOps =
      if (rootComponent != null) {
        ArrayList(document.getOperationsReflection().takeWhile { it !== rootComponent })
      } else {
        document.getOperationsReflection()
      }
    document.applyOperationsReflection(context, globalOps)

    // Then every constant anywhere in the tree, so authored color/float defaults are in the
    // store before the data pass.
    val constantOps = ArrayList<Operation>()
    collectConstants(document.getOperationsReflection(), constantOps)
    document.applyOperationsReflection(context, constantOps)

    val dataOps = ArrayList<Operation>()
    document.rootLayoutComponent?.getData(dataOps, true)
    document.applyOperationsReflection(context, dataOps)
  }

/** Collect every [BitmapData] in the op tree. Mirrors `RcPlayer.kt`'s private `findBitmaps`. */
private fun findBitmaps(operations: Collection<Operation>, list: MutableList<BitmapData>) {
  operations.forEach { op ->
    if (op is BitmapData) list.add(op)
    if (op is Container) findBitmaps(op.getList(), list)
  }
}

/** Collect every constant-like op in the tree. Mirrors `RcPlayer.kt`'s inline constant walk. */
private fun collectConstants(operations: Collection<Operation>, out: MutableList<Operation>) {
  for (op in operations) {
    val match =
      op is ColorConstant ||
        op is FloatConstant ||
        op is ColorTheme ||
        op is NamedVariable ||
        op.javaClass.simpleName.endsWith("Constant")
    if (match) out.add(op)
    if (op is Container) collectConstants(op.getList(), out)
    if (op is LayoutComponent) {
      val canvasOps = op.getCanvasOperations()
      if (canvasOps != null) collectConstants(listOf(canvasOps), out)
    }
  }
}
