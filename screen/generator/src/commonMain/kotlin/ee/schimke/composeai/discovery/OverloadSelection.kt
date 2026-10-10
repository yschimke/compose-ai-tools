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
 * Which overload a component record speaks for (compose-ai-tools#5807).
 *
 * A record holds one signature, and a catalog's policy describes one overload's parameters:
 * `m3/outlined-text-field` offers `value` and `singleLine`, which exist only on
 * `OutlinedTextField(value: String, …)`, not on the `TextFieldState` overload the class file lists
 * first. Neither "first in the class file" nor "what the stickers call" is a rule — the first
 * matched m3-catalog's buttons and sliders by luck, the second its text fields — because only the
 * policy knows which overload its properties describe. So the choice is made against the policy:
 *
 * 1. **Never a deprecated overload.** Excluded outright, whatever it would have won.
 * 2. **Coverage**: the overload whose parameters include every policy name that names a parameter
 *    of SOME overload (a builder-only property such as `containerColor` covers nothing and is
 *    ignored), then the one covering most of them.
 * 3. **Writable**: one the generator can call with the policy supplying its names
 *    ([ComponentSnippets.refusalWith] answers null) beats one it cannot (`Button(…, shapes:
 *    ButtonShapes)` has no placeholder).
 * 4. **Fewest unmatched required parameters**: required parameters the policy does not supply. This
 *    keeps `Card(content)` over `Card(onClick, content)` when the policy names neither.
 * 5. **Most called**: the overload most previews invoke.
 * 6. **Declaration order**, so the answer never depends on manifest order.
 *
 * When some policy names are parameters of deprecated overloads only, the best non-deprecated one
 * is kept — the generator then refuses the properties it lacks, by name — and a diagnostic names
 * those properties, the deprecated overloads that have them, and the current alternatives. When
 * EVERY overload is deprecated the record's code is refused: printing deprecated source is never
 * the answer.
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
