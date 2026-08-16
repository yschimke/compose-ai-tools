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
@file:OptIn(androidx.tracing.DelicateTracingApi::class)

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Limits
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
import androidx.compose.remote.core.operations.Theme
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
import androidx.compose.remote.player.compose.embedded.resolveThemeMode
import androidx.compose.remote.player.compose.embedded.resolveThemedColors
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
import androidx.tracing.Tracer
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
  seeds: Map<String, RcSeed> = emptyMap(),
  theme: Int = Theme.LIGHT,
  systemColorLookup: (name: String) -> Int? = { null },
): ByteArray =
  Tracer.global.trace(category = RC_EMBEDDED_TRACE_DOCUMENT, name = "rcEmbedded:renderToPng") {
    val scene =
      ImageComposeScene(width = widthPx, height = heightPx, density = Density(density)) {
        val document = remember(bytes) { parseDocument(bytes) }
        RcPlayerJvm(document, Modifier.fillMaxSize(), seeds, theme, systemColorLookup)
      }
    try {
      // The composition, measure, layout and draw of the whole document all happen inside this
      // one call — `ImageComposeScene.render()` is the frame.
      val image =
        Tracer.global.trace(category = RC_EMBEDDED_TRACE_FRAME, name = "rcEmbedded:renderFrame") {
          scene.render()
        }
      Tracer.global.trace(category = RC_EMBEDDED_TRACE_DOCUMENT, name = "rcEmbedded:encodePng") {
        val data =
          image.encodeToData(EncodedImageFormat.PNG)
            ?: error("skiko could not encode the rendered image to PNG")
        data.bytes
      }
    } finally {
      scene.close()
    }
  }

/*
 * Trace categories for the embedded (AndroidX) player lane.
 *
 * These are `androidx.tracing` 2.x categories, written through `Tracer.global` — the same tracer
 * `:rc-player-trace` feeds from the CMP player, reached here directly rather than through that
 * facade because this module renders AndroidX's own player and should not acquire a dependency on
 * ours. The *values* are chosen to sit alongside `RcTraceCategory.DOCUMENT` / `.FRAME` so one
 * capture shows both lanes with the same filtering, which is what makes the two comparable at all.
 *
 * As with the CMP player, nothing here installs a tracer: `Tracer.global` is a stub that drops
 * every span until the embedding process registers a driver.
 */
internal const val RC_EMBEDDED_TRACE_DOCUMENT: String = "rc-embedded.document"

internal const val RC_EMBEDDED_TRACE_FRAME: String = "rc-embedded.frame"

/**
 * Let the AndroidX parser accept URL- and file-encoded bitmaps.
 *
 * `Limits.ENABLE_IMAGE_URLS` / `ENABLE_IMAGE_FILES` are mutable globals that ship `false`. With
 * them off, `BitmapData.read` throws `URL image not supported [<id>]` the moment a document carries
 * a `BitmapData` with `ENCODING_URL` — and because that throw happens during `inflateFromBuffer`,
 * it takes down the *whole* parse. The document then produces no render at all, so this lane drops
 * its column for it rather than drawing the 95% of the document that has nothing to do with the
 * image.
 *
 * Enabling the parse is not enabling a fetch. The reference is only resolved if the host supplies a
 * loader, and this renderer supplies none — the image slot stays empty and the rest of the document
 * draws. That is exactly what the JS and CMP players already do with the same bytes, which is what
 * makes the comparison a comparison: before this, a URL-image document was the one case where the
 * embedded lanes were blank for a reason that had nothing to do with the renderer under test.
 *
 * Both flags, not just the URL one, because a document authored against both assumes both: the Home
 * Assistant catalog that surfaced this calls its own `enableRemoteImageUrls()` — setting the
 * identical pair — from every player entry point it owns. This lane is an entry point it doesn't.
 *
 * Set on every parse rather than once in an initializer: the flags are process-global and public,
 * so anything else on the classpath can flip them back, and re-asserting costs two field writes.
 */
private fun enableEncodedImageReferences() {
  Limits.ENABLE_IMAGE_URLS = true
  Limits.ENABLE_IMAGE_FILES = true
}

/** Parse `.rc` bytes into a [CoreDocument]. Mirrors `RcPlayer(capturedDocument)`'s buffer load. */
internal fun parseDocument(bytes: ByteArray): CoreDocument =
  Tracer.global.trace(category = RC_EMBEDDED_TRACE_DOCUMENT, name = "rcEmbedded:parseDocument") {
    enableEncodedImageReferences()
    CoreDocument(RemoteClock.SYSTEM).apply {
      ByteArrayInputStream(bytes).use { stream ->
        initFromBuffer(RemoteComposeBuffer.fromInputStream(stream))
      }
    }
  }

/**
 * The jvm player composable: initialize [document] onto a [JvmRemoteContext], build the
 * [GraphContext], provide the composition locals the shared dispatch reads, and draw. A faithful,
 * static-document subset of Android `RcPlayer` — see the file header.
 */
