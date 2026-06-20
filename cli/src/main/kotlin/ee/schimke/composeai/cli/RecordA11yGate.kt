package ee.schimke.composeai.cli

/**
 * Pure decision logic for `compose-preview record --fail-on a11y` (issue #1966) — the Espresso/ATF
 * "fail the test on accessibility violations" model applied to a recorded preview. Kept free of the
 * render-session transport so it's unit-testable: the command fetches the `a11y/atf` findings
 * through the open session, and this reduces them to a pass/fail verdict at the chosen threshold.
 *
 * **Backend note.** ATF findings are produced by the **Android** backend (the framework runs
 * against an Android `View` hierarchy); the desktop a11y path is overlay-only and emits no
 * findings, so on a desktop preview the findings list is empty and the gate never trips. That's a
 * documented property, not a bug — the gate is meaningful for Android recordings.
 */
internal enum class A11yThreshold {
  /** Fail only on ATF `ERROR` findings. The default for `--fail-on a11y`. */
  ERRORS,
  /** Fail on `ERROR` *or* `WARNING` findings. */
  WARNINGS;

  companion object {
    /**
     * Parse the threshold token (`errors` / `warnings`, singular accepted); `null` when unknown.
     */
    fun parse(token: String): A11yThreshold? =
      when (token.trim().lowercase()) {
        "errors",
        "error" -> ERRORS
        "warnings",
        "warning" -> WARNINGS
        else -> null
      }
  }
}

/**
 * What happened when the record command tried to gate on a11y. Lets the caller fail *closed*: a
 * producer error ([EvaluationFailed]) must fail the command, while a backend that legitimately has
 * no ATF data ([NotApplicable], e.g. desktop) must not.
 */
internal sealed interface A11yGateOutcome {
  /** The gate ran; [result] carries the verdict. */
  data class Evaluated(val result: A11yGateResult) : A11yGateOutcome

  /** The backend produces no ATF findings here (desktop overlay-only / kind not advertised). */
  data class NotApplicable(val reason: String) : A11yGateOutcome

  /** The a11y producer errored, so the requested check could not run — the caller fails closed. */
  data class EvaluationFailed(val reason: String) : A11yGateOutcome
}

/** Outcome of the a11y gate: per-severity counts plus whether the [threshold] was tripped. */
internal data class A11yGateResult(
  val errorCount: Int,
  val warningCount: Int,
  val threshold: A11yThreshold,
) {
  val tripped: Boolean
    get() =
      when (threshold) {
        A11yThreshold.ERRORS -> errorCount > 0
        A11yThreshold.WARNINGS -> errorCount + warningCount > 0
      }

  /** One-line human summary for the CLI output. */
  fun summary(): String =
    "$errorCount error(s), $warningCount warning(s) " + "(threshold: ${threshold.name.lowercase()})"
}

/**
 * Count ATF findings by severity and decide the verdict. `AccessibilityFinding.level` is the ATF
 * result type rendered as a string (`"ERROR"` / `"WARNING"`, matched case-insensitively).
 */
internal fun evaluateA11yGate(
  findings: List<AccessibilityFinding>,
  threshold: A11yThreshold,
): A11yGateResult {
  val errors = findings.count { it.level.equals("ERROR", ignoreCase = true) }
  val warnings = findings.count { it.level.equals("WARNING", ignoreCase = true) }
  return A11yGateResult(errorCount = errors, warningCount = warnings, threshold = threshold)
}
