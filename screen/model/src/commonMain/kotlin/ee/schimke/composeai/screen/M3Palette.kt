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
 * So these are hand-authored. That is a smaller claim than it sounds: it is **data in the real
 * schema**, read by the real [ee.schimke.composeai.discovery.ScreenGenerator], which type-checks
 * every value against [TargetParameter.typeFqn], refuses what it cannot prove, and holds the
 * `expressionPackages` allow-list. Nothing here is a second code generator. When discovery learns
 * to record library symbols, this file is deleted and the same document generates unchanged.
 *
 * ### What decides the contents
 *
 * Not "what looks useful": the vocabulary is sized by **real screens**. Each component and each
 * argument here is one a preview in this repository's own catalog actually uses —
 * `LibraryGreetingPreview`, `PermissionGatedCameraScreen`, `ScrollingListPreview` and
 * `AppScaffoldTemplate`, four screens from four different sample apps. `M3PaletteScreenTest`
 * rebuilds those four as documents and asserts the generated source, so a component that drifts
 * from the signature it claims fails there rather than in a browser.
 *
 * ### `origin = LIBRARY`
 *
 * These are a dependency's symbols, not the project's, which is exactly what that flag records.
 */
object M3Palette {

  private const val M3 = "androidx.compose.material3"
  private const val LAYOUT = "androidx.compose.foundation.layout"
  private const val UNIT = "androidx.compose.ui.unit"

  /** The container ids, which take children. */
  val containerIds: List<String> =
    listOf("scaffold", "surface", "column", "card", "top-app-bar", "list-item")

  /** The leaf ids, which do not. */
  val componentIds: List<String> = listOf("button", "fab", "text", "divider")

  /** Every id the builder offers, containers first. */
  val allIds: List<String> = containerIds + componentIds

  // ---------------------------------------------------------------------------------------------
  // Values a screen sets, as the generator's own vocabulary rather than text spliced into source.
  // ---------------------------------------------------------------------------------------------

  /**
   * `[value].dp`, as the generator's own vocabulary for a dimension.
   *
   * `dp` is an extension **property** on `Int`, which is why this is a chain with a property link
   * rather than a call. Passing the bare `Int` instead — `padding(8)` — is a call to a `padding`
   * overload that does not exist, and the compiler says only "none of the following candidates is
   * applicable".
   */
  fun dp(value: Int): ScreenValue =
    ScreenValue.Chain(
      receiver = ScreenValue.Whole(value.toLong()),
      links = listOf(ChainLink("$UNIT.dp", property = true)),
      typeFqn = "$UNIT.Dp",
    )

  /** A read off a Material 3 theme object — `MaterialTheme.typography.titleMedium`. */
  private fun themeRead(vararg members: String, typeFqn: String): ScreenValue =
    ScreenValue.Reference(
      rootFqn = "$M3.MaterialTheme",
      members = members.toList(),
      typeFqn = typeFqn,
    )

  private const val TEXT_STYLE = "androidx.compose.ui.text.TextStyle"
  private const val COLOR = "androidx.compose.ui.graphics.Color"
  private const val ARRANGEMENT_VERTICAL = "$LAYOUT.Arrangement\$Vertical"
  private const val PADDING_VALUES = "$LAYOUT.PaddingValues"

  /**
   * The named values the builder offers for a parameter of [typeFqn], as label to value.
   *
   * A closed list rather than a free expression box, and that is the security posture rather than a
   * simplification: `expressionPackages` gates which packages a value may *name*, and every choice
   * here is one this file authored. A screen cannot reach a symbol nobody put in this list.
   */
  fun choicesFor(typeFqn: String?): List<Pair<String, ScreenValue>> =
    when (typeFqn) {
      TEXT_STYLE ->
        listOf(
            "titleLarge",
            "titleMedium",
            "bodyLarge",
            "bodyMedium",
            "bodySmall",
            "labelLarge",
          )
          .map { it to themeRead("typography", it, typeFqn = TEXT_STYLE) }
      COLOR ->
        listOf("background", "surface", "surfaceVariant", "primary", "secondaryContainer").map {
          it to themeRead("colorScheme", it, typeFqn = COLOR)
        }
      ARRANGEMENT_VERTICAL ->
        listOf(
          "Top" to
            ScreenValue.Reference(
              rootFqn = "$LAYOUT.Arrangement",
              members = listOf("Top"),
              typeFqn = ARRANGEMENT_VERTICAL,
            ),
          "Center" to
            ScreenValue.Reference(
              rootFqn = "$LAYOUT.Arrangement",
              members = listOf("Center"),
              typeFqn = ARRANGEMENT_VERTICAL,
            ),
        ) + (4..24 step 4).map { "spacedBy($it)" to spacedBy(it) }
      PADDING_VALUES ->
        listOf(
          "h16 v8" to paddingValues(horizontal = 16, vertical = 8),
          "h12 v6" to paddingValues(horizontal = 12, vertical = 6),
          "all 8" to
            ScreenValue.Construct(
              callableFqn = PADDING_VALUES,
              positional = listOf(dp(8)),
              typeFqn = PADDING_VALUES,
            ),
        )
      else -> emptyList()
    }

