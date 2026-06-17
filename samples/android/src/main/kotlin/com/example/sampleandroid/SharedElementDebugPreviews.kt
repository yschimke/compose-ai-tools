package com.example.sampleandroid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalLookaheadAnimationVisualDebugApi
import androidx.compose.animation.LookaheadAnimationVisualDebugging
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Compose **1.11's shared-element visual debugging** (`LookaheadAnimationVisualDebugging`) captured
 * as GIFs.
 *
 * 1.11 added a runtime debug composable you wrap *around* a `SharedTransitionLayout`. While a shared
 * transition is in flight it paints, into the shared overlay:
 * - the **target bounds** each element is animating toward (semi-transparent fill, [overlayColor]),
 * - **unmatched** elements — a shared key with no counterpart in the other state — in
 *   [unmatchedColor] (red), the single most common shared-element bug,
 * - **multiply-matched** keys — the same key registered more than once — in [multipleMatchesColor]
 *   (green), the other common bug,
 * - optional **key labels** so you can read which `rememberSharedContentState(key = …)` each box
 *   belongs to.
 *
 * Android Studio's Animation Preview can't inspect shared elements, and the overlay only exists
 * *during* a transition, so a paused-clock GIF is the natural way to put it in front of a reviewer
 * or an agent. These previews are the rendered, diffable proof that the overlay behaves — pair them
 * with the un-instrumented transitions in [ContainerTransformAnimatedPreview].
 *
 * Requires the `@ExperimentalLookaheadAnimationVisualDebugApi` opt-in (the debug API is still
 * experimental in 1.11 even though the shared-element APIs themselves are stable).
 */
private val debugBoundsSpec = BoundsTransform { _, _ -> tween(durationMillis = 600) }

private enum class DebugScreen {
    Collapsed,
    Expanded,
}

/**
 * A **well-formed** container transform under the debug overlay: every shared key (`avatar`,
 * `title`, `container`) has a matched counterpart, so the overlay only draws target-bounds rectangles
 * and key labels — no red, no green. This is the "what correct looks like" baseline.
 */
@OptIn(ExperimentalLookaheadAnimationVisualDebugApi::class)
@Preview(name = "Shared Element Debug — Matched", widthDp = 300, heightDp = 520, showBackground = true)
@AnimatedPreview(durationMs = 750)
@Composable
fun SharedElementDebugMatchedAnimatedPreview() {
    var screen by remember { mutableStateOf(DebugScreen.Collapsed) }
    LaunchedEffect(Unit) { screen = DebugScreen.Expanded }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LookaheadAnimationVisualDebugging(isEnabled = true, isShowKeyLabelEnabled = true) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    AnimatedContent(
                        targetState = screen,
                        label = "debug-matched",
                        modifier = Modifier.fillMaxSize(),
                    ) { target ->
                        when (target) {
                            DebugScreen.Collapsed ->
                                DebugCollapsed(
                                    this@SharedTransitionLayout,
                                    this@AnimatedContent,
                                    includeBadge = false,
                                )
                            DebugScreen.Expanded ->
                                DebugExpanded(this@SharedTransitionLayout, this@AnimatedContent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The **broken** version: the collapsed state declares an extra `badge` shared element that the
 * expanded state never registers. During the transition the overlay flags `badge` as **unmatched**
 * in red — exactly the diagnostic you'd reach for when a shared element silently fails to animate
 * because a key is missing on one side.
 */
@OptIn(ExperimentalLookaheadAnimationVisualDebugApi::class)
@Preview(
    name = "Shared Element Debug — Unmatched",
    widthDp = 300,
    heightDp = 520,
    showBackground = true,
)
@AnimatedPreview(durationMs = 750)
@Composable
fun SharedElementDebugUnmatchedAnimatedPreview() {
    var screen by remember { mutableStateOf(DebugScreen.Collapsed) }
    LaunchedEffect(Unit) { screen = DebugScreen.Expanded }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LookaheadAnimationVisualDebugging(
                isEnabled = true,
                unmatchedElementColor = Color(0xCCD32F2F),
                isShowKeyLabelEnabled = true,
            ) {
                SharedTransitionLayout(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    AnimatedContent(
                        targetState = screen,
                        label = "debug-unmatched",
                        modifier = Modifier.fillMaxSize(),
                    ) { target ->
                        when (target) {
                            DebugScreen.Collapsed ->
                                DebugCollapsed(
                                    this@SharedTransitionLayout,
                                    this@AnimatedContent,
                                    includeBadge = true,
                                )
                            DebugScreen.Expanded ->
                                DebugExpanded(this@SharedTransitionLayout, this@AnimatedContent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugCollapsed(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    includeBadge: Boolean,
) =
    with(sharedScope) {
        Row(
            modifier =
                Modifier.sharedBounds(
                        rememberSharedContentState(key = "container"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = debugBoundsSpec,
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFD7E3FF))
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.sharedElement(
                            rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = debugBoundsSpec,
                        )
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF345CA8))
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "Glacier trail",
                modifier =
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "title"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = debugBoundsSpec,
                    ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (includeBadge) {
                Spacer(Modifier.size(8.dp))
                // This `badge` key exists only in the collapsed state — the overlay flags it as an
                // unmatched (red) shared element while the transition runs.
                Box(
                    modifier =
                        Modifier.sharedElement(
                                rememberSharedContentState(key = "badge"),
                                animatedVisibilityScope = visibilityScope,
                                boundsTransform = debugBoundsSpec,
                            )
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF6C00))
                )
            }
        }
    }

@Composable
private fun DebugExpanded(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) =
    with(sharedScope) {
        Column(
            modifier =
                Modifier.sharedBounds(
                        rememberSharedContentState(key = "container"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = debugBoundsSpec,
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFD7E3FF))
                    .fillMaxSize()
                    .padding(20.dp)
        ) {
            Box(
                modifier =
                    Modifier.sharedElement(
                            rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = debugBoundsSpec,
                        )
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF345CA8))
            )
            Spacer(Modifier.size(16.dp))
            Text(
                "Glacier trail",
                modifier =
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "title"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = debugBoundsSpec,
                    ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
