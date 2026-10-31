package ee.schimke.composeai.simulator;

/**
 * When the screen has come to rest: {@code {"cmd":"wait","until":"still"}}, which a restarting
 * {@code restart} or {@code reload} sends before it answers, so a {@code screenshot} right after it
 * shows the app's first screen rather than its entry animation.
 *
 * <p>{@link Session} samples the default display once per {@link #pollMs()} (the quiet period
 * itself) and says whether each frame differs from the last. The simulator's clock is Robolectric's
 * virtual one, so a sample costs real time (a full screenshot) while the gap between samples does
 * not: sampling once per quiet period proves stillness with two equal frames, where 100 ms polls
 * took nine screenshots and four real seconds per restart. The screen is still once something is on it and nothing has changed
 * for {@code quietMs}; the wait gives up at {@code timeoutMs} whatever the screen does, so an app
 * that animates without end (a spinner, a ticking clock) costs the timeout and never hangs. Pixels,
 * not Compose's own idleness: a View animation, a window's fade and a splash screen's exit come to
 * rest the same way, and the app's (possibly shrunk) Compose runtime is never reached into. Pure
 * Java, so it is tested without the framework.
 */
final class Stillness {
  /** The shortest gap between samples. */
  static final long MIN_POLL_MS = 50;
  /** How long the screen must stay unchanged to count as still. */
  static final long DEFAULT_QUIET_MS = 400;
  /** The longest a wait for stillness takes. */
  static final long DEFAULT_TIMEOUT_MS = 3_000;

  private final long startMs;
  private final long quietMs;
  private final long timeoutMs;
  /** When the screen last changed, or showed nothing; -1 before the first frame. */
  private long changedAtMs = -1;
  private boolean still;
  private long doneAtMs = -1;

  Stillness(long startMs, long quietMs, long timeoutMs) {
    this.startMs = startMs;
    this.quietMs = Math.max(0, quietMs);
    this.timeoutMs = Math.max(0, timeoutMs);
  }

  /**
   * A frame sampled at [nowMs]: [present] when anything was on screen to capture, [changed] when
   * it differs from the previous frame (the first frame always counts as a change). Returns true
   * once the wait is over: the screen is still, or the timeout has passed.
   */
  boolean frame(long nowMs, boolean present, boolean changed) {
    if (doneAtMs >= 0) return true;
    if (!present || changed || changedAtMs < 0) changedAtMs = nowMs;
    if (present && nowMs - changedAtMs >= quietMs) {
      still = true;
      doneAtMs = nowMs;
    } else if (nowMs - startMs >= timeoutMs) {
      doneAtMs = nowMs;
    }
    return doneAtMs >= 0;
  }

  /** How often the screen is sampled: once per quiet period, so two equal frames prove it. */
  long pollMs() {
    return Math.max(MIN_POLL_MS, quietMs);
  }

  /** Whether the screen came to rest, rather than the wait timing out. */
  boolean still() {
    return still;
  }

  /** How long the wait took, once it is over; until then, how long it has run by [nowMs]. */
  long waitedMs(long nowMs) {
    return (doneAtMs >= 0 ? doneAtMs : nowMs) - startMs;
  }
}
