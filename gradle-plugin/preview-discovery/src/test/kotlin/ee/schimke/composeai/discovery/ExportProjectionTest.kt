package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * A catalog's saved `wear-m3/button` written as the REAL Wear Material 3 call, from data alone: the
 * variant picks the composable and its colours factory, colours become theme roles or literals, an
 * unstyled button keeps the library's own colours and `enabled = true` is not written.
 */
class ExportProjectionTest {
  private fun parameter(
    name: String,
    type: String,
    typeFqn: String? = null,
    default: Boolean = false,
    slot: Boolean = false,
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.hasDefault = default
        b.composableSlot = slot
      }
      .build()

  private fun wearButton(name: String) =
    ComponentRecord.Builder(
        canonicalId = "catalog/androidx.wear.compose.material3.ButtonKt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.ButtonKt",
              callable = "androidx.wear.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.signatureKnown = true
        b.parameters =
          listOf(
            parameter("onClick", "() -> Unit", "kotlin.Function0"),
            parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
            parameter("enabled", "Boolean", "kotlin.Boolean", default = true),
            parameter(
              "colors",
              "ButtonColors",
              "androidx.wear.compose.material3.ButtonColors",
              default = true,
            ),
            parameter("label", "RowScope.() -> Unit", slot = true),
          )
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "$name(onClick = {})"
              c.imports = listOf("androidx.wear.compose.material3.$name")
            }
            .build()
      }
      .build()

  private val button = wearButton("Button")
  private val tonal = wearButton("FilledTonalButton")
  private val text =
    ComponentRecord.Builder(
        canonicalId = "catalog/androidx.wear.compose.material3.TextKt.Text",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.TextKt",
              callable = "androidx.wear.compose.material3.Text",
              name = "Text",
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.signatureKnown = true
        b.parameters = listOf(parameter("text", "String", "kotlin.String"))
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "Text(text = \"\")"
              c.imports = listOf("androidx.wear.compose.material3.Text")
            }
            .build()
      }
      .build()

  private val wearColor =
    ExportProjection.Codec.Color(
      schemeRootFqn = "androidx.wear.compose.material3.MaterialTheme",
      roles = mapOf("surface" to "surfaceContainer"),
    )

  private fun colors(factory: String) =
    ExportProjection.Value.Call(
      "androidx.wear.compose.material3.ButtonDefaults.$factory",
      "androidx.wear.compose.material3.ButtonColors",
      mapOf(
        "containerColor" to ExportProjection.Value.Property("containerColor", wearColor),
        "contentColor" to ExportProjection.Value.Property("contentColor", wearColor),
      ),
    )

  private fun common(factory: String) =
    mapOf(
      "enabled" to
        ExportProjection.Value.Property(
          "enabled",
          ExportProjection.Codec.Bool,
          omitWhen = JsonPrimitive(true),
        ),
      "colors" to colors(factory),
    )

  private val projection =
    ExportProjection(
      variantProperty = "variant",
      targets =
        listOf(
          ExportProjection.Target(button.canonicalId, common("buttonColors")),
          ExportProjection.Target(
            tonal.canonicalId,
            common("filledTonalButtonColors"),
            variants = setOf("filled-tonal"),
          ),
        ),
    )

  private fun source(properties: Map<String, kotlinx.serialization.json.JsonElement>): String {
    val lowered = projection.lower(properties) as ExportProjection.Lowered.Node
    val document =
      ScreenDocument(
        name = "Projected",
        root =
          ScreenNode(
            lowered.record,
            arguments = lowered.arguments,
            slots =
              mapOf(
                "label" to
                  listOf(ScreenNode(text.canonicalId, mapOf("text" to ScreenValue.Text("Start"))))
              ),
          ),
      )
    val result =
      ScreenGenerator.generate(
        document,
        ComponentRecordFile.Builder("catalog", "debug", listOf(button, tonal, text)).build(),
        expressionPackages = setOf("androidx.wear.compose", "androidx.compose.ui.graphics"),
      )
    assertThat(result).isInstanceOf(ScreenGenerator.Result.Emitted::class.java)
    return (result as ScreenGenerator.Result.Emitted).source
  }

  private fun wrapper(type: String, value: Any) = buildJsonObject {
    put("type", type)
    when (value) {
      is Boolean -> put("value", value)
      else -> put("value", value.toString())
    }
  }

  @Test
  fun `a styled tonal button is written as FilledTonalButton with its own colours factory`() {
    val source =
      source(
        mapOf(
          "variant" to wrapper("enum", "filled-tonal"),
          "enabled" to wrapper("bool", false),
          "containerColor" to wrapper("colorToken", "surface"),
          "contentColor" to wrapper("color", "#336699"),
        )
      )
    assertThat(source).contains("FilledTonalButton(")
    assertThat(source).contains("enabled = false")
    assertThat(source)
      .contains(
        "colors = ButtonDefaults.filledTonalButtonColors(containerColor = " +
          "MaterialTheme.colorScheme.surfaceContainer, contentColor = Color(0xFF336699))"
      )
    assertThat(source).contains("import androidx.wear.compose.material3.ButtonDefaults")
    assertThat(source).contains("Text(text = \"Start\")")
  }

  @Test
  fun `an unstyled default button keeps the library's colours and enabled default`() {
    val source = source(mapOf("enabled" to wrapper("bool", true)))
    assertThat(source).contains("Button(")
    assertThat(source).doesNotContain("FilledTonalButton")
    assertThat(source).doesNotContain("colors =")
    assertThat(source).doesNotContain("enabled =")
  }

  @Test
  fun `a malformed value is refused with where it came from`() {
    val lowered =
      projection.lower(mapOf("enabled" to wrapper("string", "maybe")))
        as ExportProjection.Lowered.Refused
    assertThat(lowered.reasons.single()).contains("enabled")
  }

  private val listPadding =
    ChainLink(
      "androidx.wear.compose.material3.minimumVerticalContentPadding",
      positional =
        listOf(
          ScreenValue.Reference(
            "androidx.wear.compose.material3.ButtonDefaults",
            listOf("minimumVerticalListContentPadding"),
            typeFqn = "androidx.compose.ui.unit.Dp",
          )
        ),
    )

  private val inList =
    ExportProjection.Context(
      parameters =
        mapOf(
          "shape" to
            ExportProjection.Value.Fixed(
              ScreenValue.Reference(
                "androidx.wear.compose.material3.ButtonDefaults",
                listOf("shape"),
                typeFqn = "androidx.compose.ui.graphics.Shape",
              )
            )
        ),
      modifier = listOf(listPadding),
    )

  private val listed =
    ExportProjection(
      listOf(
        ExportProjection.Target(
          button.canonicalId,
          common("buttonColors"),
          contexts = mapOf("list-item" to inList),
        )
      )
    )

  @Test
  fun `a role's context is written only when the node is placed in that role`() {
    val alone = listed.lower(emptyMap()) as ExportProjection.Lowered.Node
    assertThat(alone.arguments).doesNotContainKey("shape")
    assertThat(alone.modifier).isEmpty()

    val item = listed.lower(emptyMap(), role = "list-item") as ExportProjection.Lowered.Node
    assertThat(item.arguments.keys).containsExactly("shape")
    assertThat(item.modifier).containsExactly(listPadding)

    // A role this target says nothing about adds nothing.
    val boxed = listed.lower(emptyMap(), role = "container") as ExportProjection.Lowered.Node
    assertThat(boxed).isEqualTo(alone)
  }

  @Test
  fun `contexts round trip through the published spelling`() {
    val json = ExportProjectionJson.encode(listed)
    assertThat(json.toString()).contains("\"contexts\":{\"list-item\":")
    assertThat(ExportProjectionJson.decode(json).getOrThrow()).isEqualTo(listed)
    // A projection without contexts keeps its exact earlier spelling.
    assertThat(ExportProjectionJson.encode(projection).toString()).doesNotContain("contexts")
  }

  @Test
  fun `a context's owners are allowed, and its properties are read`() {
    val readsInList =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            button.canonicalId,
            emptyMap(),
            contexts =
              mapOf(
                "list-item" to
                  ExportProjection.Context(
                    mapOf(
                      "enabled" to
                        ExportProjection.Value.Property("listEnabled", ExportProjection.Codec.Bool)
                    ),
                    listOf(listPadding),
                  )
              ),
          )
        )
      )
    assertThat(readsInList.properties).containsExactly("listEnabled")
    assertThat(readsInList.qualifiedOwners)
      .containsAtLeast(
        "androidx.wear.compose.material3",
        "androidx.wear.compose.material3.ButtonDefaults",
      )
  }

  @Test
  fun `a scope member's scope is an owner the host must admit`() {
    val scope = "androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope"
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            button.canonicalId,
            emptyMap(),
            contexts =
              mapOf(
                "list-item" to
                  ExportProjection.Context(
                    modifier =
                      listOf(
                        ChainLink(
                          "$scope.minimumVerticalContentPadding",
                          positional = listOf(ScreenValue.Whole(4)),
                          receiverScopeFqn = scope,
                        )
                      )
                  )
              ),
          )
        )
      )
    // Never imported, but the generator still holds `$scope.minimumVerticalContentPadding` to the
    // allowlist, so a host that admitted only imported owners refused the row.
    assertThat(projection.qualifiedOwners).contains(scope)
  }

  @Test
  fun `a context must name a structural role and not rewrite what the target writes`() {
    fun target(role: String, context: ExportProjection.Context) = runCatching {
      ExportProjection.Target(
        button.canonicalId,
        mapOf("enabled" to ExportProjection.Value.Fixed(ScreenValue.Bool(false))),
        contexts = mapOf(role to context),
      )
    }
      .exceptionOrNull()
      ?.message

    assertThat(target("list-itme", inList)).contains("not one of")
    assertThat(
        target(
          "list-item",
          ExportProjection.Context(
            mapOf("enabled" to ExportProjection.Value.Fixed(ScreenValue.Bool(true)))
          ),
        )
      )
      .contains("rewrites [enabled]")
    assertThat(
        target(
          "list-item",
          ExportProjection.Context(
            mapOf(
              "modifier" to
                ExportProjection.Value.Fixed(
                  ScreenValue.Reference(
                    "androidx.compose.ui.Modifier",
                    typeFqn = "androidx.compose.ui.Modifier",
                  )
                )
            ),
            listOf(listPadding),
          ),
        )
      )
      .contains("both writes `modifier` and appends to it")
  }

  /** A child as a host sees it: its catalog id, and its lines when it is a plain column. */
  private data class Child(val id: String, val column: List<Child>? = null)

  private fun text(line: String) = Child("wear-m3/text:$line")

  private val icon = Child("wear-m3/icon")

  private fun lines(vararg texts: String) = Child("layout/column", texts.map(::text))

  private val buttonPatterns =
    ExportProjection.SlotPatterns(
      listOf(
        ExportProjection.SlotPattern(
          listOf(
            ExportProjection.Matcher.Component("wear-m3/icon", "icon", optional = true),
            ExportProjection.Matcher.Lines(
              "wear-m3/text",
              listOf("label", "secondaryLabel"),
              max = 2,
            ),
          )
        )
      ),
      otherwise = "content",
    )

  private fun patterned(patterns: ExportProjection.SlotPatterns) =
    ExportProjection(
      listOf(
        ExportProjection.Target(
          button.canonicalId,
          emptyMap(),
          slotPatterns = mapOf("content" to patterns),
        )
      )
    )

  private fun assign(patterns: ExportProjection.SlotPatterns, vararg children: Child) =
    patterned(patterns)
      .assignSlot(
        button.canonicalId,
        "content",
        children.toList(),
        componentId = { it.id.substringBefore(':') },
        plainColumn = { it.column },
      )

  private fun assigned(patterns: ExportProjection.SlotPatterns, vararg children: Child) =
    (assign(patterns, *children) as ExportProjection.SlotAssignment.Assigned).parameters

  @Test
  fun `a slot's children choose the parameters they fill, as Wear's button does`() {
    assertThat(assigned(buttonPatterns, icon, text("Go")))
      .containsExactly("icon", listOf(icon), "label", listOf(text("Go")))
      .inOrder()
    // A plain column of texts is its lines, and the column itself disappears.
    assertThat(assigned(buttonPatterns, lines("Go", "Now")))
      .containsExactly("label", listOf(text("Go")), "secondaryLabel", listOf(text("Now")))
      .inOrder()
    // Anything else is the content overload's.
    val image = Child("wear-m3/image")
    assertThat(assigned(buttonPatterns, image)).containsExactly("content", listOf(image))
    assertThat(assigned(buttonPatterns, lines("a", "b", "c"))).containsKey("content")
    assertThat(assigned(buttonPatterns, icon, text("Go"), text("Again"))).containsKey("content")
  }

  @Test
  fun `a column with anything but text is not lines`() {
    val mixed = Child("layout/column", listOf(text("Go"), icon))
    assertThat(assigned(buttonPatterns, mixed)).containsExactly("content", listOf(mixed))
    // And a column the host does not judge plain (null) is not lines either.
    val styled = Child("layout/column")
    assertThat(assigned(buttonPatterns, styled)).containsExactly("content", listOf(styled))
  }

  @Test
  fun `extra lines go to rest, as a card's trailing content does`() {
    val card =
      ExportProjection.SlotPatterns(
        listOf(
          ExportProjection.SlotPattern(
            listOf(
              ExportProjection.Matcher.Lines(
                "wear-m3/text",
                listOf("appName", "title"),
                min = 2,
                rest = "content",
              )
            )
          )
        )
      )
    assertThat(assigned(card, lines("App", "Title", "Body", "More")))
      .containsExactly(
        "appName",
        listOf(text("App")),
        "title",
        listOf(text("Title")),
        "content",
        listOf(text("Body"), text("More")),
      )
      .inOrder()
    // Without `otherwise` an unmatched slot refuses the node, naming what it held.
    val refused = assign(card, text("Only")) as ExportProjection.SlotAssignment.Refused
    assertThat(refused.reason)
      .contains("no pattern of `content` matches its children [wear-m3/text]")
  }

  @Test
  fun `any takes the remaining children, at least one`() {
    val leading =
      ExportProjection.SlotPatterns(
        listOf(
          ExportProjection.SlotPattern(
            listOf(
              ExportProjection.Matcher.Component("wear-m3/icon", "icon"),
              ExportProjection.Matcher.Rest("content"),
            )
          )
        )
      )
    assertThat(assigned(leading, icon, text("a"), text("b")))
      .containsExactly("icon", listOf(icon), "content", listOf(text("a"), text("b")))
    assertThat(assign(leading, icon))
      .isInstanceOf(ExportProjection.SlotAssignment.Refused::class.java)
  }

  @Test
  fun `a slot without patterns is its plain rename`() {
    val plain =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            button.canonicalId,
            emptyMap(),
            slots = mapOf("content" to "label"),
          )
        )
      )
    val result =
      plain.assignSlot(button.canonicalId, "content", listOf(icon), { it.id }, { it.column })
    assertThat((result as ExportProjection.SlotAssignment.Assigned).parameters)
      .containsExactly("label", listOf(icon))
  }

  @Test
  fun `slot patterns round trip through the published spelling`() {
    val projection = patterned(buttonPatterns)
    val json = ExportProjectionJson.encode(projection)
    assertThat(json.toString()).contains("\"slotPatterns\":{\"content\":")
    assertThat(ExportProjectionJson.decode(json).getOrThrow()).isEqualTo(projection)
    val refusing = patterned(buttonPatterns.copy(otherwise = null))
    val refusingJson = ExportProjectionJson.encode(refusing)
    assertThat(refusingJson.toString()).contains("\"otherwise\":\"refuse\"")
    assertThat(ExportProjectionJson.decode(refusingJson).getOrThrow()).isEqualTo(refusing)
  }

  @Test
  fun `slot patterns are checked when they are built`() {
    fun failure(build: () -> Any) = runCatching(build).exceptionOrNull()?.message
    assertThat(
        failure {
          ExportProjection.SlotPattern(
            listOf(
              ExportProjection.Matcher.Rest("content"),
              ExportProjection.Matcher.Component("wear-m3/icon", "icon"),
            )
          )
        }
      )
      .contains("last matcher")
    assertThat(
        failure {
          ExportProjection.SlotPattern(
            listOf(
              ExportProjection.Matcher.Component("wear-m3/icon", "label"),
              ExportProjection.Matcher.Lines("wear-m3/text", listOf("label")),
            )
          )
        }
      )
      .contains("each parameter once")
    assertThat(failure { ExportProjection.Matcher.Lines("wear-m3/text", listOf("label"), max = 3) })
      .contains("need a `rest` parameter")
    assertThat(
        failure {
          ExportProjection.Target(
            button.canonicalId,
            emptyMap(),
            slots = mapOf("content" to "label"),
            slotPatterns = mapOf("content" to buttonPatterns),
          )
        }
      )
      .contains("renamed and recognised")
  }

  private val header =
    ExportProjection(
      listOf(
        ExportProjection.Target(
          "catalog/androidx.wear.compose.material3.ListHeaderKt.ListHeader",
          emptyMap(),
          synthesize =
            mapOf("content" to ExportProjection.Synthesis("wear-m3/text", mapOf("text" to "text"))),
        )
      )
    )

  @Test
  fun `a property written as a slot synthesizes a child of another component`() {
    val lowered =
      header.lower(mapOf("text" to wrapper("string", "Settings"))) as ExportProjection.Lowered.Node
    assertThat(lowered.arguments).isEmpty()
    assertThat(lowered.synthesized)
      .containsExactly(
        "content",
        ExportProjection.Lowered.Synthesized(
          "wear-m3/text",
          mapOf("text" to wrapper("string", "Settings")),
        ),
      )
    // Unset, the child is still synthesized, so a required slot is always written; its own
    // component decides what an unset property means.
    val unset = header.lower(emptyMap()) as ExportProjection.Lowered.Node
    assertThat(unset.synthesized.getValue("content").properties).isEmpty()
    assertThat(header.properties).containsExactly("text")
  }

  @Test
  fun `synthesized children round trip, and are never also written`() {
    val json = ExportProjectionJson.encode(header)
    assertThat(json.toString()).contains("\"synthesize\":{\"content\":")
    assertThat(ExportProjectionJson.decode(json).getOrThrow()).isEqualTo(header)
    val both = runCatching {
      ExportProjection.Target(
        "r",
        mapOf("content" to ExportProjection.Value.Fixed(ScreenValue.Text(""))),
        synthesize = mapOf("content" to ExportProjection.Synthesis("t", mapOf("text" to "text"))),
      )
    }
      .exceptionOrNull()
    assertThat(both?.message).contains("both written and synthesized")
  }

  @Test
  fun `a DSL slot's item member lowers and round trips, and must be an identifier`() {
    val list =
      ExportProjection(
        listOf(
          ExportProjection.Target("catalog/List", emptyMap(), items = mapOf("content" to "item"))
        )
      )
    assertThat((list.lower(emptyMap()) as ExportProjection.Lowered.Node).items)
      .containsExactly("content", "item")
    val json = ExportProjectionJson.encode(list)
    assertThat(json.toString()).contains("\"items\":{\"content\":\"item\"}")
    assertThat(ExportProjectionJson.decode(json).getOrThrow()).isEqualTo(list)
    val bad = runCatching {
      ExportProjection.Target("r", emptyMap(), items = mapOf("content" to "item(key = 1)"))
    }
    assertThat(bad.exceptionOrNull()?.message).contains("is not a Kotlin identifier")
    val written = runCatching {
      ExportProjection.Target(
        "r",
        mapOf("content" to ExportProjection.Value.Fixed(ScreenValue.Text(""))),
        items = mapOf("content" to "item"),
      )
    }
    assertThat(written.exceptionOrNull()?.message).contains("both a DSL slot and written")
  }

  @Test
  fun `a property only another target writes is refused, unless unset`() {
    val container = ExportProjection.Value.Property("containerColor", ExportProjection.Codec.Text)
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target("catalog/Button", mapOf("containerColor" to container)),
          ExportProjection.Target(
            "catalog/OutlinedButton",
            emptyMap(),
            variants = setOf("outlined"),
          ),
        ),
        variantProperty = "variant",
      )
    val outlined = JsonPrimitive("outlined")
    val refused =
      projection.lower(mapOf("variant" to outlined, "containerColor" to JsonPrimitive("primary")))
        as ExportProjection.Lowered.Refused
    assertThat(refused.reasons.single())
      .isEqualTo("containerColor: not written by catalog/OutlinedButton")
    // The seeded empty default and an explicit null are unset, so nothing is dropped.
    assertThat(
        projection.lower(mapOf("variant" to outlined, "containerColor" to JsonPrimitive("")))
      )
      .isInstanceOf(ExportProjection.Lowered.Node::class.java)
    assertThat(projection.lower(mapOf("variant" to outlined, "containerColor" to JsonNull)))
      .isInstanceOf(ExportProjection.Lowered.Node::class.java)
    // A property no target writes is the host's to refuse, not the projection's.
    assertThat(projection.lower(mapOf("variant" to outlined, "other" to JsonPrimitive("x"))))
      .isInstanceOf(ExportProjection.Lowered.Node::class.java)
  }

  @Test
  fun `an item member names the role it opens, per target, and round trips`() {
    val list =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "catalog/List",
            emptyMap(),
            items = mapOf("content" to "item"),
            itemRoles = mapOf("content" to "list-item"),
          ),
          ExportProjection.Target(
            "catalog/List",
            emptyMap(),
            variants = setOf("none"),
            items = mapOf("content" to "item"),
          ),
        ),
        variantProperty = "transformation",
      )
    val spec = list.lower(emptyMap()) as ExportProjection.Lowered.Node
    assertThat(spec.itemRoles).containsExactly("content", "list-item")
    val none =
      list.lower(mapOf("transformation" to JsonPrimitive("none"))) as ExportProjection.Lowered.Node
    assertThat(none.items).containsExactly("content", "item")
    assertThat(none.itemRoles).isEmpty()
    val json = ExportProjectionJson.encode(list)
    assertThat(json.toString()).contains("\"itemRoles\":{\"content\":\"list-item\"}")
    assertThat(ExportProjectionJson.decode(json).getOrThrow()).isEqualTo(list)
    fun failure(build: () -> Any) = runCatching(build).exceptionOrNull()?.message
    assertThat(
        failure {
          ExportProjection.Target("r", emptyMap(), itemRoles = mapOf("content" to "list-item"))
        }
      )
      .contains("has an item role but no item member")
    assertThat(
        failure {
          ExportProjection.Target(
            "r",
            emptyMap(),
            items = mapOf("content" to "item"),
            itemRoles = mapOf("content" to "row"),
          )
        }
      )
      .contains("is not one of")
  }

  @Test
  fun `a Material icon key is written from the host's icon table, and refused without one`() {
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "wear/androidx.wear.compose.material3.IconKt.Icon",
            mapOf(
              "imageVector" to
                ExportProjection.Value.Property("iconKey", ExportProjection.Codec.MaterialIcon)
            ),
          )
        )
      )
    val vector =
      ScreenValue.Reference(
        "androidx.compose.material.icons.Icons",
        listOf("Filled"),
        typeFqn = "androidx.compose.ui.graphics.vector.ImageVector",
      )
    val table = mapOf("filled/newReleases" to vector)
    val key = mapOf("iconKey" to JsonPrimitive("filled/newReleases"))
    val node = projection.lower(key, null, table::get) as ExportProjection.Lowered.Node
    assertThat(node.arguments).containsExactly("imageVector", vector)
    assertThat((projection.lower(key) as ExportProjection.Lowered.Refused).reasons.single())
      .contains("this host writes none")
    assertThat(
        (projection.lower(mapOf("iconKey" to JsonPrimitive("filled/nope")), null, table::get)
            as ExportProjection.Lowered.Refused)
          .reasons
          .single()
      )
      .contains("is not a Material icon key")
    val json = ExportProjectionJson.encode(projection)
    assertThat(json.toString()).contains("\"codec\":\"materialIcon\"")
    assertThat(ExportProjectionJson.decode(json).getOrThrow()).isEqualTo(projection)
  }

  @Test
  fun `a modifier link is written from properties, skipped unset, refused half set`() {
    val dp = ExportProjection.Codec.Unit("dp", positive = true)
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "wear/androidx.wear.compose.material3.IconKt.Icon",
            emptyMap(),
            modifier =
              listOf(
                ExportProjection.ModifierLink(
                  "androidx.compose.foundation.layout.size",
                  listOf(ExportProjection.Value.Property("sizeDp", dp)),
                ),
                ExportProjection.ModifierLink(
                  "androidx.compose.foundation.layout.offset",
                  listOf(
                    ExportProjection.Value.Property("x", dp),
                    ExportProjection.Value.Property("y", dp),
                  ),
                ),
              ),
          )
        )
      )
    val sized =
      projection.lower(mapOf("sizeDp" to JsonPrimitive(18))) as ExportProjection.Lowered.Node
    val size = sized.modifier.single()
    assertThat(size.callableFqn).isEqualTo("androidx.compose.foundation.layout.size")
    assertThat((size.positional.single() as ScreenValue.Chain).links.single().callableFqn)
      .isEqualTo("androidx.compose.ui.unit.dp")
    assertThat((projection.lower(emptyMap()) as ExportProjection.Lowered.Node).modifier).isEmpty()
    assertThat(
        (projection.lower(mapOf("x" to JsonPrimitive(2))) as ExportProjection.Lowered.Refused)
          .reasons
          .single()
      )
      .contains("only some of which are set")
    assertThat(projection.properties).containsExactly("sizeDp", "x", "y")
    assertThat(projection.qualifiedOwners).contains("androidx.compose.foundation.layout.size")
    assertThat(ExportProjectionJson.decode(ExportProjectionJson.encode(projection)).getOrThrow())
      .isEqualTo(projection)
  }

  @Test
  fun `a parameter is filled once, whether by a pattern, a line or a synthesized child`() {
    fun failure(build: () -> Any) = runCatching(build).exceptionOrNull()?.message
    assertThat(
        failure {
          ExportProjection.Target(
            button.canonicalId,
            emptyMap(),
            slotPatterns = mapOf("content" to buttonPatterns),
            synthesize =
              mapOf("label" to ExportProjection.Synthesis("wear-m3/text", mapOf("text" to "text"))),
          )
        }
      )
      .contains("both filled from a wire slot and synthesized")
    assertThat(
        failure {
          ExportProjection.Matcher.Lines("wear-m3/text", listOf("label", "label"), min = 2)
        }
      )
      .contains("distinct parameters")
    assertThat(
        failure { ExportProjection.Matcher.Lines("wear-m3/text", listOf("label"), rest = "label") }
      )
      .contains("also a line's parameter")
  }

  @Test
  fun `a rest needed to reach the minimum is always filled, and otherwise optional`() {
    // Wear's `AppCard`: an app name, a title and a required content of the lines after them.
    assertThat(
        ExportProjection.Matcher.Lines(
            "wear-m3/text",
            listOf("appName", "title"),
            min = 3,
            rest = "content",
          )
          .parameters
      )
      .containsExactly("appName", true, "title", true, "content", true)
    assertThat(
        ExportProjection.Matcher.Lines("wear-m3/text", listOf("title"), rest = "content").parameters
      )
      .containsExactly("title", true, "content", false)
  }

  private val inbox =
    ComponentRecord.Builder(
        canonicalId = "app/com.example.InboxKt.Inbox",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "com.example.InboxKt",
              callable = "com.example.Inbox",
              name = "Inbox",
              origin = ComponentOrigin.PROJECT,
            )
            .build(),
      )
      .also { b ->
        b.signatureKnown = true
        b.parameters =
          listOf(
            parameter("contacts", "List<Contact>", "kotlin.collections.List"),
            parameter("profile", "Profile", "com.example.Profile", default = true),
          )
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "Inbox(contacts = listOf())"
              c.imports = listOf("com.example.Inbox")
            }
            .build()
      }
      .build()

  private val contactCodec =
    ExportProjection.Codec.Object(
      "com.example.Contact",
      mapOf(
        "name" to ExportProjection.Codec.Field(ExportProjection.Codec.Text),
        "unread" to
          ExportProjection.Codec.Field(
            ExportProjection.Codec.Whole,
            default = json("""{"type":"int","value":0}""").jsonObject,
          ),
      ),
    )

  private val inboxProjection =
    ExportProjection(
      listOf(
        ExportProjection.Target(
          inbox.canonicalId,
          mapOf(
            "contacts" to
              ExportProjection.Value.Property(
                "contacts",
                ExportProjection.Codec.ListOf(contactCodec),
              ),
            "profile" to
              ExportProjection.Value.Property(
                "profile",
                ExportProjection.Codec.Object(
                  "com.example.Profile",
                  mapOf(
                    "owner" to ExportProjection.Codec.Field(contactCodec),
                    "tags" to
                      ExportProjection.Codec.Field(
                        ExportProjection.Codec.ListOf(ExportProjection.Codec.Text),
                        default = json("""{"type":"list","values":[]}""").jsonObject,
                      ),
                    // A catalog codec stored under its own wrapper type.
                    "accent" to
                      ExportProjection.Codec.Field(
                        wearColor,
                        literalType = "colorToken",
                        default = json("""{"type":"colorToken","value":"primary"}""").jsonObject,
                      ),
                  ),
                  callableFqn = "com.example.ProfileDefaults.profile",
                ),
              ),
          ),
        )
      )
    )

  private fun json(text: String) = Json.parseToJsonElement(text)

  private fun contact(name: String, unread: Int) =
    ScreenValue.Construct(
      "com.example.Contact",
      named =
        mapOf("name" to ScreenValue.Text(name), "unread" to ScreenValue.Whole(unread.toLong())),
      typeFqn = "com.example.Contact",
    )

  private val ada =
    """{"type":"object","fields":{"name":{"type":"string","value":"Ada"},"unread":{"type":"int","value":2}}}"""

  private fun refusals(properties: Map<String, kotlinx.serialization.json.JsonElement>) =
    (inboxProjection.lower(properties) as ExportProjection.Lowered.Refused).reasons

  private fun profile(fields: String) =
    mapOf("profile" to json("""{"type":"object","fields":{$fields}}"""))

  private val at = "app/com.example.InboxKt.Inbox"

  @Test
  fun `a list property is written as listOf and an object as a named-argument call`() {
    val lowered =
      inboxProjection.lower(
        mapOf(
          "contacts" to
            json(
              """{"type":"list","values":[$ada,
                {"type":"object","fields":{"name":{"type":"string","value":"Bo"}}}]}"""
            ),
          "profile" to json("""{"type":"object","fields":{"owner":$ada}}"""),
        )
      ) as ExportProjection.Lowered.Node
    // Bo's omitted `unread` and the profile's omitted `tags` and `accent` write their declared
    // defaults: the constructor need not default them.
    assertThat(lowered.arguments["contacts"])
      .isEqualTo(
        ScreenValue.Construct(
          "kotlin.collections.listOf",
          positional = listOf(contact("Ada", 2), contact("Bo", 0)),
          typeFqn = "kotlin.collections.List",
        )
      )
    assertThat(lowered.arguments["profile"])
      .isEqualTo(
        ScreenValue.Construct(
          "com.example.ProfileDefaults.profile",
          named =
            mapOf(
              "owner" to contact("Ada", 2),
              "tags" to
                ScreenValue.Construct(
                  "kotlin.collections.listOf",
                  typeFqn = "kotlin.collections.List",
                ),
              "accent" to
                ScreenValue.Reference(
                  "androidx.wear.compose.material3.MaterialTheme",
                  listOf("colorScheme", "primary"),
                  typeFqn = "androidx.compose.ui.graphics.Color",
                ),
            ),
          typeFqn = "com.example.Profile",
        )
      )
    assertThat(inboxProjection.qualifiedOwners)
      .containsAtLeast("kotlin.collections", "com.example", "com.example.ProfileDefaults")

    val result =
      ScreenGenerator.generate(
        ScreenDocument(
          name = "Projected",
          root = ScreenNode(lowered.record, arguments = lowered.arguments),
        ),
        ComponentRecordFile.Builder("app", "debug", listOf(inbox)).build(),
        expressionPackages =
          setOf(
            "kotlin.collections",
            "com.example",
            "androidx.wear.compose",
            "androidx.compose.ui.graphics",
          ),
      )
    assertThat(result).isInstanceOf(ScreenGenerator.Result.Emitted::class.java)
    val source = (result as ScreenGenerator.Result.Emitted).source
    assertThat(source)
      .contains(
        "contacts = listOf(Contact(name = \"Ada\", unread = 2), Contact(name = \"Bo\", unread = 0))"
      )
    assertThat(source)
      .contains(
        "profile = ProfileDefaults.profile(owner = Contact(name = \"Ada\", unread = 2), " +
          "tags = listOf(), accent = MaterialTheme.colorScheme.primary)"
      )
    assertThat(source).contains("import kotlin.collections.listOf")
    assertThat(source).contains("import com.example.Contact")
  }

  @Test
  fun `an omitted required field is refused rather than left to the constructor`() {
    assertThat(
        refusals(
          mapOf(
            "contacts" to
              json(
                """{"type":"list","values":[{"type":"object","fields":{"unread":{"type":"int","value":1}}}]}"""
              )
          )
        )
      )
      .containsExactly("$at.contacts <- contacts[0].name: required field is missing")
    assertThat(refusals(profile("")))
      .containsExactly("$at.profile <- profile.owner: required field is missing")
  }

  @Test
  fun `a malformed list element or object field is refused with its path`() {
    assertThat(
        refusals(
          mapOf(
            "contacts" to
              json(
                """{"type":"list","values":[{"type":"object","fields":{"name":{"type":"string","value":3}}}]}"""
              )
          )
        )
      )
      .containsExactly("$at.contacts <- contacts[0].name: expected text")
    assertThat(refusals(profile(""""owner":$ada,"nickname":{"type":"string","value":"x"}""")))
      .containsExactly("$at.profile <- profile: com.example.Profile has no field [nickname]")
    assertThat(refusals(mapOf("contacts" to wrapper("string", "Ada"))).single())
      .contains("expected a {\"type\": \"list\"")
    assertThat(refusals(profile(""""owner":$ada,"accent":{"type":"colorToken","value":""}""")))
      .containsExactly("$at.profile <- profile.accent: an empty value cannot be written here")
    // Absent, a list or object property is simply not written.
    val unset = inboxProjection.lower(emptyMap()) as ExportProjection.Lowered.Node
    assertThat(unset.arguments).isEmpty()
  }

  @Test
  fun `a nested scalar must be its codec's own literal wrapper, as unwrap requires`() {
    fun tags(values: String) =
      refusals(profile(""""owner":$ada,"tags":{"type":"list","values":[$values]}"""))
    assertThat(tags(""""x""""))
      .containsExactly("$at.profile <- profile.tags[0]: expected a `string` wrapper, found \"x\"")
    assertThat(tags("""{"type":"int","value":"x"}"""))
      .containsExactly("$at.profile <- profile.tags[0]: expected a `string` wrapper, found `int`")
    assertThat(tags("""{"value":"x"}"""))
      .containsExactly(
        "$at.profile <- profile.tags[0]: expected a `string` wrapper, found {\"value\":\"x\"}"
      )
    assertThat(tags("""{"type":"string"}"""))
      .containsExactly("$at.profile <- profile.tags[0]: a `string` wrapper has no value")
    assertThat(tags("""{"type":"string","value":{"nested":true}}"""))
      .containsExactly("$at.profile <- profile.tags[0]: a `string` wrapper has no value")
    // A field stored under its catalog's own wrapper type is held to that type.
    assertThat(refusals(profile(""""owner":$ada,"accent":{"type":"string","value":"primary"}""")))
      .containsExactly(
        "$at.profile <- profile.accent: expected a `colorToken` wrapper, found `string`"
      )
    assertThat(refusals(profile(""""owner":{"type":"object","fields":{"name":"Ada"}}""")))
      .containsExactly(
        "$at.profile <- profile.owner.name: expected a `string` wrapper, found \"Ada\""
      )
    // At the top level a scalar property stays as lenient as it always was.
    assertThat(projection.lower(mapOf("enabled" to JsonPrimitive(false))))
      .isInstanceOf(ExportProjection.Lowered.Node::class.java)
  }

  @Test
  fun `list and object codecs round trip through the published spelling`() {
    val encoded = ExportProjectionJson.encode(inboxProjection)
    assertThat(ExportProjectionJson.decode(encoded).getOrThrow()).isEqualTo(inboxProjection)
    val parameters = encoded["targets"]!!.jsonArray.single().jsonObject["parameters"]!!.jsonObject
    assertThat(parameters["contacts"])
      .isEqualTo(
        json(
          """{"property":"contacts","codec":{"list":{"element":{"object":{
            "type":"com.example.Contact","fields":{"name":"text",
            "unread":{"codec":"whole","default":{"type":"int","value":0}}}}}}}}"""
        )
      )
    val accent =
      parameters["profile"]!!
        .jsonObject["codec"]!!
        .jsonObject["object"]!!
        .jsonObject["fields"]!!
        .jsonObject["accent"]!!
        .jsonObject
    assertThat(accent["literalType"]).isEqualTo(JsonPrimitive("colorToken"))
    // The first spelling, a field as its bare codec, still reads: required, default wrapper type.
    assertThat(
        ExportProjectionJson.decode(
            json(
              """{"targets":[{"record":"$at","parameters":{"contacts":{"property":"contacts",
                "codec":{"list":{"element":{"object":{"type":"com.example.Contact",
                "fields":{"name":"text"}}}}}}}}]}"""
            )
          )
          .getOrThrow()
          .targets
          .single()
          .parameters["contacts"]
      )
      .isEqualTo(
        ExportProjection.Value.Property(
          "contacts",
          ExportProjection.Codec.ListOf(
            ExportProjection.Codec.Object(
              "com.example.Contact",
              mapOf("name" to ExportProjection.Codec.Field(ExportProjection.Codec.Text, "string")),
            ),
            "object",
          ),
        )
      )
    // The scalar spellings are unchanged.
    assertThat(ExportProjectionJson.encode(projection).toString()).contains("\"codec\":\"bool\"")
    val failure = runCatching {
      ExportProjection.Value.Property(
        "contacts",
        ExportProjection.Codec.ListOf(contactCodec),
        omitWhen = JsonPrimitive(true),
      )
    }
      .exceptionOrNull()
    assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
  }
}
