package ee.schimke.composeai.cli.serve

/**
 * What an agent grant ([ServeAgentGrantStore]) may unlock, and nothing else — see
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * The three values are the three gates this server already has, named. That correspondence is the
 * whole point: a scope is not a new policy, it is permission to pass one existing check, so there
 * is no way for a grant to reach a surface the operator token and a signed-in visitor couldn't.
 *
 * They are **ordered and cumulative** — declaration order is least-to-most privileged and each
 * value [implies] the ones before it. So a `playground` grant is also a `live` grant is also a
 * `preview` grant, and a gate asks one question ([ServeAgentGrantStore.Grant.allows]) rather than
 * matching a set. Adding a value in the middle re-orders the lattice, which is why the ordinal is
 * load-bearing and the enum is not alphabetised.
 */
enum class ServeAgentGrantScope(
  /** The wire/CLI name — lowercase, stable, what `--scope` and the JSON carry. */
  val wire: String,
  /** One line for the approval page: what the human is actually agreeing to. */
  val humanDescription: String,
) {
  /**
   * Read the published catalogs: browse pages, baked renders, `/status`. Satisfies the token gate
   * ([ServeHttpServer.rejectBadToken]) and nothing further, so on a `--public` box this scope is
   * already what an anonymous visitor has.
   */
  PREVIEW("preview", "Browse this server's catalogs and their rendered previews"),

  /**
   * Open a live daemon-backed session — the viewer WebSocket, on-demand renders, theme switching.
   * Satisfies [ServeHttpServer.rejectMissingGithubAuth], the gate a signed-in GitHub visitor
   * passes. Costs the box real CPU (a render daemon), which is why it is a step above [PREVIEW]
   * rather than part of it.
   */
  LIVE("live", "Open live preview sessions, which start a render daemon on this machine"),

  /**
   * Compile and run a Kotlin snippet on this host (the playground lane). Satisfies
   * [ServeHttpServer.rejectMissingGithubRepoAccess].
   *
   * This is arbitrary code execution on the box, inside the playground's sandbox and nothing more.
   * It is deliberately absent from [DEFAULT_MAX], and an approver who does not themselves hold
   * repository access may not grant it — see [ServeAgentGrants].
   */
  PLAYGROUND("playground", "Compile and run Kotlin snippets on this machine (the playground)");

  /** True when holding `this` also confers [other] — i.e. [other] is at or below this rung. */
  fun implies(other: ServeAgentGrantScope): Boolean = ordinal >= other.ordinal

  companion object {
    /**
     * What a grant carries when the agent asked for nothing in particular: enough to look, not
     * enough to spend the box's CPU. An agent that wants [LIVE] says so.
     */
    val DEFAULT_REQUEST: ServeAgentGrantScope = PREVIEW

    /**
     * The operator's ceiling when `--agent-grant-scopes` is unset. [PLAYGROUND] is out because it
     * runs code; opting into it must be a deliberate, typed decision rather than a default an
     * operator inherits by upgrading.
     */
    val DEFAULT_MAX: ServeAgentGrantScope = LIVE

    /** Parse one wire name, case-insensitively; null when it names nothing. */
    fun parse(value: String?): ServeAgentGrantScope? {
      val wanted = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
      return entries.firstOrNull { it.wire == wanted }
    }

    /**
     * The highest scope named in a comma/space-separated list — `"preview,live"` is `LIVE`, because
     * the values are cumulative and asking for a rung is asking for everything under it.
     *
     * Returns null when the list is blank; **throws** when it names something unknown, so a typo in
     * `--agent-grant-scopes` fails the server's startup instead of silently narrowing every future
     * grant to `preview` and leaving the operator to wonder why live never works.
     */
    fun parseHighest(list: String?): ServeAgentGrantScope? {
      val names =
        list?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
      if (names.isEmpty()) return null
      return names
        .map { name ->
          parse(name)
            ?: throw IllegalArgumentException(
              "unknown agent grant scope '$name' — expected one of ${entries.joinToString(", ") { it.wire }}"
            )
        }
        .max()
    }

    /** Every scope up to and including [highest], least-privileged first — what a grant lists. */
    fun upTo(highest: ServeAgentGrantScope): List<ServeAgentGrantScope> = entries.filter {
      highest.implies(it)
    }
  }
}
