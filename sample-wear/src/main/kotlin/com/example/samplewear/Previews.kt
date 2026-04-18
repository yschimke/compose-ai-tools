package com.example.samplewear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import androidx.wear.compose.ui.tooling.preview.WearPreviewSmallRound

private object FixedTimeSource : TimeSource {
    @Composable
    override fun currentTime(): String = "10:10"
}

private data class Item(val title: String, val subtitle: String)

private val sampleItems = listOf(
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
            timeText = { TimeText(timeSource = FixedTimeSource) },
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
        edgeButton = {
            EdgeButton(
                onClick = {},
                buttonSize = EdgeButtonSize.Large,
            ) {
                BasicText(
                    text = "Start workout",
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(),
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    ),
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
                    modifier = Modifier
                        .minimumVerticalContentPadding(
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
                    modifier = Modifier
                        .fillMaxWidth()
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
    MaterialTheme {
        AppScaffold(
            timeText = { TimeText(timeSource = FixedTimeSource) },
        ) {
            ScreenScaffold { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(onClick = {}) {
                        Text("Tap me!")
                    }
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
fun ActivityListPreview() {
    WearApp()
}

@WearPreviewFontScales
@Composable
fun ActivityListFontScalesPreview() {
    WearApp()
}

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun ButtonPreview() {
    ButtonPreviewContent()
}

/**
 * Deliberately-broken Wear preview — a tiny unlabelled clickable Box
 * tucked into the centre of the round face. Exists so the a11y pipeline
 * produces a Wear-sized annotated PNG; exercises the stacked legend layout
 * (screenshot on top, legend below) used for square/round displays.
 */
@WearPreviewSmallRound
@Composable
fun BadWearButtonPreview() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(
            onClick = { /* no-op */ },
            modifier = Modifier.size(20.dp),
        ) {}
    }
}