  /** `Arrangement.spacedBy(n.dp)` — a call on the `Arrangement` object, so a construct. */
  fun spacedBy(gap: Int): ScreenValue =
    ScreenValue.Construct(
      callableFqn = "$LAYOUT.Arrangement.spacedBy",
      positional = listOf(dp(gap)),
      typeFqn = ARRANGEMENT_VERTICAL,
    )

  /** `PaddingValues(horizontal = …, vertical = …)`. */
  fun paddingValues(horizontal: Int, vertical: Int): ScreenValue =
    ScreenValue.Construct(
      callableFqn = PADDING_VALUES,
      named = mapOf("horizontal" to dp(horizontal), "vertical" to dp(vertical)),
      typeFqn = PADDING_VALUES,
    )

  // ---------------------------------------------------------------------------------------------
  // Modifiers.
  // ---------------------------------------------------------------------------------------------

  /**
   * The modifier links the builder offers, labelled, as the chain link each toggles.
   *
   * A modifier is a [ScreenValue.Chain] on `Modifier`, which is the generator's own vocabulary for
   * it — not a string the builder splices into source. Every link is imported and called by its
   * simple name, and the generator refuses two links claiming one simple name from different
   * packages, so a chain cannot silently become a different chain.
   *
   * `padding` is a *function* of the amount rather than a fixed entry, because the four screens
   * this palette is sized against use 8, 12 and 16 between them. A fixed `padding(8.dp)` chip could
   * not build any of them faithfully, which is the whole question being asked of the builder.
   */
  fun modifierLinks(paddingDp: Int): List<Pair<String, ChainLink>> =
    listOf(
      "fillMaxWidth" to ChainLink("$LAYOUT.fillMaxWidth"),
      "fillMaxSize" to ChainLink("$LAYOUT.fillMaxSize"),
      "padding($paddingDp)" to ChainLink("$LAYOUT.padding", positional = listOf(dp(paddingDp))),
    )

  /** The links at the default amount, for callers with no amount of their own. */
  val modifierLinks: List<Pair<String, ChainLink>> = modifierLinks(DEFAULT_PADDING_DP)

  /** `Modifier` as the receiver every modifier chain hangs off. */
  val modifierReceiver: ScreenValue.Reference =
    ScreenValue.Reference(
      rootFqn = "androidx.compose.ui.Modifier",
      typeFqn = "androidx.compose.ui.Modifier",
    )

  /**
   * The packages an expression in a generated screen may name — the generator's security guard.
   *
   * An **allow-list**, not a convenience: [ee.schimke.composeai.discovery.ScreenGenerator] refuses
   * any reference, construct or chain link outside it rather than emitting a call into a package
   * the document's author chose. `androidx.compose.material3` is here because the real screens read
   * `MaterialTheme.typography` and `MaterialTheme.colorScheme`; widening it further is a deliberate
   * act, not a side effect of adding a component.
   */
  val expressionPackages: Set<String> =
    setOf("androidx.compose.ui", LAYOUT, UNIT, M3, "androidx.compose.ui.graphics")

  // ---------------------------------------------------------------------------------------------
  // The records.
  // ---------------------------------------------------------------------------------------------

  private fun record(
    id: String,
    pkg: String,
    name: String,
    parameters: List<TargetParameter> = emptyList(),
    slots: List<ComponentSlot> = emptyList(),
    requiredOptIns: List<String> = emptyList(),
    androidxOptIns: List<String> = emptyList(),
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
      code =
        ComponentCode(
          call = "$name()",
          imports = listOf("$pkg.$name"),
          requiredOptIns = requiredOptIns,
          androidxOptIns = androidxOptIns,
        ),
    )

  private fun slot(name: String, receiverScope: String? = null) =
    TargetParameter(
      name = name,
      type = "@Composable () -> Unit",
      hasDefault = false,
      composableSlot = true,
      composableSlotReceiver = receiverScope,
    )

  private fun value(name: String, type: String, typeFqn: String, hasDefault: Boolean = true) =
    TargetParameter(name = name, type = type, typeFqn = typeFqn, hasDefault = hasDefault)

  private fun modifier() = value("modifier", "Modifier", "androidx.compose.ui.Modifier")

