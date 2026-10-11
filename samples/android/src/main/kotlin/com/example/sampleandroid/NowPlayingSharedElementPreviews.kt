package com.example.sampleandroid

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalLookaheadAnimationVisualDebugApi
import androidx.compose.animation.LookaheadAnimationVisualDebugging
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview

// A container transform — a "now playing" mini-player growing into a full player — shown two ways:
// [NowPlayingContainerTransformPreview] as it ships, and [NowPlayingDebugOverlayPreview] wrapped in
// Compose 1.11's [LookaheadAnimationVisualDebugging] overlay. Both render the identical
// [NowPlayingSharedLayout], so the GIFs side by side show exactly what the overlay adds.
//
// The album art is the hero shared element (`key = "art"`), morphing from a 56dp square to the
// full-width cover via [artworkBrush]; the title and card surface use `sharedBounds`; the scrubber
// and transport controls fade in.
/**
 * Duration of the morph, shared by the bounds tween and the [AnimatedContent] transition: shared
 * elements only animate while that transition is active, so a shorter default fade would snap them.
 */
private const val MORPH_MS = 1200

private val playerBoundsSpec = BoundsTransform { _, _ -> tween(durationMillis = MORPH_MS) }

private val artworkBrush =
  Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFFB14DFF), Color(0xFFFF6BA6)))

private const val ACCENT = 0xFF6750A4
private const val CARD = 0xFFEDE7F6

private enum class PlayerScreen {
  MiniPlayer,
  FullPlayer,
}

/** Plain: the container transform as it ships, no debugging overlay. */
@Preview(name = "Now Playing", widthDp = 320, heightDp = 560, showBackground = true)
@AnimatedPreview(durationMs = 1300, frameIntervalMs = 55)
@Composable
fun NowPlayingContainerTransformPreview() {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      NowPlayingSharedLayout(Modifier.fillMaxSize().padding(16.dp))
    }
  }
}

/** The identical scene, wrapped in the 1.11 lookahead debug overlay. */
@Suppress("DisallowLookaheadAnimationVisualDebug") // This is an explicitly named debug preview.
@OptIn(ExperimentalLookaheadAnimationVisualDebugApi::class)
@Preview(name = "Now Playing (debug overlay)", widthDp = 320, heightDp = 560, showBackground = true)
@AnimatedPreview(durationMs = 1300, frameIntervalMs = 55)
@Composable
fun NowPlayingDebugOverlayPreview() {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      LookaheadAnimationVisualDebugging(isEnabled = true, isShowKeyLabelEnabled = true) {
        NowPlayingSharedLayout(Modifier.fillMaxSize().padding(16.dp))
      }
    }
  }
}

/**
 * The shared-element scene, shared so both previews render the same transition. Starts the
 * mini→full transition on the first frame.
 */
@Composable
private fun NowPlayingSharedLayout(modifier: Modifier = Modifier) {
  // Animate a SeekableTransitionState's fraction over MORPH_MS rather than flipping targetState:
  // under the paused clock a flip snaps shared bounds in one frame. Sweeping 0→1 is safe, unlike
  // seeking to fixed fractions (see SharedElementFilmstripPreview), since every frame is part of
  // the animation.
  val seekState = remember { SeekableTransitionState(PlayerScreen.MiniPlayer) }
  LaunchedEffect(Unit) {
    seekState.animateTo(PlayerScreen.FullPlayer, animationSpec = tween(MORPH_MS))
  }
  val transition = rememberTransition(seekState, label = "now-playing")
  SharedTransitionLayout(modifier = modifier) {
    transition.AnimatedContent { target ->
      when (target) {
        PlayerScreen.MiniPlayer -> MiniPlayer(this@SharedTransitionLayout, this@AnimatedContent)
        PlayerScreen.FullPlayer -> FullPlayer(this@SharedTransitionLayout, this@AnimatedContent)
      }
    }
  }
}

@Composable
private fun MiniPlayer(
  sharedScope: SharedTransitionScope,
  visibilityScope: AnimatedVisibilityScope,
) =
  with(sharedScope) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Row(
        modifier =
          Modifier.sharedBounds(
              rememberSharedContentState(key = "container"),
              animatedVisibilityScope = visibilityScope,
              boundsTransform = playerBoundsSpec,
            )
            .clip(RoundedCornerShape(20.dp))
            .background(Color(CARD))
            .fillMaxWidth()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier =
            Modifier.sharedElement(
                rememberSharedContentState(key = "art"),
                animatedVisibilityScope = visibilityScope,
                boundsTransform = playerBoundsSpec,
              )
              .size(56.dp)
              .clip(RoundedCornerShape(14.dp))
              .background(artworkBrush)
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            "Neon Meridian",
            modifier =
              Modifier.sharedBounds(
                rememberSharedContentState(key = "title"),
                animatedVisibilityScope = visibilityScope,
                boundsTransform = playerBoundsSpec,
              ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "The Glass Arcade",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.size(8.dp))
        PlayButton(diameter = 36.dp, glyph = 15.dp)
      }
    }
  }

@Composable
private fun FullPlayer(
  sharedScope: SharedTransitionScope,
  visibilityScope: AnimatedVisibilityScope,
) =
  with(sharedScope) {
    Column(
      modifier =
        Modifier.sharedBounds(
            rememberSharedContentState(key = "container"),
            animatedVisibilityScope = visibilityScope,
            enter = fadeIn(),
            exit = fadeOut(),
            boundsTransform = playerBoundsSpec,
          )
          .clip(RoundedCornerShape(28.dp))
          .background(Color(CARD))
          .fillMaxSize()
          .padding(22.dp)
    ) {
      Box(
        modifier =
          Modifier.sharedElement(
              rememberSharedContentState(key = "art"),
              animatedVisibilityScope = visibilityScope,
              boundsTransform = playerBoundsSpec,
            )
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(artworkBrush)
      )
      Spacer(Modifier.size(22.dp))
      Text(
        "Neon Meridian",
        modifier =
          Modifier.sharedBounds(
            rememberSharedContentState(key = "title"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = playerBoundsSpec,
          ),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "The Glass Arcade",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.size(20.dp))
      // Scrubber — exists only in the full player, fades in over the morphing container.
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Color(ACCENT).copy(alpha = 0.18f))
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth(0.38f).height(4.dp).clip(CircleShape).background(Color(ACCENT))
        )
      }
      Spacer(Modifier.size(8.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
          "1:24",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "3:58",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.weight(1f))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PlayButton(diameter = 68.dp, glyph = 28.dp)
      }
    }
  }

/** A circular play button with a hand-drawn triangle glyph (no icon-font dependency). */
@Composable
private fun PlayButton(diameter: Dp, glyph: Dp) {
  Box(
    modifier = Modifier.size(diameter).clip(CircleShape).background(Color(ACCENT)),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(glyph)) {
      val w = size.width
      val h = size.height
      val triangle =
        Path().apply {
          moveTo(w * 0.30f, h * 0.20f)
          lineTo(w * 0.30f, h * 0.80f)
          lineTo(w * 0.82f, h * 0.50f)
          close()
        }
      drawPath(triangle, Color.White)
    }
  }
}
