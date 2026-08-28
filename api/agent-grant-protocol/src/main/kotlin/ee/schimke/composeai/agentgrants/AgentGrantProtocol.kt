package ee.schimke.composeai.agentgrants

import java.security.MessageDigest

/**
 * The parts of the agent-grant lane both ends have to agree on.
 *
 * The duration grammar is the clearest case for this module existing: the CLI's `--ttl` and the
 * server's `--agent-grant-max-ttl` accept the same `2h` / `45m` / `90s` spellings, and they have
 * to, because the client asks in that grammar and the server clamps in it. While
 * `parseDurationSeconds` lived in `:cli:serve`, `auth` reached into the preview server to read its
 * own flag.
 *
 * The fingerprint is here for the same reason from the other direction: a grant token is only ever
 * *displayed* as its fingerprint, and a client that computed a different one would show the
 * operator a value that matches nothing on the box.
 *
 * What is deliberately NOT here: minting, expiry, revocation, persistence and rate limiting. Those
 * are `ServeAgentGrantStore`'s, and they are policy rather than protocol.
 */
public object AgentGrantProtocol {

  /**
   * The longest grant any box will mint, whatever the operator asks for.
   *
   * A ceiling rather than a default: beyond a day this stops being temporary access. Shared because
   * the client prints it when explaining why a requested TTL was cut.
   */
  public const val HARD_MAX_GRANT_TTL_SECONDS: Long = 24 * 60 * 60L

  /**
   * Turn `"2h"`, `"45m"`, `"90s"`, or a bare number of seconds into seconds. Null for anything
   * unparseable, so a caller can fall back rather than silently granting a duration nobody chose.
   */
  public fun parseDurationSeconds(raw: String?): Long? {
    val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val match = DURATION.matchEntire(text) ?: return null
    val value = match.groupValues[1].toLongOrNull() ?: return null
    if (value <= 0) return null
    return when (match.groupValues[2]) {
      "h" -> value * 3600
      "m" -> value * 60
      else -> value
    }
  }

  /** `2h 15m`, `45m`, `30s` — how a duration is shown on the page and in the CLI's output. */
  public fun formatDuration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
      hours == 0L -> "${minutes}m"
      minutes == 0L -> "${hours}h"
      else -> "${hours}h ${minutes}m"
    }
  }

  /** SHA-256, first 12 hex characters — the only form of a token that is ever displayed. */
  public fun fingerprintOf(token: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(token.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(12)

  private val DURATION = Regex("(\\d+)\\s*([hms]?)(?:ec|in|our)?s?")
}
