package com.example.samplewear

import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.onehandedgesture.LocalOneHandedGestureEnabled
import androidx.wear.compose.material3.onehandedgesture.GesturePriority
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureConfiguration
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureHorizontalPageIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicator
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import ee.schimke.composeai.daemon.GestureHint
import ee.schimke.composeai.daemon.GestureType
import ee.schimke.composeai.daemon.reportedOneHandedGesture
import kotlinx.coroutines.launch

/**
 * A one-handed-gesture gallery for Wear OS, navigated with [SwipeDismissableNavHost].
 *
 * Each screen wires the real `Modifier.oneHandedGesture` API (via the `:data-gestures-connector`
 * [reportedOneHandedGesture] seam, so the handlers also surface in the `compose/gestures` data
 * product) and demonstrates one gesture affordance from the Wear design guide:
 * - **primary** — the double-pinch primary action on a [Button], with a [GestureHint].
 * - **dismiss** — the wrist-turn dismiss action mapped to back navigation.
 * - **scroll** — the primary gesture driving `scrollDown` on a list.
 * - **page** — the primary gesture driving `scrollToNextPage` on a pager.
 * - **disabled** — a screen that opts out via `LocalOneHandedGestureEnabled = false`.
 *
 * On-device the gestures fire from the watch's sensors (Pixel Watch 3+); off-device they no-op, so
 * the connector's data product is what makes the wiring observable and invokable under `@Preview`.
 */
object GestureRoutes {
  const val HOME = "home"
  const val PRIMARY = "primary"
  const val DISMISS = "dismiss"
  const val SCROLL = "scroll"
  const val PAGE = "page"
  const val DISABLED = "disabled"
  const val HINT_BUTTON = "hint-button"
  const val HINT_FLOATING = "hint-floating"
}

@Composable
internal fun rememberGestureConfiguration(
  type: GestureType,
  key: String,
  priority: GesturePriority = GesturePriority.Clickable,
): OneHandedGestureConfiguration =
  rememberOneHandedGestureConfiguration(
    action = type.toGestureAction(),
    key = key,
    priority = priority,
  )

@Composable
internal fun rememberGestureIndicatorState(
  forceShow: Boolean = false
): OneHandedGestureIndicatorState {
  val state = remember { OneHandedGestureIndicatorState() }
  LaunchedEffect(forceShow, state) {
    if (forceShow) {
      state.isIndicatorActive = true
    }
  }
  return state
}

@Composable
fun GestureGalleryApp(
  navController: NavHostController = rememberSwipeDismissableNavController(),
  // Real production app — let TimeText use the system clock. Previews that want a
  // deterministic time supply a `FixedPreviewTimeSource` (see [GestureGalleryPreview]).
  timeText: @Composable () -> Unit = { TimeText() },
) {
  MaterialTheme {
    AppScaffold(timeText = timeText) {
      SwipeDismissableNavHost(
        navController = navController,
        startDestination = GestureRoutes.HOME,
      ) {
        composable(GestureRoutes.HOME) { GestureHomeScreen(onOpen = navController::navigate) }
        composable(GestureRoutes.PRIMARY) { PrimaryActionScreen() }
        composable(GestureRoutes.DISMISS) {
          DismissActionScreen(onDismiss = { navController.popBackStack() })
        }
        composable(GestureRoutes.SCROLL) { ScrollGestureScreen() }
        composable(GestureRoutes.PAGE) { PageGestureScreen() }
        composable(GestureRoutes.DISABLED) { DisabledGestureScreen() }
        composable(GestureRoutes.HINT_BUTTON) { ButtonHintScreen() }
        composable(GestureRoutes.HINT_FLOATING) { FloatingHintScreen() }
      }
    }
  }
}

@Composable
private fun GestureHomeScreen(onOpen: (String) -> Unit) {
  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize(),
    ) {
      item { ListHeader { Text("Gestures") } }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.PRIMARY) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Primary action")
        }
      }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.DISMISS) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Dismiss action")
        }
      }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.SCROLL) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Scroll gesture")
        }
      }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.PAGE) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Page gesture")
        }
      }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.HINT_BUTTON) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Button hint")
        }
      }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.HINT_FLOATING) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Floating hint")
        }
      }
      item {
        FilledTonalButton(
          onClick = { onOpen(GestureRoutes.DISABLED) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Disabled")
        }
      }
    }
  }
}

