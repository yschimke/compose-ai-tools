@file:Suppress("DisallowLookaheadAnimationVisualDebug") // This file contains only debug previews.

package com.example.sampleandroid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.CustomizedLookaheadAnimationVisualDebugging
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
 * Compose 1.11's shared-element visual debugging (`LookaheadAnimationVisualDebugging`) captured as
 * GIFs. During a transition the overlay paints target bounds ([overlayColor]), unmatched keys in
 * [unmatchedColor] (red), multiply-matched keys in [multipleMatchesColor] (green), and optional key
 * labels. Studio's Animation Preview can't inspect shared elements, so a paused-clock GIF is how
 * reviewers see it. Compare with [ContainerTransformAnimatedPreview].
 *
 * Needs `@ExperimentalLookaheadAnimationVisualDebugApi`.
 */
private val debugBoundsSpec = BoundsTransform { _, _ -> tween(durationMillis = 600) }
private val debugElementColor = Color(0xFF4285F4)

private enum class DebugScreen {
  Collapsed,
  Expanded,
}

/**
 * A well-formed container transform: every key (`avatar`, `title`, `container`) matches, so the
 * overlay draws only bounds and labels — the "correct" baseline.
 */
@OptIn(ExperimentalLookaheadAnimationVisualDebugApi::class)
@Preview(
  name = "Shared Element Debug — Matched",
  widthDp = 300,
  heightDp = 520,
  showBackground = true,
)
@AnimatedPreview(durationMs = 750)
@Composable
fun SharedElementDebugMatchedAnimatedPreview() {
  var screen by remember { mutableStateOf(DebugScreen.Collapsed) }
  LaunchedEffect(Unit) { screen = DebugScreen.Expanded }
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      LookaheadAnimationVisualDebugging(isEnabled = true, isShowKeyLabelEnabled = true) {
        // AndroidX's default debug-color allocator is process-global. An explicit color keeps this
        // fixture independent of which other previews rendered earlier in the sandbox.
        CustomizedLookaheadAnimationVisualDebugging(debugElementColor) {
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
}

/**
 * Broken on purpose: the collapsed state has an extra `badge` key the expanded state never
 * registers, so the overlay flags it red as unmatched.
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
        CustomizedLookaheadAnimationVisualDebugging(debugElementColor) {
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
