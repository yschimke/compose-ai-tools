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
 * Hand-authored because discovery only records composables in the scanned module; M3's components
 * have no `@Preview`. These are data in the real schema, checked by the real
 * [ee.schimke.composeai.discovery.ScreenGenerator]; when discovery learns to record library
 * symbols, this file goes away.
 *
 * The vocabulary is sized by four real sample screens (`LibraryGreetingPreview`,
 * `PermissionGatedCameraScreen`, `ScrollingListPreview`, `AppScaffoldTemplate`), which
 * `M3PaletteScreenTest` rebuilds and asserts. `origin = LIBRARY` marks them as dependency symbols.
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

  // Values a screen sets, in the generator's own vocabulary.

  /**
   * `[value].dp`. `dp` is an extension property on `Int`, hence a chain with a property link; a
   * bare `Int` would call a nonexistent `padding` overload.
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
   * The named values the builder offers for a parameter of [typeFqn], as label to value. A closed
   * list by design: a screen can't reach a symbol nobody put here.
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

  // Modifiers.

  /**
   * The modifier links the builder offers, labelled, each as a [ScreenValue.Chain] link on
   * `Modifier`. `padding` takes the amount because the reference screens use 8, 12 and 16.
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
   * The packages an expression in a generated screen may name: the generator's security allow-list.
   * `androidx.compose.material3` is here for `MaterialTheme.typography` / `.colorScheme`; widening
   * it should be deliberate.
   */
  val expressionPackages: Set<String> =
    setOf("androidx.compose.ui", LAYOUT, UNIT, M3, "androidx.compose.ui.graphics")

  // The records.

  private fun record(
    id: String,
    pkg: String,
    name: String,
    parameters: List<TargetParameter> = emptyList(),
    slots: List<ComponentSlot> = emptyList(),
    requiredOptIns: List<String> = emptyList(),
    androidxOptIns: List<String> = emptyList(),
  ): ComponentRecord =
    ComponentRecord.Builder(
        canonicalId = id,
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "$pkg.${name}Kt",
              callable = "$pkg.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { builder ->
        builder.componentIds = listOf(id)
        builder.parameters = parameters
        builder.slots = slots
        builder.signatureKnown = true
        builder.code =
          ComponentCode.Builder()
            .also { b ->
              b.call = "$name()"
              b.imports = listOf("$pkg.$name")
              b.requiredOptIns = requiredOptIns
              b.androidxOptIns = androidxOptIns
            }
            .build()
      }
      .build()

  private fun slot(
    name: String,
    receiverScope: String? = null,
    type: String = "@Composable () -> Unit",
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.hasDefault = false
        b.composableSlot = true
        b.composableSlotReceiver = receiverScope
      }
      .build()

  private fun value(name: String, type: String, typeFqn: String, hasDefault: Boolean = true) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.hasDefault = hasDefault
      }
      .build()

  private fun modifier() = value("modifier", "Modifier", "androidx.compose.ui.Modifier")

  /** The records, as the file [ee.schimke.composeai.discovery.ScreenGenerator] takes. */
  val records: ComponentRecordFile =
    ComponentRecordFile.Builder(
        module = "m3-builder-palette",
        variant = "authored",
        components =
          listOf(
            // `Scaffold.content` takes `PaddingValues` as a parameter, not a receiver; recorded so
            // a document can name it (`ScreenNode.slotParameters`) and pad its body.
            record(
              "scaffold",
              M3,
              "Scaffold",
              parameters =
                listOf(
                  modifier(),
                  slot("topBar"),
                  slot("floatingActionButton"),
                  slot("content", type = "@Composable (PaddingValues) -> Unit"),
                ),
              slots =
                listOf(
                  ComponentSlot.Builder(name = "topBar", required = false).build(),
                  ComponentSlot.Builder(name = "floatingActionButton", required = false).build(),
                  ComponentSlot.Builder(name = "content", required = true).build(),
                ),
            ),
            record(
              "surface",
              M3,
              "Surface",
              parameters = listOf(modifier(), value("color", "Color", COLOR), slot("content")),
              slots = listOf(ComponentSlot.Builder(name = "content", required = true).build()),
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
                  ComponentSlot.Builder(name = "content", required = true)
                    .also { b -> b.receiverScope = "$LAYOUT.ColumnScope" }
                    .build()
                ),
            ),
            record(
              "card",
              M3,
              "ElevatedCard",
              parameters = listOf(modifier(), slot("content", "$LAYOUT.ColumnScope")),
              slots =
                listOf(
                  ComponentSlot.Builder(name = "content", required = true)
                    .also { b -> b.receiverScope = "$LAYOUT.ColumnScope" }
                    .build()
                ),
            ),
            // `TopAppBar` is `@ExperimentalMaterial3Api`, an AndroidX-mechanism marker, so the
            // generator emits `androidx.annotation.OptIn` for it.
            record(
              "top-app-bar",
              M3,
              "TopAppBar",
              parameters = listOf(modifier(), slot("title")),
              slots = listOf(ComponentSlot.Builder(name = "title", required = true).build()),
              requiredOptIns = listOf("$M3.ExperimentalMaterial3Api"),
            ),
            record(
              "list-item",
              M3,
              "ListItem",
              parameters = listOf(modifier(), slot("headlineContent"), slot("supportingContent")),
              slots =
                listOf(
                  ComponentSlot.Builder(name = "headlineContent", required = true).build(),
                  ComponentSlot.Builder(name = "supportingContent", required = false).build(),
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
                listOf(
                  ComponentSlot.Builder(name = "content", required = true)
                    .also { b -> b.receiverScope = "$LAYOUT.RowScope" }
                    .build()
                ),
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
              slots = listOf(ComponentSlot.Builder(name = "content", required = true).build()),
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
      .build()

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