/**
 * Renders wear-compose-material3's shipped gesture-indicator AVD
 * (`wear_one_handed_gesture_{primary,dismiss}_indicator_animation`) as a static, tinted icon via the
 * official `androidx.compose.animation.graphics` API — the same drawable + API
 * `OneHandedGestureIndicator` draws internally, shown here at its resting frame so the gesture
 * illustration is visible in a still capture (the interactive indicator only flashes it on-device).
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun GestureHintIcon(
  type: GestureType,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  tint: Color = LocalContentColor.current,
) {
  val resId =
    when (type) {
      GestureType.DISMISS ->
        androidx.wear.compose.material3.R.drawable
          .wear_one_handed_gesture_dismiss_indicator_animation
      else ->
        androidx.wear.compose.material3.R.drawable
          .wear_one_handed_gesture_primary_indicator_animation
    }
  val avd = AnimatedImageVector.animatedVectorResource(resId)
  Image(
    painter = rememberAnimatedVectorPainter(avd, atEnd = false),
    contentDescription = null,
    colorFilter = ColorFilter.tint(tint),
    modifier = modifier.size(size),
  )
}

/**
 * A titled full-screen gesture demo: a [ListHeader] title, the shipped [GestureHintIcon] gesture
 * illustration, an instruction line, and the interactive affordance centred below — the layout the
 * catalog's full-screen Wear stickers use so the screen reads clearly instead of a bare control lost
 * on the watch face.
 */
@Composable
private fun GestureDemoScreen(
  title: String,
  instruction: String,
  gestureType: GestureType? = null,
  control: @Composable () -> Unit,
) {
  ScreenScaffold {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      ListHeader { Text(title) }
      gestureType?.let { GestureHintIcon(it, modifier = Modifier.padding(bottom = 4.dp)) }
      Text(instruction, textAlign = TextAlign.Center)
      Box(modifier = Modifier.padding(top = 12.dp)) { control() }
    }
  }
}

/** The double-pinch primary action on a play/pause [Button], wrapped in its [GestureHint]. */
@Composable
private fun PlayGestureButton(forceHint: Boolean) {
  var playing by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.PRIMARY, key = "samplewear:play")
  val indicatorState = rememberGestureIndicatorState()
  GestureHint(
    gestureConfiguration = gestureConfiguration,
    indicatorState = indicatorState,
    forceShow = forceHint,
  ) {
    Button(
      onClick = { playing = !playing },
      interactionSource = interactionSource,
      modifier =
        Modifier.reportedOneHandedGesture(
          type = GestureType.PRIMARY,
          label = if (playing) "Pause" else "Play",
          gestureConfiguration = gestureConfiguration,
          indicatorState = indicatorState,
          interactionSource = interactionSource,
        ) {
          playing = !playing
        },
    ) {
      Text(if (playing) "Pause" else "Play")
    }
  }
}

/** Primary action (double pinch) on a play/pause button. */
@Composable
fun PrimaryActionScreen(forceHint: Boolean = false) {
  GestureDemoScreen(
    title = "Primary",
    instruction = "Double-pinch to play",
    gestureType = GestureType.PRIMARY,
  ) {
    PlayGestureButton(forceHint)
  }
}

/** Wrist-turn dismiss mapped to back navigation, with a dismiss-hint affordance. */
@Composable
fun DismissActionScreen(onDismiss: () -> Unit = {}, forceHint: Boolean = false) {
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.DISMISS, key = "samplewear:dismiss")
  val indicatorState = rememberGestureIndicatorState()
  GestureDemoScreen(
    title = "Dismiss",
    instruction = "Wrist-turn to go back",
    gestureType = GestureType.DISMISS,
  ) {
    GestureHint(
      gestureConfiguration = gestureConfiguration,
      indicatorState = indicatorState,
      forceShow = forceHint,
    ) {
      FilledTonalButton(
        onClick = onDismiss,
        interactionSource = interactionSource,
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.DISMISS,
            label = "Dismiss",
            gestureConfiguration = gestureConfiguration,
            indicatorState = indicatorState,
            interactionSource = interactionSource,
          ) {
            onDismiss()
          },
      ) {
        Text("Back")
      }
    }
  }
}

