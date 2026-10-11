package ee.schimke.composeai.guidelines

/**
 * What a model charges, in US dollars per million tokens each way: what a run predicts its requests
 * cost with ([PreviewGuidelineRequests.predictedCostUsd]) before any has come back.
 */
public data class GuidelinePrice(val promptPerMillion: Double, val completionPerMillion: Double) {
  /** [inputTokens] in and [outputTokens] out (reasoning included), in US dollars. */
  public fun costUsd(inputTokens: Int, outputTokens: Int): Double =
    (inputTokens * promptPerMillion + outputTokens * completionPerMillion) / 1_000_000

  public companion object {
    /**
     * [model]'s price in OpenRouter's [models] list; null when it is not listed or gives no price
     * either way, and the run falls back to what its answers cost so far.
     */
    public fun of(models: List<OpenRouterModels.Model>, model: String): GuidelinePrice? {
      val listed = models.firstOrNull { it.id == model } ?: return null
      return GuidelinePrice(
        listed.promptPerMillion ?: return null,
        listed.completionPerMillion ?: return null,
      )
    }
  }
}

/**
 * What a run will ask before it asks anything ([GuidelineEngine.plan]): the previews it would send,
 * in how many requests, and — given a [GuidelinePrice] — what that is expected to cost and how many
 * of them the cost cap is expected to reach. Requests are asked in this order, so a capped run
 * checks a prefix: never-checked previews first, each composable's first variant before any
 * composable's second.
 */
public data class GuidelinePlan(
  /** Previews that would be asked about: no cached result, and at least one rule applies. */
  val previews: Int,
  /** Previews answered from the cache, at no cost. */
  val cached: Int,
  /** The requests [previews] are batched into, before any retry, split or follow-up. */
  val requests: Int,
  /** The requests' predicted cost in US dollars; null without a price. */
  val predictedCostUsd: Double?,
  /**
   * How many of [previews] the cost cap is expected to reach, at the predicted costs; null without
   * a price, and [previews] when there is no cap.
   */
  val predictedChecked: Int?,
)
