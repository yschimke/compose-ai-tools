package ee.schimke.composeai.daemon.protocol

import ee.schimke.composeai.data.layoutinspector.SemanticsDelta
import ee.schimke.composeai.data.theme.ThemeDelta
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// `history/diff mode=data` — the wire shapes of the data-product delta.
//
// These live beside the rest of the protocol rather than beside the differ that
// produces them, because they are what crosses the wire: `HistoryDiffResult`
// carries a `HistoryDataDelta`, so a client that speaks the protocol needs the
// shape and nothing else. `HistoryDataDiff` and `A11yDiff` — the algorithms that
// read archived data products off disk and compute these — stay in
// `:daemon:core`, which is where the archive lives.
//
// The schema constants travel with the shapes for the same reason: a consumer
// validating a payload's discriminator should not have to depend on the code
// that computed it.
// ---------------------------------------------------------------------------

public object HistoryDataDiffProduct {
  public const val SCHEMA: String = "history-data-diff/v1"
}

public object A11yDiffProduct {
  public const val SCHEMA: String = "a11y-diff/v1"
}

@Serializable
public data class A11yFieldChange(
  val field: String,
  val from: String? = null,
  val to: String? = null,
)

@Serializable
public data class A11yFindingSummary(
  val ref: String? = null,
  val type: String,
  val level: String,
  val message: String,
  val boundsInScreen: String? = null,
)

@Serializable
public data class A11yFindingChange(
  val ref: String? = null,
  val type: String,
  val changes: List<A11yFieldChange>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class A11yDelta(
  @EncodeDefault val schema: String = A11yDiffProduct.SCHEMA,
  val added: List<A11yFindingSummary> = emptyList(),
  val removed: List<A11yFindingSummary> = emptyList(),
  val changed: List<A11yFindingChange> = emptyList(),
) {
  val isEmpty: Boolean
    get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class HistoryDataDelta(
  // `@EncodeDefault` so the versioned schema discriminator rides the wire even under
  // `encodeDefaults = false`, matching the `SemanticsDelta` / `ThemeDelta` contract.
  @EncodeDefault val schema: String = HistoryDataDiffProduct.SCHEMA,
  val semantics: SemanticsDelta? = null,
  val a11y: A11yDelta? = null,
  val theme: ThemeDelta? = null,
) {
  /** True when every compared section is absent or carries no changes. */
  val isEmpty: Boolean
    get() = (semantics?.isEmpty ?: true) && (a11y?.isEmpty ?: true) && (theme?.isEmpty ?: true)
}