@Composable
internal fun RcPlayerJvm(
  document: CoreDocument,
  modifier: Modifier = Modifier,
  seeds: Map<String, RcSeed> = emptyMap(),
  theme: Int = Theme.LIGHT,
  systemColorLookup: (name: String) -> Int? = { null },
) {
  val clock: RemoteClock =
    remember(document) { document.clock.takeUnless { it is SystemClock } ?: RemoteClock.SYSTEM }

  // Mirror Android `RcPlayer`, which reads `LocalDensity.current` and seeds it onto the context.
  // `ImageComposeScene` sets `LocalDensity` to the requested render density, so this forwards that
  // density (and the platform font scale) into context init below.
  val density = LocalDensity.current
  val remoteContext =
    remember(document) {
      initDrawContext(
        document,
        clock,
        density.density,
        density.fontScale,
        seeds,
        theme,
        systemColorLookup,
      )
    }

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
  seeds: Map<String, RcSeed>,
  theme: Int,
  systemColorLookup: (name: String) -> Int?,
): JvmRemoteContext =
  Tracer.global.trace(category = RC_EMBEDDED_TRACE_DOCUMENT, name = "rcEmbedded:initContext") {
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

      // Themed colours, before the `ColorTheme` ops in `constantOps` are applied — `ColorTheme`
      // reads the fields resolution overwrites, so resolving afterwards is resolving too late.
      //
      // The mode is resolved rather than passed through: `SYSTEM`/`UNSPECIFIED` are questions, and
      // `ColorTheme` treats anything that is not `LIGHT` as dark, so leaving one unanswered renders
      // the document dark by accident. Headless, "the host's setting" is not a real question — this
      // renderer has no desktop session whose theme it should follow, and a render that changed
      // colour with the build machine's OS theme would not be reproducible — so `renderRemote
      // DocumentToPng` defaults `theme` to `LIGHT` and a caller wanting dark asks for it.
      //
      // `systemColorLookup` defaults to resolving nothing, which is the honest answer here: there
      // is no system palette off Android, so every themed colour keeps the fallback its document
      // captured for exactly that case.
      resolveThemedColors(document, systemColorLookup)
      context.paintTheme = resolveThemeMode(theme, systemInDarkTheme = false)

      // Then every constant anywhere in the tree, so authored color/float defaults are in the
      // store before the data pass.
      val constantOps = ArrayList<Operation>()
      collectConstants(document.getOperationsReflection(), constantOps)
      document.applyOperationsReflection(context, constantOps)

      // Host knob edits (the serve `rc.<name>=…` seeds), applied on top of the authored defaults
      // just
      // loaded — exactly where Android `RcPlayer` applies its `namedColorOverrides` (RcPlayer.kt),
      // so
      // a
      // seeded value wins over the constant and the draw resolves it.
      applySeeds(context, seeds)

      val dataOps = ArrayList<Operation>()
      document.rootLayoutComponent?.getData(dataOps, true)
      document.applyOperationsReflection(context, dataOps)
    }
  }

/**
 * A named-value knob seed applied on top of a document's authored defaults — the jvm counterpart of
 * the serve `rc.<name>=…` overrides. Kept neutral (no daemon-protocol types): [RcJvmRenderMain]
 * decodes its CLI seed file into these, and the serve side encodes the daemon's
 * `RemoteComposeOverride.namedValues` into that file. `dp` collapses to [FloatValue] and `bool` to
 * [IntValue] on the writer side (matching the daemon's apply table), so only four leaf types
 * arrive.
 */
public sealed interface RcSeed {
  public data class StringValue(val value: String) : RcSeed

  public data class FloatValue(val value: Float) : RcSeed

  public data class IntValue(val value: Int) : RcSeed

  public data class ColorValue(val argb: Int) : RcSeed
}

/**
 * Apply [seeds] as named-value overrides on [context], the jvm counterpart of the daemon's
 * `applyConnectorOverrides`: it targets the embedded player's `RemoteContext` named-override family
 * (the same setters the Android `RcPlayer` uses for its colour overrides) instead of a
 * `StateUpdater`. Names are USER-qualified when unprefixed, matching how author knobs
 * (`rememberNamedRemote*`) register and how `RcPlayer` qualifies its overrides.
 */
private fun applySeeds(context: JvmRemoteContext, seeds: Map<String, RcSeed>) {
  seeds.forEach { (name, seed) ->
    val qualified = if (name.contains(':')) name else "USER:$name"
    when (seed) {
      is RcSeed.StringValue -> context.setNamedStringOverride(qualified, seed.value)
      is RcSeed.FloatValue -> context.setNamedFloatOverride(qualified, seed.value)
      is RcSeed.IntValue -> context.setNamedIntegerOverride(qualified, seed.value)
      is RcSeed.ColorValue -> context.setNamedColorOverride(qualified, seed.argb)
    }
  }
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
