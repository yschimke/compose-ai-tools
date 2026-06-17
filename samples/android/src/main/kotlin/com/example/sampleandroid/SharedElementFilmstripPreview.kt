package com.example.sampleandroid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A **deterministic filmstrip** of a shared-element container transform — one static, diffable PNG
 * that lays the same transition out at five fixed progress fractions (0% → 100%) stacked top to
 * bottom.
 *
 * Where [ContainerTransformAnimatedPreview] uses the `@AnimatedPreview` paused-clock GIF (great for
 * *watching* the motion), this preview is the static counterpart: each panel freezes the transition
 * at an exact fraction with [SeekableTransitionState.seekTo], so the in-between bounds interpolation
 * is legible in a single image and a visual-diff bot can compare it pixel-for-pixel without the
 * frame-timing jitter a GIF carries. `seekTo(fraction, target)` is the stable primitive for "render
 * any intermediate frame" — the same one Android Studio's scrubber is built on — driven here to a
 * literal constant per panel rather than swept by a clock.
 *
 * Each panel builds its own `SeekableTransitionState`, seeks it once on first composition, and feeds
 * the resulting `Transition` into `Transition.AnimatedContent` so the shared modifiers get the
 * `AnimatedVisibilityScope` they need.
 */
private val filmstripBoundsSpec = BoundsTransform { _, _ -> tween(durationMillis = 600) }

private enum class FilmScreen {
    Collapsed,
    Expanded,
}

private val FILMSTRIP_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

@Preview(name = "Shared Element Filmstrip", widthDp = 340, heightDp = 820, showBackground = true)
@Composable
fun SharedElementFilmstripPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FILMSTRIP_FRACTIONS.forEach { fraction -> FilmstripPanel(fraction) }
            }
        }
    }
}

@Composable
private fun FilmstripPanel(fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        val seekState = remember { SeekableTransitionState(FilmScreen.Collapsed) }
        // One-shot seek to a constant fraction; the panel renders the transition frozen there.
        LaunchedEffect(Unit) { seekState.seekTo(fraction = fraction, targetState = FilmScreen.Expanded) }
        val transition = rememberTransition(seekState, label = "filmstrip-$fraction")
        Box(modifier = Modifier.fillMaxWidth().height(132.dp)) {
            SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                transition.AnimatedContent { target ->
                    when (target) {
                        FilmScreen.Collapsed ->
                            FilmCollapsed(this@SharedTransitionLayout, this@AnimatedContent)
                        FilmScreen.Expanded ->
                            FilmExpanded(this@SharedTransitionLayout, this@AnimatedContent)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmCollapsed(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) =
    with(sharedScope) {
        Row(
            modifier =
                Modifier.sharedBounds(
                        rememberSharedContentState(key = "container"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = filmstripBoundsSpec,
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFE8DEF8))
                    .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.sharedElement(
                            rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = filmstripBoundsSpec,
                        )
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4))
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "Aurora ridge",
                modifier =
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "title"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = filmstripBoundsSpec,
                    ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

@Composable
private fun FilmExpanded(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) =
    with(sharedScope) {
        Row(
            modifier =
                Modifier.sharedBounds(
                        rememberSharedContentState(key = "container"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = filmstripBoundsSpec,
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFFE8DEF8))
                    .fillMaxSize()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.sharedElement(
                            rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = filmstripBoundsSpec,
                        )
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4))
            )
            Spacer(Modifier.size(14.dp))
            Text(
                "Aurora ridge",
                modifier =
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "title"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = filmstripBoundsSpec,
                    ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