/** Primary gesture driving `scrollDown` on a list, with the gesture scroll indicator. */
@Composable
fun ScrollGestureScreen(forceHint: Boolean = false) {
  val listState = rememberTransformingLazyColumnState()
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.SCROLL, key = "samplewear:scroll")
  val indicatorState = rememberGestureIndicatorState(forceHint)
  ScreenScaffold(scrollState = listState) { contentPadding ->
    Box(modifier = Modifier.fillMaxSize()) {
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier =
          Modifier.fillMaxSize()
            .reportedOneHandedGesture(
              type = GestureType.SCROLL,
              label = "Scroll down",
              gestureConfiguration = gestureConfiguration,
              indicatorState = indicatorState,
              interactionSource = interactionSource,
            ) {
              OneHandedGestureDefaults.scrollDown(listState)
            },
      ) {
        item { ListHeader { Text("Scroll") } }
        items(12) { index ->
          TitleCard(
            onClick = {},
            title = { Text("Item ${index + 1}") },
            subtitle = { Text("Double-pinch to scroll") },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      OneHandedGestureScrollIndicator(
        gestureConfiguration = gestureConfiguration,
        indicatorState = indicatorState,
        scrollState = listState,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
    }
  }
}

/** Primary gesture driving `scrollToNextPage` on a pager, with the gesture page indicator. */
@Composable
fun PageGestureScreen(forceHint: Boolean = false) {
  val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.PAGE, key = "samplewear:page")
  val indicatorState = rememberGestureIndicatorState(forceHint)
  ScreenScaffold {
    Box(modifier = Modifier.fillMaxSize()) {
      HorizontalPager(
        state = pagerState,
        modifier =
          Modifier.fillMaxSize()
            .reportedOneHandedGesture(
              type = GestureType.PAGE,
              label = "Next page",
              gestureConfiguration = gestureConfiguration,
              indicatorState = indicatorState,
              interactionSource = interactionSource,
            ) {
              OneHandedGestureDefaults.scrollToNextPage(pagerState)
            },
      ) { page ->
        Box(
          modifier = Modifier.fillMaxSize().padding(16.dp),
          contentAlignment = Alignment.Center,
        ) {
          Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("Page ${page + 1}")
              Text("Double-pinch for next")
            }
          }
        }
      }
      OneHandedGestureHorizontalPageIndicator(
        gestureConfiguration = gestureConfiguration,
        indicatorState = indicatorState,
        pagerState = pagerState,
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

/** A screen that opts out of one-handed gestures via `LocalOneHandedGestureEnabled = false`. */
@Composable
fun DisabledGestureScreen() {
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.PRIMARY, key = "samplewear:disabled-play")
  CompositionLocalProvider(LocalOneHandedGestureEnabled provides false) {
    GestureDemoScreen(title = "Disabled", instruction = "Gestures off on this screen") {
      Button(
        onClick = {},
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.PRIMARY,
            label = "Play (disabled)",
            gestureConfiguration = gestureConfiguration,
            interactionSource = interactionSource,
          ) {},
      ) {
        Text("Tap only")
      }
    }
  }
}

/**
 * The design guide's **button hint**: the gesture-indicator icon drawn *within* the target element,
 * tinted to match the button's content colour (`onPrimary`). This is the affordance
 * `OneHandedGestureIndicator` flashes over the button on-device; here it's composited statically so
 * the "icon on the button" treatment is visible in a still frame.
 */
@Composable
fun ButtonHintScreen(showHint: Boolean = true) {
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.PRIMARY, key = "samplewear:button-hint")
  GestureDemoScreen(title = "Button hint", instruction = "Icon drawn on the button") {
    Box(contentAlignment = Alignment.Center) {
      Button(
        onClick = {},
        interactionSource = interactionSource,
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.PRIMARY,
            label = "Play",
            gestureConfiguration = gestureConfiguration,
            interactionSource = interactionSource,
          ) {},
      ) {
        Text("Play")
      }
      if (showHint) {
        GestureHintIcon(
          type = GestureType.PRIMARY,
          size = 28.dp,
          tint = MaterialTheme.colorScheme.onPrimary,
        )
      }
    }
  }
}

/**
 * The design guide's **floating hint**: a `Tertiary` bubble overlay carrying the gesture-indicator
 * icon (`onTertiary`) with a pointer aimed at the target element. Shown above the play button.
 */
