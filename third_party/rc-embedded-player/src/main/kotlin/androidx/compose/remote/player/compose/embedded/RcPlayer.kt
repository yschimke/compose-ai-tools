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

@file:Suppress(
  "RestrictedApiAndroidX",
  "PrimitiveInCollection",
  "VisibleForTests",
  "RememberReturnType",
  "ModifierParameter",
  "AutoboxingStateCreation",
)

package androidx.compose.remote.player.compose.embedded

import android.annotation.SuppressLint
import android.app.PendingIntent
import androidx.collection.IntObjectMap
import androidx.collection.ObjectIntMap
import androidx.collection.emptyIntObjectMap
import androidx.collection.emptyObjectIntMap
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.remote.core.operations.ComponentValue
import androidx.compose.remote.core.operations.FloatConstant
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.core.operations.ParticlesCompare
import androidx.compose.remote.core.operations.ParticlesLoop
import androidx.compose.remote.core.operations.Theme
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.WakeIn
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.core.operations.layout.LayoutComponent
import androidx.compose.remote.core.operations.layout.LayoutComponentContent
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.core.operations.utilities.NanMap
import androidx.compose.remote.creation.compose.action.LambdaAction
import androidx.compose.remote.creation.compose.action.PendingIntentAction
import androidx.compose.remote.creation.compose.capture.CapturedDocument
import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayInputStream

/**
 * A player of a [CoreDocument].
 *
 * **One player per document.** First composition installs this player's runtime state *onto the
 * document* (swaps in a [SnapshotRemoteComposeState], re-gathers collections, applies operations),
 * so a given [CoreDocument] instance is bound to a single `RcPlayer`. Don't drive two players from
 * the same `CoreDocument` concurrently — give each its own document (re-`initFromBuffer`).
 * Re-installing onto an already-initialized document is guarded against below so an accidental
 * reuse doesn't clobber existing state, but the two players would then share one state, which is
 * not supported.
 *
 * Theme colors: the document already carries each named color's authored default (a `ColorConstant`
 * emitted alongside the `NamedVariable`), which is applied at setup. To re-theme from the host —
 * the embedded equivalent of the View player's `setColor(name, value)` — pass [namedColorOverrides]
 * (variable name -> ARGB int); each entry is applied via `setNamedColorOverride` after the
 * document's defaults.
 *
 * [theme] selects the `ColorTheme` light/dark branch. Indexed colors in the Android group are
 * resolved from framework resources before the first operation replay; unknown groups or missing
 * resources retain their authored fallback.
 *
 * [Theme.SYSTEM] — the default — and [Theme.UNSPECIFIED] are not modes, they are questions, and
 * both are answered here from `isSystemInDarkTheme()`. They cannot be passed through: `ColorTheme`
 * selects light only for `Theme.LIGHT` and dark for everything else, so an unanswered question
 * renders the whole document dark without anything having chosen that.
 */
