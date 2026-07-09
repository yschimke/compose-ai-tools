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
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureHorizontalPageIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicator
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import ee.schimke.composeai.daemon.GestureHint
import ee.schimke.composeai.daemon.GestureType
import ee.schimke.composeai.daemon.rememberForcedGestureHintSource
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
}

@Composable
fun GestureGalleryApp(
  navController: NavHostController = rememberSwipeDismissableNavController()
) {
  MaterialTheme {
    AppScaffold(timeText = { TimeText() }) {
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
 * A titled full-screen gesture demo: a [ListHeader] title, an instruction line, and the interactive
 * affordance centred below — the layout the catalog's full-screen Wear stickers use so the screen
 * reads clearly instead of a bare control lost on the watch face.
 */
@Composable
private fun GestureDemoScreen(title: String, instruction: String, control: @Composable () -> Unit) {
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

/** The double-pinch primary action on a play/pause [Button], wrapped in its [GestureHint]. */
@Composable
private fun PlayGestureButton(forceHint: Boolean) {
  var playing by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  GestureHint(
    type = GestureType.PRIMARY,
    interactionSource = interactionSource,
    forceShow = forceHint,
  ) {
    Button(
      onClick = { playing = !playing },
      interactionSource = interactionSource,
      modifier =
        Modifier.reportedOneHandedGesture(
          type = GestureType.PRIMARY,
          label = if (playing) "Pause" else "Play",
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
  GestureDemoScreen(title = "Primary", instruction = "Double-pinch to play") {
    PlayGestureButton(forceHint)
  }
}

/** Wrist-turn dismiss mapped to back navigation, with a dismiss-hint affordance. */
@Composable
fun DismissActionScreen(onDismiss: () -> Unit = {}, forceHint: Boolean = false) {
  val interactionSource = remember { MutableInteractionSource() }
  GestureDemoScreen(title = "Dismiss", instruction = "Wrist-turn to go back") {
    GestureHint(
      type = GestureType.DISMISS,
      interactionSource = interactionSource,
      forceShow = forceHint,
    ) {
      FilledTonalButton(
        onClick = onDismiss,
        interactionSource = interactionSource,
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.DISMISS,
            label = "Dismiss",
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
  val hintSource =
    if (forceHint) rememberForcedGestureHintSource(GestureType.SCROLL) else interactionSource
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
        interactionSource = hintSource,
        state = listState,
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
  val hintSource =
    if (forceHint) rememberForcedGestureHintSource(GestureType.PAGE) else interactionSource
  ScreenScaffold {
    Box(modifier = Modifier.fillMaxSize()) {
      HorizontalPager(
        state = pagerState,
        modifier =
          Modifier.fillMaxSize()
            .reportedOneHandedGesture(
              type = GestureType.PAGE,
              label = "Next page",
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
        interactionSource = hintSource,
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
  CompositionLocalProvider(LocalOneHandedGestureEnabled provides false) {
    GestureDemoScreen(title = "Disabled", instruction = "Gestures off on this screen") {
      Button(
        onClick = {},
        modifier =
          Modifier.reportedOneHandedGesture(
            type = GestureType.PRIMARY,
            label = "Play (disabled)",
            interactionSource = interactionSource,
          ) {},
      ) {
        Text("Tap only")
      }
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
    OneHandedGestureScrollIndicator(
      interactionSource = rememberForcedGestureHintSource(GestureType.SCROLL),
      state = listState,
      modifier = Modifier.size(48.dp),
    )
  }
}

@Preview(showBackground = false)
@Composable
fun PageIndicatorStickerPreview() {
  GestureSticker {
    OneHandedGestureHorizontalPageIndicator(
      interactionSource = rememberForcedGestureHintSource(GestureType.PAGE),
      pagerState = rememberPagerState(initialPage = 1, pageCount = { 4 }),
    )
  }
}

@WearPreviewLargeRound
@Composable
fun GestureGalleryPreview() {
  GestureGalleryApp()
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
