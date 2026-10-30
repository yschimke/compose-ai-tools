package ee.schimke.composeai.simulator;

/**
 * Gesture navigation's Back swipe, as a device's {@code EdgeBackGestureHandler} recognises it: a
 * touch that goes down within [edgePx] of the left or right edge is the app's until it moves
 * inward past the touch slop, more across than along the edge; from then it is the system's (the
 * app's pointer is cancelled, as {@code pilferPointers} does) and reports its progress until the
 * finger lifts, Back if it travelled [triggerPx], cancelled if not. A touch that moves along the
 * edge first stays the app's. Free of Android types so it is tested on the JVM.
 */
final class EdgeSwipe {

  /** {@code BackEvent.EDGE_LEFT} and {@code EDGE_RIGHT}. */
  static final int EDGE_LEFT = 0;

  static final int EDGE_RIGHT = 1;

  /** What one pointer event does. */
  enum Step {
    /** To the app, as it would have gone without gesture navigation. */
    APP,
    /** Recognised: cancel the app's pointer, and Back starts at this event. */
    START,
    /** The system's: Back's progress moved. */
    PROGRESS,
    /** Lifted past the trigger distance: Back. */
    COMMIT,
    /** Lifted short of it, or cancelled: no Back. */
    CANCEL
  }

  /** {@code MotionEvent}'s actions, kept here so it is testable without android.jar. */
  static final int DOWN = 0;

  static final int UP = 1;
  static final int MOVE = 2;
  static final int CANCELLED = 3;

  private final int widthPx;
  private final float edgePx;
  private final float slopPx;
  private final float triggerPx;

  private enum State {
    IDLE,
    PENDING,
    ACTIVE,
    APP
  }

  private State state = State.IDLE;
  private int edge;
  private float startX;
  private float startY;
  private float progress;

  EdgeSwipe(int widthPx, float edgePx, float slopPx, float triggerPx) {
    this.widthPx = widthPx;
    this.edgePx = edgePx;
    this.slopPx = slopPx;
    this.triggerPx = triggerPx;
  }

  /** The pointer event [action] at ([x], [y]), in display pixels. */
  Step on(int action, float x, float y) {
    switch (action) {
      case DOWN:
        startX = x;
        startY = y;
        progress = 0;
        if (x <= edgePx) {
          edge = EDGE_LEFT;
          state = State.PENDING;
        } else if (x >= widthPx - edgePx) {
          edge = EDGE_RIGHT;
          state = State.PENDING;
        } else {
          state = State.APP;
        }
        return Step.APP;
      case MOVE:
        if (state == State.PENDING) {
          float across = inward(x);
          float along = Math.abs(y - startY);
          if (along > slopPx && along >= across) {
            state = State.APP;
          } else if (across > slopPx && across > along) {
            state = State.ACTIVE;
            progress = progressAt(x);
            return Step.START;
          }
          return Step.APP;
        }
        if (state == State.ACTIVE) {
          progress = progressAt(x);
          return Step.PROGRESS;
        }
        return Step.APP;
      case UP:
      case CANCELLED:
        {
          State was = state;
          state = State.IDLE;
          if (was != State.ACTIVE) return Step.APP;
          progress = progressAt(x);
          return action == UP && inward(x) >= triggerPx ? Step.COMMIT : Step.CANCEL;
        }
      default:
        return state == State.ACTIVE ? Step.PROGRESS : Step.APP;
    }
  }

  /** Whether the system has the pointer: events go to the gesture, not the app. */
  boolean active() {
    return state == State.ACTIVE;
  }

  /** The edge the swipe started from, {@link #EDGE_LEFT} or {@link #EDGE_RIGHT}. */
  int edge() {
    return edge;
  }

  /** How far across, from 0 at the edge to 1 at the far side, as {@code BackEvent.getProgress}. */
  float progress() {
    return progress;
  }

  private float inward(float x) {
    return edge == EDGE_LEFT ? x - startX : startX - x;
  }

  /** Linear across the rest of the display from where it started, as {@code TouchTracker}. */
  private float progressAt(float x) {
    float span = edge == EDGE_LEFT ? widthPx - startX : startX;
    if (span <= 0) return 0;
    return Math.max(0, Math.min(1, inward(x) / span));
  }
}