@OptIn(ExperimentalRemotePlayerApi::class)
@SuppressLint("RestrictedApiAndroidX")
@Suppress("PrimitiveInCollection")
@Composable
public fun RcPlayer(
  document: CoreDocument,
  modifier: Modifier = Modifier,
  theme: Int = Theme.SYSTEM,
  namedColorOverrides: ObjectIntMap<String> = emptyObjectIntMap(),
  imageLoader: RcImageLoader? = null,
  isShaderValid: (shaderSource: String) -> Boolean = { true },
  onAction: (actionId: Int, value: String?) -> Unit = { _, _ -> },
  onNamedAction: (name: String, value: Any?, stateUpdater: StateUpdater) -> Unit = { _, _, _ -> },
  customPlugins: CustomPluginRegistry? = null,
  lambdas: IntObjectMap<() -> Unit> = emptyIntObjectMap(),
  pendingIntents: IntObjectMap<PendingIntent> = emptyIntObjectMap(),
) {
  val clock = remember {
    if (document.clock is SystemClock) {
      RemoteClock.SYSTEM
    } else {
      document.clock
    }
  }

  // `SYSTEM` / `UNSPECIFIED` become a concrete mode here, once, before anything branches on them.
  val systemInDarkTheme = isSystemInDarkTheme()
  val resolvedTheme =
    remember(theme, systemInDarkTheme) { resolveThemeMode(theme, systemInDarkTheme) }

  val density = LocalDensity.current
  val context = LocalContext.current
  val remoteContext = remember {
    // Consider a Compose Clock
    AndroidRemoteContext(clock).also {
      it.setTypefaceResolver(EmbeddedPlayerTypefaceResolver(it))
      // Back the document's reactive scalar state (float/int/color) with Compose snapshot
      // state, so those variables resolve reactively without a per-id listener bridge (see
      // SnapshotRemoteComposeState / rememberRemoteFloatAsState). Swap before
      // initializeContext
      // propagates the document's state to the context, and re-gather the collections the
      // loader put in the previous state. Guard against re-installing onto a document already
      // initialized by a player (see the one-player-per-document note on RcPlayer): don't
      // clobber the existing snapshot state.
      if (document.remoteComposeState !is SnapshotRemoteComposeState) {
        document.setRemoteComposeState(SnapshotRemoteComposeState())
        document.recollectCollectionsReflection()
      }
      it.useChoreographer = true
      it.loadFloat(RemoteContext.ID_FONT_SIZE, 14f * density.fontScale * density.density)
      it.loadFloat(RemoteContext.ID_DENSITY, density.density)
      it.density = density.density
      // Use the overload that only binds/reset the context. The one-argument overload immediately
      // calls applyDataOperations(), which walks every top-level operation and eagerly applies
      // BitmapData (decoding every image before the document can compose).
      document.initializeContext(it, emptyMap())

      // Register each bitmap's metadata (declared width/height for ImageAttribute, and
      // discoverability for the lazy decode) WITHOUT decoding the pixels. The costly decode
      // is
      // deferred until a bitmap is actually drawn or its Image component composes — see
      // resolveBitmap / rememberRemoteBitmapAsState. (BitmapData.apply would also loadBitmap,
      // i.e. decode every bitmap up front, which is what we're avoiding.)
      val bitmaps = ArrayList<BitmapData>()
      findBitmaps(document.getOperationsReflection(), bitmaps)
      bitmaps.forEach { bitmap -> it.putObject(bitmap.mImageId, bitmap) }
      document.applyDataOperationsWithoutBitmaps(it)

      resolveAndroidThemeColors(context, document)
      it.paintTheme = resolvedTheme

      document.setLayoutCallback {}

      document.updateTimeReflection(it)
      document.registerVariablesReflection(it, document.getOperationsReflection())

      // Validate shaders before applying operations: a ShaderData only loads itself (via
      // ShaderData.apply -> loadShader) once enabled, and it defaults to disabled.
      // checkShaders
      // applies the shader source TextData and calls isShaderValid to enable approved
      // shaders,
      // so the subsequent applyOperations caches them for the draw path's buildRuntimeShader.
      // Mirrors the View player's RemoteComposePlayer.checkShaders(shaderControl).
      document.checkShaders(
        it,
        CoreDocument.ShaderControl { source -> isShaderValid(source) },
      )

      // Apply only the global setup ops here — those up to the root layout component (color
      // constants, named variables, top-level data collections, ...) — mirroring the core's
      // first-paint pass, which stops at the root layout. The layout tree's *internal* ops
      // are
      // applied in data order via getData below (and re-evaluated reactively at draw).
      // Eagerly
      // recursing the whole tree here evaluated layout-internal animation/array expressions
      // before the data collections they read (`[A_n]`) were populated, underflowing
      // AnimatedFloatExpression and crashing setup for time/array-driven documents.
      val rootComponent = document.rootLayoutComponent
      val globalOps =
        if (rootComponent != null) {
          ArrayList(document.getOperationsReflection().takeWhile { it !== rootComponent })
        } else {
          document.getOperationsReflection()
        }
      document.applyOperationsReflection(it, globalOps.withoutBitmaps())

      val constantOps = ArrayList<Operation>()
      fun walk(ops: Collection<Operation>) {
        for (op in ops) {
          val match =
            op is ColorConstant ||
              op is FloatConstant ||
              op is ColorTheme ||
              op is NamedVariable ||
              op.javaClass.simpleName.endsWith("Constant")
          if (match) {
            constantOps.add(op)
          }
          if (op is Container) {
            walk(op.getList())
          }
          if (op is LayoutComponent) {
            val canvasOps = op.getCanvasOperations()
            if (canvasOps != null) {
              walk(listOf(canvasOps))
            }
          }
        }
      }
      walk(document.getOperationsReflection())
      document.applyOperationsReflection(it, constantOps)

      // applyOperations above ran each ColorConstant -> loadColor, so every named color now
      // holds its authored default. Host theme overrides (if any) replace them by name, the
      // same path the View player's setColor(name, value) uses.
      namedColorOverrides.forEach { name, color ->
        val prefixedName = if (name.contains(':')) name else "USER:$name"
        it.setNamedColorOverride(prefixedName, color)
      }

      val dataOps = ArrayList<Operation>()
      document.rootLayoutComponent?.getData(dataOps, true)
      document.applyOperationsReflection(it, dataOps.withoutBitmaps())
    }
  }

  LaunchedEffect(remoteContext, resolvedTheme) {
    document.themedColors.orEmpty().forEach { it.setTheme(remoteContext, resolvedTheme) }
    remoteContext.paintTheme = resolvedTheme
  }

  // Time and animations are driven on demand. A static document — no declared animations and no
  // time-driven content — ticks for a frame and then the loop suspends, so the player goes fully
  // idle, like normal Compose. Documents with animations or time-driven variables
  // (continuous/seconds/minutes) keep the frame loop running. This replaces the previous
  // always-on rememberInfiniteTransition + unconditional per-frame full-document re-evaluation,
  // which never let the runtime go idle even for a wholly static document.
  val currentTimeMillisState = remember { mutableFloatStateOf(0f) }
  val hasAnimations =
    remember(document) {
      document.getFloatExpressionsReflection().values.any { it.mFloatAnimation != null }
    }
  // Pure-Compose time-dependence detection (no remote-core changes): scan each float
  // expression's NaN-encoded source operands for references to the continuously-changing time
  // variables. This mirrors how rememberRemoteExpression discovers an expression's variables.
  val isTimeDependent =
    remember(document) {
      val timeIds =
        intArrayOf(
          RemoteContext.ID_CONTINUOUS_SEC,
          RemoteContext.ID_TIME_IN_SEC,
          RemoteContext.ID_TIME_IN_MIN,
          RemoteContext.ID_TIME_IN_HR,
        )
      document.getFloatExpressionsReflection().values.any { expr ->
        expr.mSrcValue.any { v ->
          v.isNaN() &&
            !AnimatedFloatExpression.isMathOperator(v) &&
            !NanMap.isDataVariable(v) &&
            Utils.idFromNan(v) in timeIds
        }
      }
    }

  // Particle systems advance their simulation once per draw, so the frame loop must keep
  // ticking (and re-invalidating the particle draw) even if no expression is otherwise
  // time-dependent.
  val hasParticles = remember(document) { containsParticles(document.getOperationsReflection()) }

  // A WakeIn requests a future repaint; with no one-shot scheduler we keep the loop alive
  // instead.
  val hasWakeIn = remember(document) { containsWakeIn(document.getOperationsReflection()) }

  // `withInfiniteAnimationFrameMillis`, not `withFrameMillis`: this loop never terminates for an
  // animated / time-driven document, which is precisely what Compose means by an *infinite*
  // animation. Requesting frames through the infinite-animation channel routes them via the
  // `InfiniteAnimationPolicy` in the coroutine context, so a host that needs the composition to
  // reach idle can see through it. Under `ComposeTestRule` that is the difference between
  // `waitForIdle()` returning and hanging forever; under `@Preview` inspection it is what lets
  // tooling pause the animation instead of spinning. Outside a test the policy is absent and this
  // degrades to exactly `withFrameMillis`, so production timing is unchanged.
  LaunchedEffect(document, hasAnimations, isTimeDependent, hasParticles, hasWakeIn) {
    val startMillis = withInfiniteAnimationFrameMillis { it }
    while (true) {
      val frameMillis = withInfiniteAnimationFrameMillis { it } - startMillis
      // Pure time ticker. Updating currentTimeMillisState is the *only* per-frame work: every
      // reactive path keys off it. Expression display flows through the GraphContext
      // derivedStateOf graph and the float/int/color resolvers (which read this state for
      // time);
      // animated floats run on Compose's frame clock via rememberAnimatedRemoteFloat; and the
      // canvas draw path now reads time/variable values *through* the GraphContext too, so a
      // time-driven draw observes this state and re-runs when it ticks. No applyOperations,
      // no
      // updateVariables(mOperations) — the imperative per-frame recompute is fully gone.
      // (currentTime is still set for any core code that consults it directly.)
      currentTimeMillisState.floatValue = frameMillis.toFloat()
      remoteContext.currentTime = frameMillis

      // Settle to idle once the document is static: no declared float animation and no
      // continuously-changing time variable. Animated / time-driven documents keep looping.
      // TODO: also idle animated documents between animations and re-arm on host-driven
      // variable writes (see HISTORY.md, "Plan 1").
      if (!hasAnimations && !isTimeDependent && !hasParticles && !hasWakeIn) break
    }
  }

  // Pure-Compose evaluation of *derived/computed* operations (color & text expressions,
  // attributes,
  // lookups). Each computed id resolves to a derivedStateOf that runs the op's existing
  // updateVariables+apply against this GraphContext, which routes the op's reads to the reactive
  // store / other computed States and captures its write as the result. No imperative recompute
  // pass, no dirty flags — changing an input invalidates exactly the dependent States, and chains
  // compose naturally. (Frame loop above still drives time/animation; plain/expression float/int
  // and animated floats keep their dedicated resolvers.)
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

  // The document's root content description (Header DOC_CONTENT_DESCRIPTION /
  // RootContentDescription
  // op, resolved onto the document during initializeContext) labels the whole player for
  // accessibility — the embedded equivalent of the View player's root-view contentDescription.
  val rootContentDescription = remember(document) { document.contentDescription }

  // Desired frame rate (Header DOC_DESIRED_FPS): expressed as a platform hint via Compose's
  // preferredFrameRate modifier (which sets the layer's frame rate) rather than throttling the
  // time
  // ticker ourselves — the system then drives frames at this rate and the withFrameMillis loop
  // above advances time at the same cadence. 0 = no preference (absent / non-positive value).
  val desiredFps =
    remember(document) {
      (document.getProperty(Header.DOC_DESIRED_FPS) as? Int)
        ?.takeIf { it > 0 }
        ?.coerceAtMost(Limits.MAX_FPS)
        ?.toFloat() ?: 0f
    }

  var size by remember { mutableStateOf(IntSize.Zero) }
  androidx.compose.foundation.layout.BoxWithConstraints(
    modifier =
      modifier
        .then(
          // preferredFrameRate adds a graphicsLayer, so only apply it when a rate is set.
          if (desiredFps > 0f) Modifier.preferredFrameRate(desiredFps) else Modifier
        )
        .then(
          if (rootContentDescription != null)
            Modifier.semantics { contentDescription = rootContentDescription }
          else Modifier
        )
        .onPlaced {
          val position = it.positionOnScreen()
          document.setOrigin(position.x, position.y)
          size = it.size
        }
  ) {
    // ColorConstant / IntegerConstant / FloatExpression defaults already live in the
    // snapshot-backed store (applied by applyOperations during setup); the single store is the
    // source of truth for variables now, so there is no separate draw-path map to populate.

    // Identify ComponentValue operations
    val componentValueMap = remember { mutableMapOf<Int, MutableList<ComponentValue>>() }
    remember(document) {
      val componentValues = mutableListOf<ComponentValue>()
      findComponentValues(document.getOperationsReflection(), componentValues)
      componentValues.forEach { op ->
        var targetId = op.componentId
        val targetComponent = findComponent(document.getOperationsReflection(), targetId)
        if (targetComponent is LayoutComponentContent) {
          val parent = targetComponent.parent
          parent?.let { targetId = it.id }
        }
        val list = componentValueMap.getOrPut(targetId) { mutableListOf() }
        list.add(op)
      }
    }

    val componentValueStateMap = remember { mutableMapOf<Int, MutableState<Float>>() }
    remember(componentValueMap) {
      componentValueMap.values.flatten().forEach { op ->
        if (!componentValueStateMap.containsKey(op.valueId)) {
          componentValueStateMap[op.valueId] = mutableFloatStateOf(0f)
        }
      }
    }

    val stateUpdater =
      remember(remoteContext) {
        androidx.compose.remote.player.core.state.StateUpdaterImpl(remoteContext)
      }
    // The image loader: the caller-supplied one, or the default that wraps embedded bitmaps.
    val resolvedImageLoader =
      remember(remoteContext, imageLoader) { imageLoader ?: EmbeddedRcImageLoader(remoteContext) }
    // Make it reachable from the (non-composable) canvas draw path too — the document image
    // draws
    // resolve through it via the GraphContext.
    graphContext?.imageLoader = resolvedImageLoader
    CompositionLocalProvider(
      LocalCoreDocument provides document,
      LocalRemoteContext provides remoteContext,
      LocalComponentValueMap provides componentValueMap,
      LocalComponentValueStateMap provides componentValueStateMap,
      LocalCurrentTimeMillis provides currentTimeMillisState,
      LocalGraphContext provides graphContext,
      LocalRcImageLoader provides resolvedImageLoader,
      LocalRemoteActionHandler provides onAction,
      LocalRemoteNamedActionHandler provides
        { name, value ->
          val lambdaId = LambdaAction.parseId(name)
          if (lambdaId != null) {
            lambdas[lambdaId]?.invoke()
          } else {
            val pendingIntentId = PendingIntentAction.parseId(name)
            if (pendingIntentId != null) {
              pendingIntents[pendingIntentId]?.send()
            }
          }
          onNamedAction(name, value, stateUpdater)
        },
      LocalRcCustomPlugins provides customPlugins,
    ) {
      val rootSize = IntSize(constraints.maxWidth, constraints.maxHeight)
      if (document.rootLayoutComponent != null) {
        RcPlayerRootLayoutComponent(rootSize)
      } else {
        // Raw draw-list document (no layout component tree): render its operations
        // directly.
        RcPlayerRawDocument(rootSize)
      }
    }
  }
}

