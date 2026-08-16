package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rcplayer.protocol.RcTheme

/**
 * Turn a requested theme into the concrete mode a `ColorTheme` selects with.
 *
 * [RcTheme.LIGHT] and [RcTheme.DARK] pass through — the caller has said which it wants.
 * [RcTheme.SYSTEM] and [RcTheme.UNSPECIFIED] are both *questions*: one asks for the host's setting,
 * the other says nothing at all. Neither is a mode, and both are answered here from
 * [isSystemInDarkTheme].
 *
 * **Why they cannot be left alone.** Every consumer of a resolved theme branches the same way a
 * `ColorTheme` operation does: light when the value is `LIGHT`, dark otherwise. That is a *branch*,
 * not a default — pass `UNSPECIFIED` through it and the document renders dark, silently, without
 * anything having decided that. A player whose caller did not name a mode should follow the
 * platform, which is what a user changing their system theme expects and what the light/dark pair
 * in the document is recorded for.
 *
 * `isSystemInDarkTheme` rather than an Android `Configuration` read: it is the Compose-level answer
 * and it exists on every target this player runs on, so desktop, iOS, wasm and Android all resolve
 * `SYSTEM` the same way instead of the JVM lanes quietly falling to one side.
 */
@Composable
public fun rcResolveSystemTheme(theme: Int): Int =
  when (theme) {
    RcTheme.SYSTEM,
    RcTheme.UNSPECIFIED -> if (isSystemInDarkTheme()) RcTheme.DARK else RcTheme.LIGHT
    else -> theme
  }
