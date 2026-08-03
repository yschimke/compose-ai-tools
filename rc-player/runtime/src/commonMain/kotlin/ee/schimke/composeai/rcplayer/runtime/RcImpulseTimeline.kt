package ee.schimke.composeai.rcplayer.runtime

/** The four observable branches of AndroidX `ImpulseOperation.paint`. */
public enum class RcImpulsePhase {
  WAITING,
  INITIALIZE,
  PROCESS,
  IDLE,
}

/** Stateful frame decision matching AndroidX's `mInitialPass` behavior. */
public class RcImpulseTimeline {
  private var initialPass: Boolean = true

  public fun evaluate(
    timeSeconds: Float,
    startAtSeconds: Float,
    durationSeconds: Float,
  ): RcImpulsePhase {
    if (timeSeconds < startAtSeconds) return RcImpulsePhase.WAITING
    if (timeSeconds <= startAtSeconds + durationSeconds) {
      return if (initialPass) {
        initialPass = false
        RcImpulsePhase.INITIALIZE
      } else {
        RcImpulsePhase.PROCESS
      }
    }
    initialPass = true
    return RcImpulsePhase.IDLE
  }
}
