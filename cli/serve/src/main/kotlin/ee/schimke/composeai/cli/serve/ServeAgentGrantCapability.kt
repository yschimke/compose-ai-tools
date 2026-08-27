package ee.schimke.composeai.cli.serve

/**
 * What a grant may do **beside** its scope rung — see
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * [ServeAgentGrantScope] is a ladder: each rung implies the ones below it, because each names a
 * strictly larger amount of this box's CPU and trust. A capability is the other shape — an
 * **independent** permission that does not sit anywhere on that ladder and must not be dragged
 * along by it.
 *
 * [IMAGES] is the worked example, and the reason this type exists rather than a fourth rung.
 * Uploading a PNG is not "more" than opening a render daemon and not "less" than running a Kotlin
 * snippet; it is sideways from both. As a rung between `live` and `playground` it would have made
 * every playground grant an uploader and every uploader a daemon-starter, neither of which anyone
 * asked for. So scope answers *how much of this machine may the agent spend*, and a capability
 * answers *may it also do this specific thing* — and the two are chosen separately, by the same
 * human, on the same page.
 *
 * The set is therefore unordered and non-cumulative, which is also why the approval page draws
 * these as checkboxes where the scopes are radios: independent boxes describe independent
 * permissions honestly.
 */
enum class ServeAgentGrantCapability(
  /** The wire/CLI name — lowercase, stable, what `--capability` and the JSON carry. */
  val wire: String,
  /** One line for the approval page: what the human is actually agreeing to. */
  val humanDescription: String,
) {
  /**
   * Upload rendered images through the image lane (`POST /images`), so the returned URLs can be
   * embedded in a pull-request body.
   *
   * Only meaningful on a box that runs that lane at all (`--accept-images`); `serve` refuses to
   * offer this capability otherwise, rather than minting grants for a route that does not exist.
   *
   * Note what an upload actually publishes: the image is served from an unguessable but
   * **unauthenticated** URL, because GitHub's camo proxy fetches a PR body's images anonymously. So
   * this is a publication right on the operator's origin, which is why it is off unless the
   * operator opts in and a human ticks it.
   */
  IMAGES(
    "images",
    // No lifetime in this sentence: `--image-ttl` is an operator setting, so a hard-coded "for 7
    // days" would understate the exposure on any box configured for longer — on the one page whose
    // job is to state accurately what is being agreed to.
    "Upload rendered preview images, published at unlisted URLs on this server until they expire",
  );

  companion object {
    /** Parse one wire name, case-insensitively; null when it names nothing. */
    fun parse(value: String?): ServeAgentGrantCapability? {
      val wanted = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
      return entries.firstOrNull { it.wire == wanted }
    }

    /**
     * Parse a comma/space-separated list, ignoring blanks.
     *
     * **Throws** on a name that means nothing, so a typo in `--agent-grant-capabilities` fails the
     * server's startup instead of silently withholding a capability the operator believes they
     * enabled — the same trade [ServeAgentGrantScope.parseHighest] makes, for the same reason.
     */
    fun parseAll(list: String?): Set<ServeAgentGrantCapability> {
      val names =
        list
          ?.split(',', ' ')
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          .orEmpty()
          .ifEmpty {
            return emptySet()
          }
      return names
        .map {
          parse(it)
            ?: throw IllegalArgumentException(
              "unknown agent-grant capability '$it' (known: ${entries.joinToString(", ") { c -> c.wire }})"
            )
        }
        .toSet()
    }

    /** Wire names, in declaration order — for JSON, the CLI and the audit line. */
    fun wireNames(capabilities: Set<ServeAgentGrantCapability>): List<String> =
      entries.filter { it in capabilities }.map { it.wire }
  }
}
