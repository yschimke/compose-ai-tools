package com.example.samplewear

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.onehandedgesture.LocalOneHandedGestureEnabled
import androidx.wear.compose.material3.onehandedgesture.GestureAction
import androidx.wear.compose.material3.onehandedgesture.GesturePriority
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureConfiguration
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureHorizontalPageIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePageIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicatorState
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions
import kotlinx.coroutines.launch

/**
 * A one-handed-gesture gallery for Wear OS, navigated with [SwipeDismissableNavHost].
 *
 * Each screen follows the AndroidX samples and uses the public one-handed gesture APIs directly:
 * - **primary** — the double-pinch primary action on a [Button], with an inline
 *   [OneHandedGestureClickIndicator].
 * - **dismiss** — the wrist-turn dismiss action mapped to back navigation.
 * - **scroll** — the primary gesture driving `scrollDown` on a list.
 * - **page** — the primary gesture driving `scrollToNextPage` on a pager.
 * - **disabled** — a screen that opts out via `LocalOneHandedGestureEnabled = false`.
 *
 * On-device the gestures fire from the watch's sensors (Pixel Watch 3+). The preview renderer fakes
 * Wear's SDK gesture input manager, so these unmodified public-API components can also register,
 * report availability, and receive gestures under Robolectric. Previews drive the same public
 * indicator states explicitly for deterministic animation capture.
 */
object GestureRoutes {
  const val HOME = "home"
  const val PRIMARY = "primary"
  const val DISMISS = "dismiss"
  const val SCROLL = "scroll"
  const val PAGE = "page"
  const val DISABLED = "disabled"
  const val HINT_BUTTON = "hint-button"
}

@Composable
internal fun rememberGestureConfiguration(
  action: GestureAction,
  key: String,
  priority: GesturePriority = GesturePriority.Clickable,
): OneHandedGestureConfiguration =
  rememberOneHandedGestureConfiguration(
    action = action,
    gestureId = key,
    priority = priority,
  )

@Composable
internal fun rememberGestureIndicatorState(
  forceShow: Boolean = false
): OneHandedGestureClickIndicatorState {
  val state = remember { OneHandedGestureClickIndicatorState() }
  LaunchedEffect(forceShow, state) {
    if (forceShow) {
      state.showIndicator()
    }
  }
  return state
}

@Composable
private fun rememberScrollGestureIndicatorState(
  forceShow: Boolean = false
): OneHandedGestureScrollIndicatorState {
  val state = remember { OneHandedGestureScrollIndicatorState() }
  LaunchedEffect(forceShow, state) {
    if (forceShow) state.showIndicator()
  }
  return state
}