/**
 * A player of a [CapturedDocument].
 *
 * This overload extracts the [CoreDocument] and any associated lambdas from the [CapturedDocument]
 * and forwards them to the underlying [RcPlayer].
 */
@OptIn(ExperimentalRemotePlayerApi::class)
@SuppressLint("RestrictedApiAndroidX")
@Suppress("PrimitiveInCollection")
@Composable
public fun RcPlayer(
  capturedDocument: CapturedDocument,
  modifier: Modifier = Modifier,
  theme: Int = Theme.SYSTEM,
  namedColorOverrides: ObjectIntMap<String> = emptyObjectIntMap(),
  imageLoader: RcImageLoader? = null,
  isShaderValid: (shaderSource: String) -> Boolean = { true },
  onAction: (actionId: Int, value: String?) -> Unit = { _, _ -> },
  onNamedAction: (name: String, value: Any?, stateUpdater: StateUpdater) -> Unit = { _, _, _ -> },
  customPlugins: CustomPluginRegistry? = null,
) {
  val coreDoc =
    remember(capturedDocument) {
      // Ahead of the parse below, which fails the whole document on a URL-encoded bitmap
      // unless the globals are set. This overload is only one of the byte-level entry
      // points — `RemoteDocument(bytes)` parses in its own constructor, so callers taking
      // that route enable it themselves.
      enableEncodedImageReferences()
      CoreDocument(RemoteClock.SYSTEM).apply {
        ByteArrayInputStream(capturedDocument.bytes).use {
          initFromBuffer(RemoteComposeBuffer.fromInputStream(it))
        }
      }
    }

  RcPlayer(
    document = coreDoc,
    modifier = modifier,
    theme = theme,
    namedColorOverrides = namedColorOverrides,
    imageLoader = imageLoader,
    isShaderValid = isShaderValid,
    onAction = onAction,
    onNamedAction = onNamedAction,
    customPlugins = customPlugins,
    lambdas = capturedDocument.lambdas,
    pendingIntents = capturedDocument.pendingIntents,
  )
}

