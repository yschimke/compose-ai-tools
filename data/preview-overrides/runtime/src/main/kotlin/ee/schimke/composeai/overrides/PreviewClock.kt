package ee.schimke.composeai.overrides

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * A wall clock a `@Preview` reads so a fake-clock override can pin it (issue #1968).
 *
 * Compose has no built-in composition-local for time-of-day, so consumer UI that shows
 * time-dependent content — relative timestamps ("2m ago"), countdowns ("expires in…") — reads
 * [LocalClock]`.current.nowEpochMillis()` instead of `System.currentTimeMillis()`. Adopting it is
 * behaviour-preserving: in a normal render (and in production) [LocalClock] defaults to
 * [SystemPreviewClock] (real system time). Under the daemon's `clockEpochMillis` override the
 * connector provides a fixed clock around the rendered preview, so the frame is deterministic — the
 * same opt-in model as `previewOverride*` and `PreviewSlot`.
 */
fun interface PreviewClock {
  /** Milliseconds since the Unix epoch — what the preview should treat as "now". */
  fun nowEpochMillis(): Long
}

/** The real wall clock: the default [LocalClock] value when no fake-clock override is active. */
val SystemPreviewClock: PreviewClock = PreviewClock { System.currentTimeMillis() }

/** A [PreviewClock] frozen at [epochMillis] — what the fake-clock override installs. */
fun fixedPreviewClock(epochMillis: Long): PreviewClock = PreviewClock { epochMillis }

/**
 * The clock preview content reads for wall-clock time. Defaults to [SystemPreviewClock]; the
 * daemon's `clockEpochMillis` render override provides a [fixedPreviewClock] around the rendered
 * preview (issue #1968), mirroring how the `slotMode` override provides `LocalSlotMode`. Backends
 * that don't provide it (or a plain render) leave it at real system time.
 */
val LocalClock: ProvidableCompositionLocal<PreviewClock> = compositionLocalOf { SystemPreviewClock }
