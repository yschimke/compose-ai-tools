package ee.schimke.composeai.cli

/**
 * Release this CLI was built from. Surfaced via `compose-preview --version`, used as the default
 * `--plugin-version` in [DoctorCommand]'s remediation snippets, and compared against the latest
 * GitHub release tag in `doctor`'s update check.
 *
 * Updated by release-please — keep the trailing comment intact (see `release-please-config.json`,
 * `extra-files`).
 */
internal const val BUNDLE_VERSION = "0.8.12" // x-release-please-version

/** GitHub repo slug used to resolve releases and the `update` subcommand bootstrap. */
internal const val REPO = "yschimke/compose-ai-tools"

/**
 * Compare two version strings componentwise (`major.minor.patch[-suffix]`), returning -1/0/1.
 * `-SNAPSHOT` and other suffixes sort *before* the same numeric base (so `0.8.11-SNAPSHOT` is older
 * than `0.8.11`) — the convention the rest of the build follows. Anything we can't parse falls back
 * to a string compare so a pathological tag never throws.
 *
 * Lives in [Version.kt] so the doctor update check can compare [BUNDLE_VERSION] against the tag
 * resolved from the GitHub `releases/latest` redirect, and so unit tests can exercise the
 * comparator without round-tripping the rest of `doctor`.
 */
internal fun compareSemver(a: String, b: String): Int {
  fun parts(v: String): Pair<List<Int>, Boolean> {
    val (head, suffix) = v.split('-', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
    val nums = head.split('.').map { it.toIntOrNull() }
    val parsed = nums.all { it != null }
    return (if (parsed) nums.map { it!! } else emptyList()) to suffix.isNotEmpty()
  }
  val (aNums, aPre) = parts(a)
  val (bNums, bPre) = parts(b)
  if (aNums.isEmpty() || bNums.isEmpty()) return a.compareTo(b)
  val len = maxOf(aNums.size, bNums.size)
  for (i in 0 until len) {
    val ai = aNums.getOrElse(i) { 0 }
    val bi = bNums.getOrElse(i) { 0 }
    if (ai != bi) return ai.compareTo(bi)
  }
  return when {
    aPre && !bPre -> -1
    !aPre && bPre -> 1
    else -> 0
  }
}
