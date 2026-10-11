package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.GuidelineCredentials

/**
 * Who judges a `guidelines` / `review` run, and why. `--backend auto` (the default for `review`)
 * picks one so the same command does the sensible thing for an agent, a person and CI:
 * 1. inside an agent session, [PROMPT]: the agent is the model, and a second provider's spend is
 *    not one it may start on its own;
 * 2. with `COMPOSE_PREVIEW_OPENROUTER_KEY` set, [OPENROUTER]: setting it is that choice;
 * 3. with a stored key, [OPENROUTER] when the project is trusted, or when a terminal can ask;
 *    otherwise [PROMPT], saying why;
 * 4. with no key, [PROMPT].
 *
 * [OPENAI] (an OpenAI-compatible endpoint, `--base-url`) is only ever chosen by name.
 */
internal object GuidelinesBackend {
  const val AUTO = "auto"
  const val PROMPT = "prompt"
  const val OPENROUTER = "openrouter"
  const val OPENAI = "openai"
  val ALL: List<String> = listOf(AUTO, PROMPT, OPENROUTER, OPENAI)

  /**
   * Environment variables an agent harness sets in the shells it runs, best effort and only ever a
   * default: `--backend` overrides, and `COMPOSE_PREVIEW_AGENT=1` marks any other harness.
   */
  val AGENT_ENV: List<String> =
    listOf("COMPOSE_PREVIEW_AGENT", "CLAUDECODE", "CODEX_SANDBOX", "GEMINI_CLI", "OPENCODE")

  data class Choice(val backend: String, val reason: String)

  /** The agent harness [env] says this runs in, by the variable that says so, or null. */
  fun agentVariable(env: (String) -> String?): String? = AGENT_ENV.firstOrNull { name ->
    env(name)?.trim()?.let { it.isNotEmpty() && it != "0" } == true
  }

  /**
   * The backend for [requested] (null: the command's default, [AUTO] for `review` and [OPENROUTER]
   * for `guidelines`). [openRouterKey] is the key OpenRouter would use, [trusted] whether a stored
   * one is trusted for this project, [terminal] whether one could ask.
   */
  fun choose(
    requested: String?,
    review: Boolean,
    env: (String) -> String?,
    openRouterKey: GuidelineCredentials.Resolved?,
    trusted: Boolean,
    terminal: Boolean,
  ): Choice {
    val asked = requested ?: if (review) AUTO else OPENROUTER
    if (asked != AUTO) return Choice(asked, "--backend $asked")
    agentVariable(env)?.let {
      return Choice(PROMPT, "running inside an agent ($it is set): the agent judges the requests")
    }
    return when {
      openRouterKey == null ->
        Choice(PROMPT, "no OpenRouter key: the requests are written for an agent to judge")
      !openRouterKey.stored -> Choice(OPENROUTER, "${openRouterKey.source} is set")
      trusted -> Choice(OPENROUTER, "the stored key, trusted for this project")
      terminal -> Choice(OPENROUTER, "the stored key, once you trust this project")
      else ->
        Choice(
          PROMPT,
          "the stored key is not trusted for this project (run `compose-preview guidelines key " +
            "trust` in a terminal): the requests are written for an agent to judge",
        )
    }
  }
}
