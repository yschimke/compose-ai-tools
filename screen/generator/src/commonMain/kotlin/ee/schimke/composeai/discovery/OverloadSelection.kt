package ee.schimke.composeai.discovery

/**
 * One overload a component could be recorded as: the whole [record] it would publish, built from
 * that overload's signature, plus what the selection ranks on.
 *
 * @property previewCount how many previews' call sites invoked this overload.
 * @property declarationIndex its position among the function's overloads in the class file.
 */
data class OverloadAlternative(
  val record: ComponentRecord,
  val previewCount: Int,
  val declarationIndex: Int,
  val deprecated: Boolean,
)

/**
 * Which overload a component record speaks for. A record holds one signature, and only the
 * catalog's policy knows which overload its properties describe (e.g. `OutlinedTextField(value:
 * String, …)` rather than the `TextFieldState` one listed first). Ranked by:
 *
 * 1. **Never a deprecated overload.**
 * 2. **Coverage**: includes every policy name that names a parameter of some overload (builder-only
 *    properties are ignored), then covers the most.
 * 3. **Writable**: [ComponentSnippets.refusalWith] answers null given the policy's names.
 * 4. **Fewest unmatched required parameters** (keeps `Card(content)` over `Card(onClick,
 *    content)`).
 * 5. **Most called** by previews.
 * 6. **Declaration order**, so the answer never depends on manifest order.
 *
 * If some policy names exist only on deprecated overloads, the best non-deprecated one is kept and
 * a diagnostic names the gap. If every overload is deprecated, the record's code is refused.
 */
object OverloadSelection {

  /** Diagnostic codes this selection publishes in `ui-builder.json`. */
  const val DEPRECATED_ONLY = "component.overload.deprecatedOnly"
  const val ALL_DEPRECATED = "component.overload.allDeprecated"

  data class Choice(val record: ComponentRecord, val diagnostic: UiBuilderDiagnostic? = null)

  /**
   * The record [alternatives] settle on for a component whose policy supplies [supplied] (empty
   * when no policy speaks for it). [alternatives] must be non-empty.
   */
  fun choose(alternatives: List<OverloadAlternative>, supplied: Set<String>): Choice {
    require(alternatives.isNotEmpty())
    val everyName = alternatives.flatMap { alt -> alt.record.parameters.map { it.name } }.toSet()
    val wanted = supplied intersect everyName
    val live = alternatives.filterNot { it.deprecated }
    if (live.isEmpty()) {
      val best = ranked(alternatives, wanted, supplied).first()
      val reason =
        "every overload of `${best.record.symbol.name}` is deprecated " +
          "(${alternatives.joinToString { signatureOf(it.record) }}); no call is emitted for it"
      return Choice(
        best.record
          .newBuilder()
          .also { b ->
            b.code = ComponentCode.Builder().also { c -> c.refusedReason = reason }.build()
          }
          .build(),
        diagnostic(ALL_DEPRECATED, best.record.canonicalId, reason),
      )
    }
    val chosen = ranked(live, wanted, supplied).first()
    if (covers(chosen, wanted)) return Choice(chosen.record)
    // The policy names the kept overload lacks that NO current overload has, but a deprecated one
    // does: those are the properties only deprecated API could supply.
    val liveNames = live.flatMap { names(it) }.toSet()
    val missing =
      (wanted - names(chosen) - liveNames)
        .filter { name -> alternatives.any { it.deprecated && name in names(it) } }
        .sorted()
    if (missing.isEmpty()) return Choice(chosen.record)
    val coveringDeprecated =
      alternatives
        .filter { alt -> alt.deprecated && names(alt).any { it in missing } }
        .sortedBy { it.declarationIndex }
    return Choice(
      chosen.record,
      diagnostic(
        DEPRECATED_ONLY,
        chosen.record.canonicalId,
        "only deprecated overloads of `${chosen.record.symbol.name}` cover the policy's " +
          "${missing.joinToString { "`$it`" }}: " +
          coveringDeprecated.joinToString { signatureOf(it.record) } +
          ". Kept the non-deprecated ${signatureOf(chosen.record)}, so those properties are " +
          "refused rather than emitted as deprecated calls. Alternatives: " +
          live.joinToString { signatureOf(it.record) } +
          ". Point the policy at the current overload's parameters.",
      ),
    )
  }

  private fun ranked(
    candidates: List<OverloadAlternative>,
    wanted: Set<String>,
    supplied: Set<String>,
  ): List<OverloadAlternative> =
    candidates.sortedWith(
      compareByDescending<OverloadAlternative> { covers(it, wanted) }
        .thenByDescending { covered(it, wanted) }
        .thenByDescending { ComponentSnippets.refusalWith(it.record, supplied) == null }
        .thenBy { unmatchedRequired(it, supplied) }
        .thenByDescending { it.previewCount }
        .thenBy { it.declarationIndex }
    )

  private fun names(alt: OverloadAlternative) = alt.record.parameters.map { it.name }.toSet()

  private fun covers(alt: OverloadAlternative, wanted: Set<String>) = names(alt).containsAll(wanted)

  private fun covered(alt: OverloadAlternative, wanted: Set<String>) =
    (names(alt) intersect wanted).size

  private fun unmatchedRequired(alt: OverloadAlternative, supplied: Set<String>) =
    alt.record.parameters.count { !it.hasDefault && it.name !in supplied }

  private fun signatureOf(record: ComponentRecord) =
    "`${record.symbol.name}(${record.parameters.joinToString { "${it.name}: ${it.type}" }})`"

  private fun diagnostic(code: String, subject: String, message: String) =
    UiBuilderDiagnostic.Builder(code = code, subject = subject, message = message).build()
}
