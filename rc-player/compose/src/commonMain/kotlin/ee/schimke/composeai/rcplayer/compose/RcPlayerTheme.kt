package ee.schimke.composeai.rcplayer.compose

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rcplayer.protocol.RcTheme

/**
 * Which of a document's recorded light/dark pairs a host wants rendered.
 *
 * **Not [RcTheme].** That name is taken, and must stay taken: `RcTheme` is a document *operation*
 * the decoder produces, carrying the wire constants a `.rc` file records. This is the host-facing
 * concept — what an embedding application asks the player for — and it lives in the compose module
 * because that is where a host meets the player. A consumer picking between the two wants this one;
 * `RcTheme` is what comes *out* of a document, not what goes *in* to the player.
 *
 * **Three cases, not four.** The wire has both `SYSTEM` and `UNSPECIFIED`, but they are identical
 * at the host boundary: [rcResolveSystemTheme] answers both from `isSystemInDarkTheme()`. Exposing
 * both would ask callers to choose between two spellings of one behaviour, so [System] covers them
 * and maps to the wire's `SYSTEM`.
 */
public enum class RcPlayerTheme {
  /** Render the light half of every recorded pair, whatever the platform is set to. */
  Light,

  /** Render the dark half of every recorded pair, whatever the platform is set to. */
  Dark,

  /** Follow the platform, via `isSystemInDarkTheme()`. The default. */
  System;

  /**
   * The wire constant this asks for. Internal because the wire values are [RcTheme]'s business:
   * everything below the player's entry point still speaks them, and nothing outside the module
   * should need to.
   */
  internal val wireValue: Int
    get() =
      when (this) {
        Light -> RcTheme.LIGHT
        Dark -> RcTheme.DARK
        System -> RcTheme.SYSTEM
      }
}

/** Resolve to the concrete mode the document's `ColorTheme` operations select with. */
@Composable internal fun RcPlayerTheme.resolve(): Int = rcResolveSystemTheme(wireValue)
