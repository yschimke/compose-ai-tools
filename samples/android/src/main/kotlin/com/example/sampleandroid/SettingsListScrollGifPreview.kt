package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

/**
 * A realistic scrolling-GIF demo: a 24-row settings-style list with a
 * leading colour-chip avatar + two-line text, and a right-aligned scroll
 * position indicator that tracks the visible window.
 *
 * This is the "what you'd actually screenshot in docs" fixture, sized
 * close to a small phone viewport (220×440dp ≈ 580×1155px at 2.625×). The
 * red-to-blue pixel-test fixture in [RedToBlueScrollGifPreview] stays
 * minimal; this one is the visual showcase for PRs / READMEs.
 */
@Preview(name = "SettingsListScrollGif", showBackground = true, widthDp = 220, heightDp = 440)
@ScrollingPreview(modes = [ScrollMode.GIF])
@Composable
fun SettingsListScrollGifPreview() {
    val state = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(SETTINGS_ROWS) { row ->
                SettingsRow(row = row)
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        ScrollPositionIndicator(
            state = state,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 8.dp, horizontal = 3.dp)
                .width(3.dp)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun SettingsRow(row: SettingsRowData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(row.tint, CircleShape),
        )
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A thin scrollbar-style indicator that shows the visible window as a
 * rounded thumb on a faint track. Derives the thumb's top / height
 * fractions directly from [LazyListState.layoutInfo] — wrapping in
 * [derivedStateOf] so recomposition only triggers when the thumb actually
 * moves, not on every scroll frame.
 *
 * Rolled by hand rather than pulled from a library because (a) Compose
 * Material3 doesn't ship a `Scrollbar` today and (b) a few composables'
 * worth of code avoids pulling `accompanist` / `scrollbar` deps into the
 * sample module.
 */
@Composable
private fun ScrollPositionIndicator(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val thumb by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty() || info.totalItemsCount == 0) {
                0f to 0f
            } else {
                val total = info.totalItemsCount.toFloat()
                val first = visible.first().index.toFloat()
                val last = visible.last().index.toFloat()
                (first / total) to ((last + 1f) / total)
            }
        }
    }
    val (start, end) = thumb
    Box(modifier = modifier) {
        // Track — faint, always visible so the indicator has a stable
        // geometry reference even on a preview where nothing is being
        // dragged.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape(2.dp)),
        )
        // Thumb — positioned via BoxWithConstraints so the dp math uses
        // the parent's actual measured height. `offset(y = ...)` and
        // `height(...)` both accept Dp, so we do the Dp arithmetic
        // inside the constraints scope.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            Box(
                Modifier
                    .offset(y = h * start)
                    .fillMaxWidth()
                    .height(h * (end - start))
                    .background(
                        Color(0xFF6750A4).copy(alpha = 0.65f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

private data class SettingsRowData(
    val title: String,
    val subtitle: String,
    val tint: Color,
)

/**
 * 24 rows, enough that a 220×440dp viewport shows ~7 at a time and the
 * scroll spans ~3 viewports — comfortably inside
 * `driveScrollByViewport`'s default 30-iteration budget even at GIF's
 * 20%-per-step cadence, so the last frame lands at the real end of the
 * list rather than getting clipped.
 *
 * Tint palette walks through the hue wheel so consecutive rows are
 * visually distinct and the scroll animation reads as actual motion (if
 * every row looked the same, the scroll would feel static).
 */
private val SETTINGS_ROWS: List<SettingsRowData> = listOf(
    SettingsRowData("Wi-Fi", "HomeNet · 5 GHz", Color(0xFF1E88E5)),
    SettingsRowData("Bluetooth", "Off", Color(0xFF3949AB)),
    SettingsRowData("Mobile network", "Vodafone · 4G", Color(0xFF8E24AA)),
    SettingsRowData("Hotspot", "Not sharing", Color(0xFFD81B60)),
    SettingsRowData("Notifications", "3 apps silenced", Color(0xFFE53935)),
    SettingsRowData("Sound", "Ring 80% · Media 60%", Color(0xFFFB8C00)),
    SettingsRowData("Display", "Adaptive brightness", Color(0xFFFDD835)),
    SettingsRowData("Wallpaper", "Mountain at dusk", Color(0xFFC0CA33)),
    SettingsRowData("Accessibility", "Large text · high contrast", Color(0xFF7CB342)),
    SettingsRowData("Storage", "127 GB free of 256 GB", Color(0xFF43A047)),
    SettingsRowData("Battery", "74% · 8h 12m left", Color(0xFF00897B)),
    SettingsRowData("Privacy", "Location off", Color(0xFF00ACC1)),
    SettingsRowData("Security", "Fingerprint · Face", Color(0xFF039BE5)),
    SettingsRowData("Google services", "Play Store · Drive", Color(0xFF5E35B1)),
    SettingsRowData("Accounts", "yuri@example.com", Color(0xFFAB47BC)),
    SettingsRowData("Backup", "Last: yesterday", Color(0xFFEC407A)),
    SettingsRowData("System updates", "Up to date", Color(0xFFEF5350)),
    SettingsRowData("Apps", "128 installed", Color(0xFFFF7043)),
    SettingsRowData("Digital wellbeing", "4h 12m screen time", Color(0xFFFFCA28)),
    SettingsRowData("Date & time", "Automatic", Color(0xFF9CCC65)),
    SettingsRowData("Language", "English (UK)", Color(0xFF26A69A)),
    SettingsRowData("Keyboard", "Gboard", Color(0xFF29B6F6)),
    SettingsRowData("Developer options", "USB debugging on", Color(0xFF7E57C2)),
    SettingsRowData("About phone", "Pixel 9 Pro · build TQ3A", Color(0xFFBDBDBD)),
)
