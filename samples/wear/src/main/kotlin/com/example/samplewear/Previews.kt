package com.example.samplewear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import androidx.wear.compose.ui.tooling.preview.WearPreviewSmallRound
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

private data class Item(val title: String, val subtitle: String)

private val sampleItems =
  listOf(
    Item("Morning run", "5.2 km · 28 min"),
    Item("Heart rate", "72 bpm"),
    Item("Sleep", "7h 14m"),
    Item("Steps", "6,482"),
    Item("Calories", "412 kcal"),
    Item("Timer", "12:30 remaining"),
  )

@Composable
fun WearApp() {
  MaterialTheme {
    AppScaffold(
      // Real production app — let TimeText use the system clock.
      // Previews that want a deterministic time supply their own
      // `AppScaffold` with a `FixedPreviewTimeSource` (see [ActivityListPreview]).
      timeText = { TimeText() }
    ) {
      ActivityListScreen()
    }
  }
}

@Composable
fun ActivityListScreen() {
  val listState = rememberTransformingLazyColumnState()
  val transformationSpec = rememberTransformationSpec()

  ScreenScaffold(
    scrollState = listState,
    // Suppress the transient scroll indicator when the renderer flips
    // `LocalScrollCaptureInProgress = true` (e.g. for `@ScrollingPreview`).
    // In a running app the local is always `false`, so the default
    // indicator is drawn unchanged.
    scrollIndicator = {
      if (!LocalScrollCaptureInProgress.current) {
        ScrollIndicator(listState)
      }
    },
    edgeButton = {
      EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Large) {
        BasicText(
          text = "Start workout",
          maxLines = 1,
          autoSize = TextAutoSize.StepBased(),
          style =
            TextStyle(color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center),
        )
      }
    },
  ) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize(),
    ) {
      item {
        ListHeader(
          modifier =
            Modifier.minimumVerticalContentPadding(
                top = ListHeaderDefaults.minimumTopListContentPadding,
                bottom = 0.dp,
              )
              .transformedHeight(this, transformationSpec),
          transformation = SurfaceTransformation(transformationSpec),
        ) {
          Text("Today")
        }
      }
      items(sampleItems) { item ->
        TitleCard(
          onClick = {},
          title = { Text(item.title) },
          subtitle = { Text(item.subtitle) },
          modifier =
            Modifier.fillMaxWidth()
              .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
              .transformedHeight(this, transformationSpec),
          transformation = SurfaceTransformation(transformationSpec),
        )
      }
    }
  }
}

@Composable
private fun ButtonPreviewContent() {
  var taps by remember { mutableStateOf(0) }
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ScreenScaffold { contentPadding ->
        Box(
          modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
          contentAlignment = Alignment.Center,
        ) {
          Button(onClick = { taps += 1 }) { Text("Taps: $taps") }
        }
      }
    }
  }
}

@Composable
private fun CircularProgressPreviewContent() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ScreenScaffold { contentPadding ->
        Box(
          modifier =
            Modifier.fillMaxSize()
              .padding(contentPadding)
              .padding(CircularProgressIndicatorDefaults.FullScreenPadding),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.fillMaxSize())
        }
      }
    }
  }
}

@WearPreviewDevices
@Composable
fun ActivityListPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ActivityListScreen()
    }
  }
}

@WearPreviewFontScales
@Composable
fun ActivityListFontScalesPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ActivityListScreen()
    }
  }
}

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun ButtonPreview() {
  ButtonPreviewContent()
}

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun CircularProgressIndicatorPreview() {
  CircularProgressPreviewContent()
}

/**
 * Deliberately-broken Wear preview — a tiny unlabelled clickable Box tucked into the centre of the
 * round face. Exists so the a11y pipeline produces a Wear-sized annotated PNG; exercises the
 * stacked legend layout (screenshot on top, legend below) used for square/round displays.
 */
@WearPreviewSmallRound
@Composable
fun BadWearButtonPreview() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Button(onClick = { /* no-op */ }, modifier = Modifier.size(20.dp)) {}
  }
}

/**
 * Screen-level long-scroll fixture: same `ScreenScaffold` + `TransformingLazyColumn` + `EdgeButton`
 * layout as [ActivityListScreen], but with 15 items so the content overflows the viewport. The
 * `scrollIndicator` slot reads [LocalScrollCaptureInProgress] so the `@ScrollingPreview(modes =
 * [LONG])` capture doesn't pick up a fading indicator at random opacities. The screen does NOT
 * compose its own `MaterialTheme` / `AppScaffold` — its caller (the preview, or production) does,
 * which keeps the preview free to swap in a [FixedPreviewTimeSource]. `ScreenScaffold` reveals the
 * `EdgeButton` only when the list is pinned to the bottom, so "Start workout" appears once, at the
 * final slice.
 */
