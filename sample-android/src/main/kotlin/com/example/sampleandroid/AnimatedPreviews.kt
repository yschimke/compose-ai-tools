package com.example.sampleandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Renders a 600ms tween fade-in of a 96dp box. The `showCurves = true`
 * sidecar ought to plot a single 0.0 → 1.0 alpha curve over the
 * annotation's 1500ms window, with the value flat at 1.0 after the tween
 * completes at 600ms.
 */
@Preview(widthDp = 200, heightDp = 200, showBackground = true)
@AnimatedPreview(durationMs = 1500, frameIntervalMs = 33, showCurves = true)
@Composable
fun FadeInBoxAnimatedPreview() {
    // `MutableTransitionState(false)` + `targetState = true` set during
    // composition is the canonical "kick off a transition on first
    // frame" pattern. `updateTransition` registers the transition with
    // the slot table so AnimationSearch picks it up cleanly.
    val state = remember { MutableTransitionState(false) }
    state.targetState = true
    val transition = updateTransition(state, label = "fade-in")
    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 600, easing = LinearOutSlowInEasing) },
        label = "alpha",
    ) { if (it) 1f else 0f }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * `AnimatedVisibility` reveal — exercises the discovery side of the
 * inspector: `AnimatedVisibility` registers via a parent `Transition` and
 * the inspector should pick it up alongside any nested animateXAsState.
 */
@Preview(widthDp = 240, heightDp = 240, showBackground = true)
@AnimatedPreview(durationMs = 1200, frameIntervalMs = 40)
@Composable
fun RevealLabelAnimatedPreview() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = visible) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Color(0xFF6750A4)),
                contentAlignment = Alignment.Center,
            ) {
                Text("hi", color = Color.White)
            }
        }
    }
}