  /** The records, as the file [ee.schimke.composeai.discovery.ScreenGenerator] takes. */
  val records: ComponentRecordFile =
    ComponentRecordFile(
      module = "m3-builder-palette",
      variant = "authored",
      components =
        listOf(
          // `Scaffold.content` is `@Composable (PaddingValues) -> Unit` — the padding arrives as a
          // parameter, not a receiver, so no receiver scope is recorded for it.
          record(
            "scaffold",
            M3,
            "Scaffold",
            parameters =
              listOf(modifier(), slot("topBar"), slot("floatingActionButton"), slot("content")),
            slots =
              listOf(
                ComponentSlot("topBar", required = false),
                ComponentSlot("floatingActionButton", required = false),
                ComponentSlot("content", required = true),
              ),
          ),
          record(
            "surface",
            M3,
            "Surface",
            parameters = listOf(modifier(), value("color", "Color", COLOR), slot("content")),
            slots = listOf(ComponentSlot("content", required = true)),
          ),
          record(
            "column",
            LAYOUT,
            "Column",
            parameters =
              listOf(
                modifier(),
                value("verticalArrangement", "Arrangement.Vertical", ARRANGEMENT_VERTICAL),
                slot("content", "$LAYOUT.ColumnScope"),
              ),
            slots =
              listOf(
                ComponentSlot("content", required = true, receiverScope = "$LAYOUT.ColumnScope")
              ),
          ),
          record(
            "card",
            M3,
            "ElevatedCard",
            parameters = listOf(modifier(), slot("content", "$LAYOUT.ColumnScope")),
            slots =
              listOf(
                ComponentSlot("content", required = true, receiverScope = "$LAYOUT.ColumnScope")
              ),
          ),
          // `TopAppBar` is still `@ExperimentalMaterial3Api`, which is why the opt-in is recorded
          // rather than assumed: the generator writes the annotation onto the screen, and it is an
          // AndroidX-mechanism marker, so it goes under `androidx.annotation.OptIn` rather than
          // `kotlin.OptIn`. A screen using no app bar carries neither.
          record(
            "top-app-bar",
            M3,
            "TopAppBar",
            parameters = listOf(modifier(), slot("title")),
            slots = listOf(ComponentSlot("title", required = true)),
            requiredOptIns = listOf("$M3.ExperimentalMaterial3Api"),
          ),
          record(
            "list-item",
            M3,
            "ListItem",
            parameters = listOf(modifier(), slot("headlineContent"), slot("supportingContent")),
            slots =
              listOf(
                ComponentSlot("headlineContent", required = true),
                ComponentSlot("supportingContent", required = false),
              ),
          ),
          record(
            "button",
            M3,
            "Button",
            parameters =
              listOf(
                value("onClick", "() -> Unit", "kotlin.Function0", hasDefault = false),
                modifier(),
                value("enabled", "Boolean", "kotlin.Boolean"),
                value("contentPadding", "PaddingValues", PADDING_VALUES),
                slot("content", "$LAYOUT.RowScope"),
              ),
            slots =
              listOf(ComponentSlot("content", required = true, receiverScope = "$LAYOUT.RowScope")),
          ),
          record(
            "fab",
            M3,
            "FloatingActionButton",
            parameters =
              listOf(
                value("onClick", "() -> Unit", "kotlin.Function0", hasDefault = false),
                modifier(),
                slot("content"),
              ),
            slots = listOf(ComponentSlot("content", required = true)),
          ),
          record(
            "text",
            M3,
            "Text",
            parameters =
              listOf(
                value("text", "String", "kotlin.String", hasDefault = false),
                modifier(),
                value("style", "TextStyle", TEXT_STYLE),
              ),
          ),
          record("divider", M3, "HorizontalDivider", parameters = listOf(modifier())),
        ),
    )

  private val byId = records.components.associateBy { it.canonicalId }

  /** The slots [componentId] takes, in declaration order. Empty for a leaf. */
  fun slotsOf(componentId: String?): List<String> =
    byId[componentId]?.slots?.map { it.name } ?: emptyList()

  /**
   * The parameters of [componentId] a builder shows an editor for: everything that is not a slot,
   * not the modifier chain (which has its own control) and not a handler.
   */
  fun editableParametersOf(componentId: String?): List<TargetParameter> =
    byId[componentId]
      ?.parameters
      .orEmpty()
      .filterNot { it.composableSlot }
      .filterNot { it.name == "modifier" }
      .filterNot { it.typeFqn == "kotlin.Function0" }
}

/** The padding the builder starts a modifier chip at, before anyone edits the amount. */
private const val DEFAULT_PADDING_DP = 16