@Composable
fun LongActivityListScreen() {
  val longItems =
    List(15) { i ->
      when (i % 6) {
        0 -> Item("Morning run ${i + 1}", "5.2 km · 28 min")
        1 -> Item("Heart rate ${i + 1}", "${70 + i} bpm")
        2 -> Item("Sleep day ${i + 1}", "7h ${(i * 3) % 60}m")
        3 -> Item("Steps day ${i + 1}", "${6000 + i * 120}")
        4 -> Item("Calories day ${i + 1}", "${400 + i * 5} kcal")
        else -> Item("Timer ${i + 1}", "${10 + i}:${(i * 7) % 60} remaining")
      }
    }
  val listState = rememberTransformingLazyColumnState()
  val transformationSpec = rememberTransformationSpec()
  ScreenScaffold(
    scrollState = listState,
    scrollIndicator = {
      if (!LocalScrollCaptureInProgress.current) {
        ScrollIndicator(listState)
      }
    },
    edgeButton = {
      EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Large) {
        BasicText(
          text = "Start workout",
          maxLines = 1,
          autoSize = TextAutoSize.StepBased(),
          style =
            TextStyle(color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center),
        )
      }
    },
  ) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize(),
    ) {
      item {
        ListHeader(
          modifier =
            Modifier.minimumVerticalContentPadding(
                top = ListHeaderDefaults.minimumTopListContentPadding,
                bottom = 0.dp,
              )
              .transformedHeight(this, transformationSpec),
          transformation = SurfaceTransformation(transformationSpec),
        ) {
          Text("Activity")
        }
      }
      items(longItems) { item ->
        TitleCard(
          onClick = {},
          title = { Text(item.title) },
          subtitle = { Text(item.subtitle) },
          modifier =
            Modifier.fillMaxWidth()
              .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
              .transformedHeight(this, transformationSpec),
          transformation = SurfaceTransformation(transformationSpec),
        )
      }
    }
  }
}

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun ActivityListLongPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      LongActivityListScreen()
    }
  }
}

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.GIF])
@Composable
fun ActivityListGifPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      LongActivityListScreen()
    }
  }
}

/**
 * Regression fixture for the Confetti `HomeListViewLongPreview` shape: LONG and GIF from ONE
 * annotation, no `reduceMotion` configuration. Each medium gets its one sensible setting
 * automatically — the LONG stitch always flattens `TransformingLazyColumn` motion (mid-scale items
 * baked into slices produce ghost/duplicate card bands the stitcher cannot collapse), while the
 * GIF always keeps the morph animation its frames can genuinely express. Confetti used to force
 * `reduceMotion = false` to keep its GIF lively and shipped ghost-banded LONG stitches for months.
 * Guarded by `LongScrollPreviewPixelTest`.
 */
@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG, ScrollMode.GIF])
@Composable
fun ActivityListMotionLongPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      LongActivityListScreen()
    }
  }
}

// ---------------------------------------------------------------------------
// Regression fixture for issue #2299. A settings list of plain `Button`s (icon
// + label, `surfaceContainer` colours, `minimumVerticalContentPadding`) inside
// a `ScreenScaffold` with NO `edgeButton` slot — the last item ("About") is an
// ordinary list button. This is the exact shape from the report, kept so the
// long-scroll stitch is exercised on a tail that is a dark list item rather
// than a bright Wear `EdgeButton`: the stitcher's EdgeButton content-anchor
// path declines (brightness / purple-cast gate), so the generic final-frame
// overlay path composes the tail — where a stray slice fragment used to be
// left as a ghost streak below the last item.
// ---------------------------------------------------------------------------

private data class SettingsItem(val id: Int, val title: String, val icon: ImageVector? = null)

private val settingsList =
  listOf(
    SettingsItem(id = 1, title = "Notifications", icon = Icons.Default.Notifications),
    SettingsItem(id = 2, title = "Privacy", icon = Icons.Default.Lock),
    SettingsItem(id = 3, title = "Display", icon = Icons.Default.PlayArrow),
    SettingsItem(id = 4, title = "Sound & vibration", icon = Icons.Default.VolumeUp),
    SettingsItem(id = 5, title = "About", icon = Icons.Default.Info),
  )

@Composable
private fun SettingsMainScreen(
  items: List<SettingsItem>,
  onItemClick: (SettingsItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberTransformingLazyColumnState()
  val transformationSpec = rememberTransformationSpec()

  ScreenScaffold(
    scrollState = listState,
    scrollIndicator = {
      if (!LocalScrollCaptureInProgress.current) {
        ScrollIndicator(state = listState)
      }
    },
  ) { contentPadding ->
    TransformingLazyColumn(contentPadding = contentPadding, state = listState, modifier = modifier) {
      item {
        ListHeader(
          modifier =
            Modifier.fillMaxWidth()
              .transformedHeight(this, transformationSpec)
              .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
          transformation = SurfaceTransformation(transformationSpec),
        ) {
          Text(text = "Settings", style = MaterialTheme.typography.titleMedium)
        }
      }
      items(items = items, key = { it.id }) { item ->
        SettingsActionButton(
          item = item,
          onItemClick = { onItemClick(item) },
          transformationSpec = transformationSpec,
        )
      }
    }
  }
}

@Composable
private fun TransformingLazyColumnItemScope.SettingsActionButton(
  item: SettingsItem,
  transformationSpec: TransformationSpec,
  onItemClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onItemClick,
    label = { Text(text = item.title, style = MaterialTheme.typography.labelMedium) },
    icon = {
      item.icon?.let {
        Icon(
          painter = rememberVectorPainter(image = it),
          contentDescription = null,
          modifier = Modifier.size(ButtonDefaults.IconSize),
        )
      }
    },
    colors =
      ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
      ),
    modifier =
      modifier
        .fillMaxWidth()
        .transformedHeight(this, transformationSpec)
        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
    transformation = SurfaceTransformation(transformationSpec),
  )
}

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun SettingsMainScreenLongPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      SettingsMainScreen(items = settingsList, onItemClick = {})
    }
  }
}