/** True if the op tree contains a particle loop (drives the frame-loop keepalive). */
private fun containsParticles(operations: Collection<Operation>): Boolean = operations.any { op ->
  op is ParticlesLoop ||
    op is ParticlesCompare ||
    (op is Container && containsParticles(op.getList()))
}

/**
 * True if the op tree contains a [WakeIn], which asks the runtime to repaint after a delay. The
 * embedded player has no one-shot scheduler, so we approximate by keeping the frame loop alive —
 * the content re-evaluates and redraws continuously, a superset of the requested single wake.
 */
private fun containsWakeIn(operations: Collection<Operation>): Boolean = operations.any { op ->
  op is WakeIn || (op is Container && containsWakeIn(op.getList()))
}

private fun findBitmaps(operations: Collection<Operation>, list: MutableList<BitmapData>) {
  operations.forEach { op ->
    if (op is BitmapData) {
      list.add(op)
    }
    if (op is Container) {
      findBitmaps(op.getList(), list)
    }
  }
}

/** Bitmap pixels are resolved on first draw; setup passes must only apply the other data ops. */
private fun Collection<Operation>.withoutBitmaps(): ArrayList<Operation> =
  filterTo(ArrayList(size)) { it !is BitmapData }

private fun findComponentValues(
  operations: Collection<Operation>,
  list: MutableList<ComponentValue>,
) {
  operations.forEach { op ->
    if (op is ComponentValue) {
      list.add(op)
    }
    if (op is Container) {
      findComponentValues(op.getList(), list)
    }
    if (op is LayoutComponent) {
      val canvasOps = op.getCanvasOperations()
      if (canvasOps != null) {
        findComponentValues(listOf(canvasOps), list)
      }
    }
  }
}

private fun findComponent(operations: Collection<Operation>, id: Int): Component? {
  for (op in operations) {
    if (op is Component && op.componentId == id) {
      return op
    }
    if (op is LayoutComponent) {
      val content = op.getContentReflection()
      if (content != null && content.componentId == id) {
        return content
      }
      val canvasOps = op.getCanvasOperations()
      if (canvasOps != null) {
        val found = findComponent(listOf(canvasOps), id)
        if (found != null) {
          return found
        }
      }
    }
    if (op is Container) {
      val found = findComponent(op.getList(), id)
      if (found != null) {
        return found
      }
    }
  }
  return null
}