@Composable
private fun rememberPageGestureIndicatorState(
  forceShow: Boolean = false
): OneHandedGesturePageIndicatorState {
  val state = remember { OneHandedGesturePageIndicatorState() }
  LaunchedEffect(forceShow, state) {
    if (forceShow) state.showIndicator()
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
 * A small sample screen shell. Gesture behavior remains in the reusable component passed as
 * [control], just as it would in application code.
 */
@Composable
private fun GestureDemoScreen(
  title: String,
  instruction: String,
  control: @Composable () -> Unit,
) {
  ScreenScaffold {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      ListHeader { Text(title) }
      Text(instruction, textAlign = TextAlign.Center)
      Box(modifier = Modifier.padding(top = 12.dp)) { control() }
    }
  }
}

/** AndroidX-style button whose indicator owns the inline content and hides it while animating. */
@Composable
private fun PlayGestureButton(forceHint: Boolean) {
  var playing by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureAction.Primary, key = "samplewear:play")
  val indicatorState = rememberGestureIndicatorState(forceShow = forceHint)
  val coroutineScope = rememberCoroutineScope()
  Button(
    onClick = { playing = !playing },
    interactionSource = interactionSource,
    modifier =
      Modifier.oneHandedGesture(
        gestureConfiguration = gestureConfiguration,
        interactionSource = interactionSource,
        onGestureLabel = if (playing) "Pause" else "Play",
        onGestureAvailable = { coroutineScope.launch { indicatorState.showIndicator() } },
      ) {
        playing = !playing
      },
  ) {
    OneHandedGestureClickIndicator(gestureConfiguration, indicatorState) {
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
  ) {
    PlayGestureButton(forceHint)
  }
}

/** Wrist-turn dismiss mapped to back navigation, with a dismiss-hint affordance. */
@Composable
fun DismissActionScreen(onDismiss: () -> Unit = {}, forceHint: Boolean = false) {
  val interactionSource = remember { MutableInteractionSource() }
  val gestureConfiguration =
    rememberGestureConfiguration(GestureAction.Dismiss, key = "samplewear:dismiss")
  val indicatorState = rememberGestureIndicatorState(forceShow = forceHint)
  val coroutineScope = rememberCoroutineScope()
  GestureDemoScreen(
    title = "Dismiss",
    instruction = "Wrist-turn to go back",
  ) {
    FilledTonalButton(
      onClick = onDismiss,
      interactionSource = interactionSource,
      modifier =
        Modifier.oneHandedGesture(
          gestureConfiguration = gestureConfiguration,
          interactionSource = interactionSource,
          onGestureLabel = "Dismiss",
          onGestureAvailable = { coroutineScope.launch { indicatorState.showIndicator() } },
          onGesture = onDismiss,
        ),
    ) {
      OneHandedGestureClickIndicator(gestureConfiguration, indicatorState) {
        Text("Back")
      }
    }
  }
}

/** Primary gesture driving `scrollDown` on a list, with the gesture scroll indicator. */
@Composable
fun ScrollGestureScreen(forceHint: Boolean = false) {
  val listState = rememberTransformingLazyColumnState()
  val coroutineScope = rememberCoroutineScope()
  val gestureConfiguration =
    rememberGestureConfiguration(
      GestureAction.Primary,
      key = "samplewear:scroll",
      priority = GesturePriority.Scrollable,
    )
  val indicatorState = rememberScrollGestureIndicatorState(forceHint)
  ScreenScaffold(scrollState = listState) { contentPadding ->
    Box(modifier = Modifier.fillMaxSize()) {
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier =
          Modifier.fillMaxSize()
            .oneHandedGesture(
              gestureConfiguration = gestureConfiguration,
              onGestureLabel = "Scroll down",
              onGestureAvailable = { coroutineScope.launch { indicatorState.showIndicator() } },
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
  val coroutineScope = rememberCoroutineScope()
  val gestureConfiguration =
    rememberGestureConfiguration(
      GestureAction.Primary,
      key = "samplewear:page",
      priority = GesturePriority.Scrollable,
    )
  val indicatorState = rememberPageGestureIndicatorState(forceHint)
  ScreenScaffold {
    Box(modifier = Modifier.fillMaxSize()) {
      HorizontalPager(
        state = pagerState,
        modifier =
          Modifier.fillMaxSize()
            .oneHandedGesture(
              gestureConfiguration = gestureConfiguration,
              onGestureLabel = "Next page",
              onGestureAvailable = { coroutineScope.launch { indicatorState.showIndicator() } },
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
    rememberGestureConfiguration(GestureAction.Primary, key = "samplewear:disabled-play")
  CompositionLocalProvider(LocalOneHandedGestureEnabled provides false) {
    GestureDemoScreen(title = "Disabled", instruction = "Gestures off on this screen") {
      Button(
        onClick = {},
        modifier =
          Modifier.oneHandedGesture(
            gestureConfiguration = gestureConfiguration,
            interactionSource = interactionSource,
            onGestureLabel = "Play (disabled)",
          ) {},
      ) {
        Text("Tap only")
      }
    }
  }
}

/**
 * The AndroidX button-hint pattern. [OneHandedGestureClickIndicator] owns the button content, so
 * the normal label is hidden while the hint is visible instead of being painted underneath it.
 */
@Composable
fun ButtonHintScreen(showHint: Boolean = true) {
  GestureDemoScreen(title = "Button hint", instruction = "Double-pinch to play") {
    PlayGestureButton(forceHint = showHint)
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
@RoboComposePreviewOptions(
  manualClockOptions = [ManualClockOptions(advanceTimeMillis = 800L)]
)
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
      rememberGestureConfiguration(
        GestureAction.Primary,
        key = "samplewear:scroll-sticker",
        priority = GesturePriority.Scrollable,
      )
    OneHandedGestureScrollIndicator(
      gestureConfiguration = gestureConfiguration,
      indicatorState = rememberScrollGestureIndicatorState(forceShow = true),
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
      rememberGestureConfiguration(
        GestureAction.Primary,
        key = "samplewear:page-sticker",
        priority = GesturePriority.Scrollable,
      )
    OneHandedGestureHorizontalPageIndicator(
      gestureConfiguration = gestureConfiguration,
      indicatorState = rememberPageGestureIndicatorState(forceShow = true),
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
@RoboComposePreviewOptions(
  manualClockOptions = [ManualClockOptions(advanceTimeMillis = 800L)]
)
@Composable
fun PrimaryActionScreenPreview() {
  MaterialTheme { PrimaryActionScreen(forceHint = true) }
}

@WearPreviewLargeRound
@RoboComposePreviewOptions(
  manualClockOptions = [ManualClockOptions(advanceTimeMillis = 800L)]
)
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
@RoboComposePreviewOptions(
  manualClockOptions = [ManualClockOptions(advanceTimeMillis = 800L)]
)
@Composable
fun ButtonHintScreenPreview() {
  MaterialTheme { ButtonHintScreen(showHint = true) }
}
