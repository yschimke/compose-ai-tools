package com.example.sampleandroid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Shared-element transition samples, captured as GIFs through the `@AnimatedPreview` paused-clock
 * pipeline.
 *
 * Compose's shared transition APIs (`SharedTransitionLayout`, `Modifier.sharedBounds` /
 * `Modifier.sharedElement`, `rememberSharedContentState`) became **stable in 1.10** (Dec 2025) and
 * are what these previews exercise. Because Android Studio's Animation Preview does **not** inspect
 * shared-element transitions, the headless GIF capture here is the only way to *see* a container
 * transform stepped frame-by-frame outside a running device — it's the shared-element analogue of
 * the existing `FadeInBoxAnimatedPreview`.
 *
 * Each preview kicks the transition off on the first frame: the `AnimatedContent` target flips from
 * the "collapsed" to the "expanded" state inside a `LaunchedEffect`, so the inspector sees a
 * transition in flight across the whole captured window. A fixed `tween` [boundsSpec] keeps the
 * bounds morph deterministic (the default `sharedBounds` spec is a spring, whose settle time drifts
 * between Compose versions and makes diffs noisy).
 */
private val boundsSpec = BoundsTransform { _, _ -> tween(durationMillis = 600) }

private enum class CardScreen {
    Collapsed,
    Expanded,
}

/**
 * The canonical **container transform**: a compact list row morphs into a full detail pane. The
 * avatar (`sharedElement`), the title (`sharedBounds`), and the card surface (`sharedBounds`) all
 * carry continuous identity, so the row visually grows into the detail screen rather than
 * cross-fading.
 */
@Preview(name = "Container Transform", widthDp = 300, heightDp = 520, showBackground = true)
@AnimatedPreview(durationMs = 750)
@Composable
fun ContainerTransformAnimatedPreview() {
    var screen by remember { mutableStateOf(CardScreen.Collapsed) }
    LaunchedEffect(Unit) { screen = CardScreen.Expanded }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            SharedTransitionLayout(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                AnimatedContent(
                    targetState = screen,
                    label = "container-transform",
                    modifier = Modifier.fillMaxSize(),
                ) { target ->
                    when (target) {
                        CardScreen.Collapsed ->
                            CollapsedCard(this@SharedTransitionLayout, this@AnimatedContent)
                        CardScreen.Expanded ->
                            ExpandedCard(this@SharedTransitionLayout, this@AnimatedContent)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedCard(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) =
    with(sharedScope) {
        Row(
            modifier =
                Modifier.sharedBounds(
                        rememberSharedContentState(key = "container"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = boundsSpec,
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE8DEF8))
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.sharedElement(
                            rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = boundsSpec,
                        )
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4))
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "Aurora ridge",
                modifier =
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "title"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = boundsSpec,
                    ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

@Composable
private fun ExpandedCard(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) =
    with(sharedScope) {
        Column(
            modifier =
                Modifier.sharedBounds(
                        rememberSharedContentState(key = "container"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = boundsSpec,
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFE8DEF8))
                    .fillMaxSize()
                    .padding(20.dp)
        ) {
            Box(
                modifier =
                    Modifier.sharedElement(
                            rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = visibilityScope,
                            boundsTransform = boundsSpec,
                        )
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4))
            )
            Spacer(Modifier.size(16.dp))
            Text(
                "Aurora ridge",
                modifier =
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "title"),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = boundsSpec,
                    ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "A long ridgeline walk above the cloud line, finishing at a glacial tarn. " +
                    "Body copy that only exists in the detail state fades in over the morphing " +
                    "container.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

private enum class FabScreen {
    Fab,
    Sheet,
}

/**
 * **FAB → sheet** container transform: a floating action button morphs into a bottom sheet. Shows
 * `sharedBounds` with an explicit enter/exit ([fadeIn] / [fadeOut]) so the FAB's icon cross-fades
 * out while the sheet's content fades in over the shared, resizing container — the resize behaviour
 * Material's `ResizeMode.scaleToBounds()` would otherwise scale, here remeasured so the text stays
 * crisp.
 */
@Preview(name = "FAB To Sheet", widthDp = 320, heightDp = 360, showBackground = true)
@AnimatedPreview(durationMs = 750)
@Composable
fun FabToSheetAnimatedPreview() {
    var screen by remember { mutableStateOf(FabScreen.Fab) }
    LaunchedEffect(Unit) { screen = FabScreen.Sheet }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = screen,
                    label = "fab-to-sheet",
                    modifier = Modifier.fillMaxSize(),
                ) { target ->
                    when (target) {
                        FabScreen.Fab -> FabState(this@SharedTransitionLayout, this@AnimatedContent)
                        FabScreen.Sheet ->
                            SheetState(this@SharedTransitionLayout, this@AnimatedContent)
                    }
                }
            }
        }
    }
}

@Composable
private fun FabState(sharedScope: SharedTransitionScope, visibilityScope: AnimatedVisibilityScope) =
    with(sharedScope) {
        Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier =
                    Modifier.sharedBounds(
                            rememberSharedContentState(key = "fab-container"),
                            animatedVisibilityScope = visibilityScope,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            boundsTransform = boundsSpec,
                        )
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF6750A4)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = Color.White, fontSize = 28.sp)
            }
        }
    }

@Composable
private fun SheetState(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) =
    with(sharedScope) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier =
                    Modifier.sharedBounds(
                            rememberSharedContentState(key = "fab-container"),
                            animatedVisibilityScope = visibilityScope,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            boundsTransform = boundsSpec,
                        )
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(Color(0xFF6750A4))
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("New reminder", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "The FAB expands into the sheet surface — the same node, resized — while the " +
                        "form content fades in on top.",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
