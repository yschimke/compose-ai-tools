package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.discovery.TargetParameter

/**
 * Component records for the Material 3 subset the UI builder offers.
 *
 * ### Why these are authored rather than discovered
 *
 * `ComponentRecords.from(manifest)` derives records **wholly from discovered preview targets**, and
 * a preview target is a composable in the scanned module. Material 3's own components are a
 * dependency's symbols with no `@Preview` anywhere, so discovery never sees them: running
 * `composePreviewDiscover` on the M3 catalog yields three records — `Sticker`, `CatalogSticker`,
 * `FullScreenM3` — the catalog's own wrappers, and none of the components a screen is built from.
 *
 * So these are hand-authored. That is a smaller claim than it sounds, and a different one from the
 * `ComponentSpec` table this replaced: it is **data in the real schema**, read by the real
 * [ee.schimke.composeai.discovery.ScreenGenerator], which type-checks every value against
 * [TargetParameter.typeFqn], refuses what it cannot prove, and holds the `expressionPackages`
 * allow-list. Nothing here is a second code generator. When discovery learns to record library
 * symbols, this file is deleted and the same document generates unchanged.
 *
 * ### `origin = LIBRARY`
 *
 * These are a dependency's symbols, not the project's, which is exactly what that flag records.
 */
object M3Palette {

  /** The container ids, which take children. */
  val containerIds: List<String> = listOf("scaffold", "lazy-column", "column", "card")

  /** The leaf ids, which do not. */
  val componentIds: List<String> = listOf("button", "text")

  /** Every id the builder offers, containers first. */
  val allIds: List<String> = containerIds + componentIds

  private fun record(
    id: String,
    pkg: String,
    name: String,
    parameters: List<TargetParameter> = emptyList(),
    slots: List<ComponentSlot> = emptyList(),
  ): ComponentRecord =
    ComponentRecord(
      canonicalId = id,
      componentIds = listOf(id),
      symbol =
        ComponentSymbol(
          jvmOwner = "$pkg.${name}Kt",
          callable = "$pkg.$name",
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters,
      slots = slots,
      signatureKnown = true,
      code = ComponentCode(call = "$name()", imports = listOf("$pkg.$name")),
    )

  private fun slotParam(name: String, receiverScope: String? = null) =
    TargetParameter(
      name = name,
      type = "@Composable () -> Unit",
      hasDefault = false,
      composableSlot = true,
      composableSlotReceiver = receiverScope,
    )

  private const val M3 = "androidx.compose.material3"
  private const val LAYOUT = "androidx.compose.foundation.layout"

  /** The records, as the file [ee.schimke.composeai.discovery.ScreenGenerator] takes. */
  val records: ComponentRecordFile =
    ComponentRecordFile(
      module = "m3-builder-palette",
      variant = "authored",
      components =
        listOf(
          record(
            "scaffold",
            M3,
            "Scaffold",
            parameters =
              listOf(
                modifierParam(),
                slotParam("topBar"),
                slotParam("content", "$LAYOUT.PaddingValues"),
              ),
            slots =
              listOf(
                ComponentSlot("topBar", required = false),
                ComponentSlot("content", required = true, receiverScope = "$LAYOUT.PaddingValues"),
              ),
          ),
          record(
            "lazy-column",
            "androidx.compose.foundation.lazy",
            "LazyColumn",
            parameters = listOf(modifierParam(), slotParam("content")),
            slots = listOf(ComponentSlot("content", required = true)),
          ),
          record(
            "column",
            LAYOUT,
            "Column",
            parameters = listOf(modifierParam(), slotParam("content", "$LAYOUT.ColumnScope")),
            slots =
              listOf(
                ComponentSlot("content", required = true, receiverScope = "$LAYOUT.ColumnScope")
              ),
          ),
          record(
            "card",
            M3,
            "ElevatedCard",
            parameters = listOf(modifierParam(), slotParam("content", "$LAYOUT.ColumnScope")),
            slots =
              listOf(
                ComponentSlot("content", required = true, receiverScope = "$LAYOUT.ColumnScope")
              ),
          ),
          record(
            "button",
            M3,
            "Button",
            parameters =
              listOf(
                TargetParameter(
                  name = "onClick",
                  type = "() -> Unit",
                  typeFqn = "kotlin.Function0",
                  hasDefault = false,
                ),
                modifierParam(),
                TargetParameter(
                  name = "enabled",
                  type = "Boolean",
                  typeFqn = "kotlin.Boolean",
                  hasDefault = true,
                ),
                slotParam("content", "$LAYOUT.RowScope"),
              ),
            slots =
              listOf(ComponentSlot("content", required = true, receiverScope = "$LAYOUT.RowScope")),
          ),
          record(
            "text",
            M3,
            "Text",
            parameters =
              listOf(
                TargetParameter(
                  name = "text",
                  type = "String",
                  typeFqn = "kotlin.String",
                  hasDefault = false,
                ),
                modifierParam(),
              ),
          ),
        ),
    )

  private fun modifierParam() =
    TargetParameter(
      name = "modifier",
      type = "Modifier",
      typeFqn = "androidx.compose.ui.Modifier",
      hasDefault = true,
    )

  /**
   * The modifier links the builder offers, labelled, as the chain link each toggles.
   *
   * A modifier is a [ScreenValue.Chain] on `Modifier`, which is the generator's own vocabulary for
   * it — not a string the builder splices into source. Every link is imported and called by its
   * simple name, and the generator refuses two links claiming one simple name from different
   * packages, so a chain cannot silently become a different chain.
   */
  val modifierLinks: List<Pair<String, ChainLink>> =
    listOf(
      "fillMaxWidth" to ChainLink("$LAYOUT.fillMaxWidth"),
      "fillMaxSize" to ChainLink("$LAYOUT.fillMaxSize"),
      "padding(8)" to ChainLink("$LAYOUT.padding", positional = listOf(ScreenValue.Whole(8))),
    )

  /**
   * The packages an expression in a generated screen may name — the generator's security guard.
   *
   * This is an **allow-list**, not a convenience: [ee.schimke.composeai.discovery.ScreenGenerator]
   * refuses any reference or chain link outside it rather than emitting a call into a package the
   * document's author chose. So it holds exactly what this palette's own values need — `Modifier`
   * itself, the layout modifiers [modifierLinks] offers, and `dp` for the sizes they take — and
   * widening it is a deliberate act, not a side effect of adding a component.
   */
  val expressionPackages: Set<String> =
    setOf("androidx.compose.ui", LAYOUT, "androidx.compose.ui.unit")

  /** `Modifier` as the receiver every chain in [modifierLinks] hangs off. */
  val modifierReceiver: ScreenValue.Reference =
    ScreenValue.Reference(
      rootFqn = "androidx.compose.ui.Modifier",
      typeFqn = "androidx.compose.ui.Modifier",
    )
}
