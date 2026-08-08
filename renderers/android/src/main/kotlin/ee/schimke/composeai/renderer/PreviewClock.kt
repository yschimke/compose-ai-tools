package ee.schimke.composeai.renderer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The wall clock a preview render sees — pinned to a fixed instant so a preview that shows the time
 * produces the same PNG on every run (issue #3239).
 *
 * ## The bug this fixes
 *
 * Robolectric's `SystemClock` is deterministic under `LooperMode.PAUSED` — it starts at 100ms since
 * boot and only moves when something advances it. `System.currentTimeMillis()` is NOT: Robolectric
 * rewrites that call into [org.robolectric.shadows.ShadowSystem.currentTimeMillis] only inside
 * *instrumented* classes, and the instrumented set is `android.`, `com.android.internal.`, a
 * handful of XML parsers — plus whatever `instrumentedPackages` names. Everything else (the
 * consumer's own code, and every library) reads the host's real wall clock. So any surface that
 * paints the time re-renders differently every minute, with no source change, and the visual-diff
 * bot reports it as a real diff on every PR.
 *
 * That hits [AppTourRenderer]'s `kind=ACTIVITY` / `kind=APP_TOUR` lane hardest, because there the
 * *app's own* top-level screen is the subject and there is no seam to inject a fixed clock through
 * — an authored `@Preview` can pass `TimeText(timeSource = FixedPreviewTimeSource)` (see
 * `:samples:wear`), an activity hero cannot. On Wear that is close to every activity preview:
 * `TimeText` is standard furniture inside `AppScaffold`.
 *
 * ## How it is fixed
 *
 * [ShadowWearTimeSource] replaces the one function Wear reads the clock through and returns
 * [currentTimeMillis]. Two halves, and **both are load-bearing** — exactly like the coil pair in
 * [ShadowAsyncImagePainter]: the shadow has to be registered (generated `robolectric.properties`
 * for the Gradle path, `SandboxHoldingRunner` for the daemon), and its target class has to be
 * instrumented, because Robolectric cannot shadow a class it did not rewrite.
 *
 * ### Why not move `SystemClock` instead
 *
 * `SystemClock.setCurrentTimeMillis(fixed)` would make every *instrumented* `currentTimeMillis()`
 * read the pinned value in one stroke, which looks tidier. It is worse in four concrete ways, and
 * they're the reason this is a shadow:
 * - Robolectric's paused clock **only moves forward**, so a pre-1970 or otherwise-earlier instant
 *   is silently ignored rather than applied.
 * - Setting it is a ~54-year jump from 100ms-past-the-epoch, which makes every already-scheduled
 *   delayed message overdue. Anything a consumer `Application.onCreate` posted (and with
 *   `useConsumerApplication = true` that runs before the test method) fires at the next idle.
 * - It leaves `SystemClock.uptimeMillis()` reporting 54 years of uptime.
 * - It has to happen *before* anything else in every render entry point, which is an ordering
 *   contract that quietly breaks the moment a lane early-returns (the daemon's scroll and
 *   `figma-svg-long` modes do exactly that).
 *
 * A shadow has none of those: it is read at call time, it holds no state, and it cannot be reached
 * out of order.
 *
 * ## What it cannot reach
 *
 * `java.` is on Robolectric's do-not-acquire list, so `Calendar.getInstance()`, `new Date()` and
 * `java.time.*.now()` read the host clock from inside the JDK where no rewrite is possible. A
 * preview that formats `LocalTime.now()` itself still drifts; the fix for that one is the authoring
 * seam (hoist the clock, or branch on `LocalInspectionMode`), not the renderer.
 *
 * The Desktop / CMP lane has no Robolectric and therefore no interception point at all, so this is
 * an Android-only guarantee — see [ee.schimke.composeai.plugin.PreviewExtension.fixedTime].
 *
 * ## Configuring it
 *
 * `-Dcomposeai.render.fixedTime=…` on the render JVM, forwarded from `-PcomposePreview.fixedTime=…`
 * or the `composePreview.fixedTime` DSL value by the Gradle plugin. Accepts:
 * - `HH:mm` / `HH:mm:ss` — that time on [FIXED_DATE], in the render JVM's default zone. The default
 *   is [DEFAULT_TIME] (`10:10`), the same literal `:samples:wear`'s `FixedPreviewTimeSource` and the
 *   Wear/Remote design catalogs already paint.
 * - an ISO-8601 local date-time (`2024-01-01T10:10`) — when the date matters too.
 * - a bare epoch-millis number.
 * - `off` (also `false` / `none`) — don't pin; [currentTimeMillis] hands back the host wall clock
 *   and renders drift exactly as they did before this existed.
 */
object PreviewClock {

  /** System property naming the pinned instant. See the class KDoc for accepted forms. */
  const val PROPERTY: String = "composeai.render.fixedTime"

  /**
   * Time of day the clock pins to when nothing overrides it. `10:10` matches the literal the Wear
   * and Remote design catalogs already paint (`FixedTimeText`) and `:samples:wear`'s
   * `FixedPreviewTimeSource` — so an activity hero and a hand-authored preview of the same screen
   * read the same. It also renders identically under 12- and 24-hour formats, which keeps the
   * pinned value independent of the preview's locale.
   */
  val DEFAULT_TIME: LocalTime = LocalTime.of(10, 10)

  /**
   * Date the clock pins to. Arbitrary but fixed — a Monday, so a preview that paints a weekday gets
   * a stable and unremarkable one.
   */
  val FIXED_DATE: LocalDate = LocalDate.of(2024, 1, 1)

  private val OFF_VALUES = setOf("off", "false", "none", "disabled")

  /**
   * Memoized resolution, keyed by the raw property value so a test (or a daemon whose launcher
   * changed the property between renders) re-resolves instead of serving a stale instant. `resolve`
   * is cheap, but this runs inside composition on every `TimeText` recomposition and every
   * `ACTION_TIME_TICK`.
   */
  @Volatile private var cache: Pair<String?, Long?>? = null

  /**
   * The wall clock a render should see: the pinned instant, or the host's real clock when pinning
   * is switched off. This is what [ShadowWearTimeSource] hands back in place of
   * `System.currentTimeMillis()`.
   */
  @JvmStatic
  fun currentTimeMillis(): Long = pinnedTimeMillis() ?: System.currentTimeMillis()

  /**
   * Epoch millis this render pins to, or `null` when pinning is switched off. Resolved from
   * [PROPERTY] against the JVM's default zone.
   */
  fun pinnedTimeMillis(): Long? {
    val raw = System.getProperty(PROPERTY)
    cache?.let { (cachedRaw, cachedMillis) ->
      if (cachedRaw == raw) return cachedMillis
    }
    return resolve(raw, ZoneId.systemDefault()).also { cache = raw to it }
  }

  /** Drops the memoized resolution. For tests that move [PROPERTY] around within one JVM. */
  internal fun clearCache() {
    cache = null
  }

  /**
   * Pure resolution of [raw] against [zone] — `null` means "don't pin".
   *
   * Resolving against the *default zone* rather than a fixed one is deliberate: what has to be
   * reproducible is the rendered string, and `TimeText` formats through `Calendar.getInstance()`,
   * which reads the default zone. Pinning the instant in UTC would paint a different time on a
   * developer's machine than in CI; pinning the local time-of-day paints `10:10` on both.
   *
   * @throws IllegalArgumentException when [raw] is set but is none of the accepted forms — a typo in
   *   a determinism knob that silently fell back to the wall clock would be worse than a build
   *   failure naming it.
   */
  internal fun resolve(raw: String?, zone: ZoneId): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return at(LocalDateTime.of(FIXED_DATE, DEFAULT_TIME), zone)
    if (value.lowercase() in OFF_VALUES) return null
    value.toLongOrNull()?.let {
      return it
    }
    runCatching { LocalTime.parse(value) }
      .getOrNull()
      ?.let {
        return at(LocalDateTime.of(FIXED_DATE, it), zone)
      }
    runCatching { LocalDateTime.parse(value) }
      .getOrNull()
      ?.let {
        return at(it, zone)
      }
    throw IllegalArgumentException(
      "compose-preview: -D$PROPERTY=$value is not a time. Use HH:mm (e.g. 10:10), an ISO-8601 " +
        "local date-time (e.g. 2024-01-01T10:10), epoch millis, or 'off' to render against the " +
        "host wall clock."
    )
  }

  private fun at(dateTime: LocalDateTime, zone: ZoneId): Long =
    dateTime.atZone(zone).toInstant().toEpochMilli()
}