@Composable
fun FloatingHintScreen(showHint: Boolean = true) {
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureType.PRIMARY, key = "samplewear:floating-hint")
  GestureDemoScreen(title = "Floating hint", instruction = "Bubble points to the button") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      if (showHint) {
        FloatingHintBubble()
        Spacer(Modifier.size(8.dp))
      }
      Button(
        onClick = {},
        interactionSource = interactionSource,
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.PRIMARY,
            label = "Play",
            gestureConfiguration = gestureConfiguration,
            interactionSource = interactionSource,
          ) {},
      ) {
        Text("Play")
      }
    }
  }
}

/** The tertiary hint bubble + downward pointer used by [FloatingHintScreen]. */
@Composable
private fun FloatingHintBubble() {
  val container = MaterialTheme.colorScheme.tertiary
  val onContainer = MaterialTheme.colorScheme.onTertiary
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
      modifier =
        Modifier.clip(RoundedCornerShape(percent = 50))
          .background(container)
          .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      GestureHintIcon(type = GestureType.PRIMARY, size = 24.dp, tint = onContainer)
      Spacer(Modifier.width(6.dp))
      Text("Double-pinch", color = onContainer)
    }
    Canvas(modifier = Modifier.size(width = 16.dp, height = 8.dp)) {
      val pointer =
        Path().apply {
          moveTo(0f, 0f)
          lineTo(size.width, 0f)
          lineTo(size.width / 2f, size.height)
          close()
        }
      drawPath(pointer, color = container)
    }
  }
}

// ---------------------------------------------------------------------------
// Previews. Close-up "stickers" (transparent background, cropped tight to the
// control — the catalog's `WearSticker` treatment) show each affordance clearly;
// the full-round previews show the same control in its on-watch screen.
// ---------------------------------------------------------------------------

@Composable
private fun GestureSticker(content: @Composable () -> Unit) {
  MaterialTheme { Box(Modifier.padding(8.dp)) { content() } }
}

@Preview(showBackground = false)
@Composable
fun PrimaryActionStickerPreview() {
  GestureSticker { PlayGestureButton(forceHint = true) }
}

@Preview(showBackground = false)
@Composable
fun ScrollIndicatorStickerPreview() {
  GestureSticker {
    val listState = rememberTransformingLazyColumnState()
    val gestureConfiguration =
      rememberGestureConfiguration(GestureType.SCROLL, key = "samplewear:scroll-sticker")
    OneHandedGestureScrollIndicator(
      gestureConfiguration = gestureConfiguration,
      indicatorState = rememberGestureIndicatorState(forceShow = true),
      scrollState = listState,
      modifier = Modifier.size(48.dp),
    )
  }
}

@Preview(showBackground = false)
@Composable
fun PageIndicatorStickerPreview() {
  GestureSticker {
    val gestureConfiguration =
      rememberGestureConfiguration(GestureType.PAGE, key = "samplewear:page-sticker")
    OneHandedGestureHorizontalPageIndicator(
      gestureConfiguration = gestureConfiguration,
      indicatorState = rememberGestureIndicatorState(forceShow = true),
      pagerState = rememberPagerState(initialPage = 1, pageCount = { 4 }),
    )
  }
}

@WearPreviewLargeRound
@Composable
fun GestureGalleryPreview() {
  GestureGalleryApp(timeText = { TimeText(timeSource = FixedPreviewTimeSource) })
}

@WearPreviewLargeRound
@Composable
fun PrimaryActionScreenPreview() {
  MaterialTheme { PrimaryActionScreen(forceHint = true) }
}

@WearPreviewLargeRound
@Composable
fun DismissActionScreenPreview() {
  MaterialTheme { DismissActionScreen(forceHint = true) }
}

@WearPreviewLargeRound
@Composable
fun ScrollGestureScreenPreview() {
  MaterialTheme { ScrollGestureScreen(forceHint = true) }
}

@WearPreviewLargeRound
@Composable
fun PageGestureScreenPreview() {
  MaterialTheme { PageGestureScreen(forceHint = true) }
}

@WearPreviewLargeRound
@Composable
fun DisabledGestureScreenPreview() {
  MaterialTheme { DisabledGestureScreen() }
}

@WearPreviewLargeRound
@Composable
fun ButtonHintScreenPreview() {
  MaterialTheme { ButtonHintScreen(showHint = true) }
}

@WearPreviewLargeRound
@Composable
fun FloatingHintScreenPreview() {
  MaterialTheme { FloatingHintScreen(showHint = true) }
}
