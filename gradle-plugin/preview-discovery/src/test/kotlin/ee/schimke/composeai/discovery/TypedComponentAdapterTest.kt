package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import io.github.classgraph.ClassGraph
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class TypedComponentAdapterTest {
  private data class Props(val label: String, val enabled: Boolean, val onClick: () -> Unit)

  private class ButtonAdapter(component: ComponentRecord) :
    TypedComponentAdapter<Props>("acme/button", component) {
    val label = property(Props::label, AdapterValueCodecs.String, "Button")
    val enabled = property(Props::enabled, AdapterValueCodecs.Boolean, true)
    val click = event(Props::onClick)
  }

  private fun parameter(name: String, type: String, fqn: String? = null) =
    TargetParameter.Builder(name, type)
      .also {
        it.typeFqn = fqn
        if (fqn?.startsWith("kotlin.Function") == true) it.lambdaReturnTypeFqn = "kotlin.Unit"
      }
      .build()

  private fun component(name: String = "BrandButton") =
    ComponentRecord.Builder(
        canonicalId = ":app/acme.${name}Kt.$name",
        symbol =
          ComponentSymbol.Builder("acme.${name}Kt", "acme.$name", name, ComponentOrigin.PROJECT)
            .build(),
      )
      .also {
        it.signatureKnown = true
        it.parameters =
          listOf(
            parameter("label", "String", "kotlin.String"),
            parameter("enabled", "Boolean", "kotlin.Boolean"),
            parameter("onClick", "() -> Unit", "kotlin.Function0"),
          )
      }
      .build()

  private fun generate(
    adapter: TypedComponentAdapter<*> = ButtonAdapter(component()),
    discovered: List<ComponentRecord> = listOf(component(), component("PrivateComponent")),
    policy: UiBuilderPolicyFile =
      UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
  ) =
    TypedAdapterCatalog.generate(
      ComponentRecordFile.Builder(":app", "desktop", discovered).build(),
      UiBuilderCatalogs.CoverSheet("acme", "Acme app"),
      policy,
      listOf(adapter),
    )

  @Test
  fun `additional validation reads the actual compiled component metadata`() {
    ClassGraph()
      .enableClasspath()
      .enableClassInfo()
      .enableMethodInfo()
      .ignoreMethodVisibility()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val owner = scan.getClassInfo("ee.schimke.composeai.discovery.TypedComponentAdapterTestKt")
        val method = owner.methodInfo.single { it.name == "typedAdapterFixture" }
        val record =
          component()
            .newBuilder()
            .also { it.parameters = ComposableSignature.parametersOf(owner, method) }
            .build()
        val adapter = ButtonAdapter(record)
        assertThat(adapter.validateAgainst(record)).isEmpty()
        assertThat(generate(adapter, listOf(record)).catalog.diagnostics).isEmpty()
      }
  }

  @Test
  fun `one definition generates standard paired artifacts and only opted in components`() {
    val generated = generate()
    assertThat(generated.record.components.map { it.symbol.name }).containsExactly("BrandButton")
    assertThat(generated.catalog.diagnostics).isEmpty()
    val published = generated.catalog.statusSemantics.components.getValue("acme/button")
    assertThat(published.canvas).isEqualTo("acme/button")
    assertThat(published.record).isEqualTo(generated.record.components.single().canonicalId)
    assertThat(
        published.insertContent!!
          .jsonObject["properties"]!!
          .jsonObject["enabled"]!!
          .jsonObject["type"]
      )
      .isEqualTo(JsonPrimitive("bool"))
    assertThat(published.propertyCapabilities!!.map { it.jsonObject["name"] })
      .containsExactly(JsonPrimitive("label"), JsonPrimitive("enabled"))
    assertThat(
        published.insertContent!!
          .jsonObject["properties"]!!
          .jsonObject["label"]!!
          .jsonObject["value"]
      )
      .isEqualTo(JsonPrimitive("Button"))
    val json = Json { encodeDefaults = true }
    assertThat(
        json.decodeFromString(
          ComponentRecordFile.serializer(),
          json.encodeToString(ComponentRecordFile.serializer(), generated.record),
        )
      )
      .isEqualTo(generated.record)
    assertThat(
        json.decodeFromString(
          UiBuilderCatalogFile.serializer(),
          json.encodeToString(UiBuilderCatalogFile.serializer(), generated.catalog),
        )
      )
      .isEqualTo(generated.catalog)
  }

  @Test
  fun `screen root builtins survive while authored component overrides are refused`() {
    val root = UiBuilderBuiltin.Builder(role = "screen-root").build()
    val policy =
      UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "wear")
        .also { it.builtins = mapOf("wear-m3/screen-scaffold" to root) }
        .build()
    val generated = generate(policy = policy)
    assertThat(generated.catalog.statusSemantics.builtins)
      .containsEntry("wear-m3/screen-scaffold", root)
    assertThat(generated.record.components).hasSize(1)
    assertThrows(IllegalArgumentException::class.java) {
      generate(
        policy =
          policy
            .newBuilder()
            .also {
              it.components = mapOf("acme/button" to UiBuilderAuthoredComponent.Builder().build())
            }
            .build()
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      generate(
        policy = policy.newBuilder().also { it.builtins = mapOf("acme/button" to root) }.build()
      )
    }
  }

  private data class CountProps(val count: Int, val onCountChange: (Int) -> Unit)

  @Test
  fun `integer properties advertise literals only until state supports integer constraints`() {
    val record =
      component()
        .newBuilder()
        .also { it.parameters = listOf(parameter("count", "Int", "kotlin.Int")) }
        .build()
    val adapter =
      object : TypedComponentAdapter<CountProps>("acme/count", record) {
        val count = property(CountProps::count, AdapterValueCodecs.Int, 3)
      }
    val generated = generate(adapter, listOf(record))
    assertThat(
        generated.catalog.statusSemantics.components
          .getValue("acme/count")
          .insertContent!!
          .jsonObject["properties"]!!
          .jsonObject["count"]!!
          .jsonObject["type"]
      )
      .isEqualTo(JsonPrimitive("int"))
    assertThat(adapter.count.bindable).isFalse()
    assertThat(
        generated.catalog.statusSemantics.components
          .getValue("acme/count")
          .propertyCapabilities!!
          .single()
          .jsonObject["jsonType"]
      )
      .isEqualTo(JsonPrimitive("integer"))
    assertThat(generated.record.components.single().builder!!.stateCallbacks).isEmpty()
    assertThrows(IllegalArgumentException::class.java) {
      object : TypedComponentAdapter<CountProps>("acme/count", record) {
        val count = property(CountProps::count, AdapterValueCodecs.Int, 3, bindable = true)
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      object : TypedComponentAdapter<CountProps>("acme/count", record) {
        val count = property(CountProps::count, AdapterValueCodecs.Int, 3)
        val change = stateChange(CountProps::onCountChange, count)
      }
    }
  }

  private data class EventProps(
    val onDismiss: () -> Unit,
    val dismiss: () -> Unit,
    val on: () -> Unit,
  )

  @Test
  fun `blank and colliding wire event names are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      object : TypedComponentAdapter<EventProps>("acme/events", component()) {
        val first = event(EventProps::onDismiss)
        val second = event(EventProps::dismiss)
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      object : TypedComponentAdapter<EventProps>("acme/events", component()) {
        val blank = event(EventProps::on)
      }
    }
  }

  @Test
  fun `callback validation requires structural metadata and preserves qualified tokens`() {
    val original = component()
    val adapter = ButtonAdapter(original)
    for (bad in
      listOf(
        parameter("onClick", "() -> kotlin.kotlin.Unit", "kotlin.Function0"),
        parameter("onClick", "() -> kotlinx.Unit", "kotlin.Function0"),
        parameter("onClick", "() -> Unit", "acme.Function0"),
        parameter("onClick", "() -> Unit", "kotlin.Function0")
          .newBuilder()
          .also { it.lambdaReturnTypeFqn = "acme.Unit" }
          .build(),
        parameter("onClick", "() -> Unit", "kotlin.Function0")
          .newBuilder()
          .also { it.composableSlot = true }
          .build(),
        parameter("onClick", "(() -> Unit)?", "kotlin.Function0")
          .newBuilder()
          .also {
            it.nullable = true
            it.composableSlot = true
          }
          .build(),
      )) {
      val changed =
        original
          .newBuilder()
          .also { b ->
            b.parameters = original.parameters.map { if (it.name == "onClick") bad else it }
          }
          .build()
      assertThat(adapter.validateAgainst(changed))
        .contains("acme/button: onClick is not a () -> Unit callback")
    }
    val qualified =
      original
        .newBuilder()
        .also { b ->
          b.parameters =
            original.parameters.map {
              if (it.name == "onClick")
                parameter("onClick", "() -> kotlin.Unit", "kotlin.Function0")
              else it
            }
        }
        .build()
    assertThat(adapter.validateAgainst(qualified)).isEmpty()
    // An optional handler (`onRetry: (() -> Unit)?`) is filled with the design's action too.
    val optional =
      original
        .newBuilder()
        .also { b ->
          b.parameters =
            original.parameters.map {
              if (it.name == "onClick")
                parameter("onClick", "(() -> Unit)?", "kotlin.Function0")
                  .newBuilder()
                  .also { p -> p.nullable = true }
                  .build()
              else it
            }
        }
        .build()
    assertThat(adapter.validateAgainst(optional)).isEmpty()
  }

  @Test
  fun `validation catches a changed source signature before publication`() {
    val original = component()
    val changed =
      original
        .newBuilder()
        .also { b ->
          b.parameters =
            original.parameters.map { p ->
              if (p.name == "label")
                p.newBuilder()
                  .also {
                    it.typeFqn = "kotlin.Int"
                    it.type = "Int"
                  }
                  .build()
              else p
            }
        }
        .build()
    val adapter = ButtonAdapter(original)
    assertThat(adapter.validateAgainst(changed))
      .containsExactly("acme/button.label: expected kotlin.String, discovered kotlin.Int")
    assertThrows(IllegalArgumentException::class.java) { generate(adapter, listOf(changed)) }
  }

  @Test
  fun `missing required parameters and unknown signatures fail validation`() {
    val component = component()
    val adapter =
      object : TypedComponentAdapter<Props>("acme/button", component) {
        val label = property(Props::label, AdapterValueCodecs.String, "Button")
      }
    assertThat(adapter.validateAgainst(component))
      .containsAtLeast(
        "acme/button: required parameter enabled is not adapted",
        "acme/button: required parameter onClick is not adapted",
      )
    assertThrows(IllegalArgumentException::class.java) { adapter.freeze() }
    assertThat(
        ButtonAdapter(component)
          .validateAgainst(component.newBuilder().also { it.signatureKnown = false }.build())
      )
      .contains("acme/button: component signature is unknown")
  }

  @Test
  fun `duplicate declarations and late changes cannot split runtime from metadata`() {
    val component = component()
    assertThrows(IllegalArgumentException::class.java) {
      object : TypedComponentAdapter<Props>("acme/button", component) {
        val first = property(Props::label, AdapterValueCodecs.String, "A")
        val second = property(Props::label, AdapterValueCodecs.String, "B")
      }
    }
    val adapter =
      object : TypedComponentAdapter<Props>("acme/button", component) {
        val label = property(Props::label, AdapterValueCodecs.String, "Button")
        val enabled = property(Props::enabled, AdapterValueCodecs.Boolean, true)
        val click = event(Props::onClick)

        fun mutate() = slot("content")
      }
    adapter.freeze()
    assertThrows(IllegalStateException::class.java) { adapter.mutate() }
  }

  @Test
  fun `strict codecs reject malformed literals rather than using the default`() {
    assertThat(AdapterValueCodecs.Int.decode(JsonPrimitive(12))).isEqualTo(12)
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Int.decode(JsonPrimitive("12"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Int.decode(JsonPrimitive(1.5))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Boolean.decode(JsonPrimitive("true"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.String.decode(JsonPrimitive(true))
    }
  }

  @Test
  fun `a separate export entry point writes a pair readable without the application`() {
    val root = Files.createTempDirectory("typed-adapter-publish")
    try {
      val generated = generate()
      val destination = root.resolve("generation")
      generated.writeTo(destination)
      val json = Json { ignoreUnknownKeys = true }
      val record =
        json.decodeFromString(
          ComponentRecordFile.serializer(),
          Files.readString(destination.resolve("components.json")),
        )
      val catalog =
        json.decodeFromString(
          UiBuilderCatalogFile.serializer(),
          Files.readString(destination.resolve("ui-builder.json")),
        )
      assertThat(catalog.statusSemantics.components.getValue("acme/button").record)
        .isEqualTo(record.components.single().canonicalId)
      assertThrows(IllegalArgumentException::class.java) { generated.writeTo(destination) }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private data class InboxProps(
    val contacts: List<TypedFixtureContact>,
    val profile: TypedFixtureProfile,
  )

  private class InboxAdapter(component: ComponentRecord) :
    TypedComponentAdapter<InboxProps>("acme/inbox", component) {
    val contacts =
      property(
        InboxProps::contacts,
        AdapterValueCodecs.list(fixtureContactCodec),
        listOf(TypedFixtureContact("Ada", 2)),
      )
    val profile = property(InboxProps::profile, fixtureProfileCodec, fixtureProfile)
  }

  private fun inbox(
    contacts: TargetParameter = parameter("contacts", "List<Contact>", LIST),
    profile: TargetParameter = parameter("profile", "Profile", PROFILE),
  ) = component("Inbox").newBuilder().also { it.parameters = listOf(contacts, profile) }.build()

  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `a list of scalars wraps each element in its own literal wrapper`() {
    val codec = AdapterValueCodecs.list(AdapterValueCodecs.String)
    val wrapped = codec.wrap(listOf("a", "b"))
    assertThat(wrapped)
      .isEqualTo(
        json(
          """{"type":"list","values":[{"type":"string","value":"a"},{"type":"string","value":"b"}]}"""
        )
      )
    assertThat(codec.unwrap(wrapped)).containsExactly("a", "b").inOrder()
    assertThat(codec.decode(codec.encode(listOf("x")))).containsExactly("x")
    assertThat(codec.unwrap(json("""{"type":"list","values":[]}"""))).isEmpty()
    assertThat(codec.jsonType).isEqualTo("array")
    assertThat(codec.literalType).isEqualTo("list")
    assertThat(codec.kotlinType).isEqualTo("kotlin.collections.List")
    assertThat(codec.stateType).isNull()
    for (bad in
      listOf(
        """{"type":"string","value":"a"}""",
        """{"type":"list","values":[{"type":"int","value":1}]}""",
        """{"type":"list","values":[{"type":"string","value":1}]}""",
        """{"type":"list","values":["a"]}""",
        """{"type":"list","values":[],"extra":true}""",
      )) {
      assertThrows(bad, IllegalArgumentException::class.java) { codec.unwrap(json(bad)) }
    }
  }

  @Test
  fun `scalar wrappers keep today's literal spelling`() {
    assertThat(AdapterValueCodecs.Int.wrap(3)).isEqualTo(json("""{"type":"int","value":3}"""))
    assertThat(AdapterValueCodecs.Boolean.wrap(true))
      .isEqualTo(json("""{"type":"bool","value":true}"""))
    assertThat(AdapterValueCodecs.unit("dp").wrap(4f).getValue("type"))
      .isEqualTo(JsonPrimitive("number"))
    assertThat(AdapterValueCodecs.Int.unwrap(json("""{"type":"int","value":3}"""))).isEqualTo(3)
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Int.unwrap(json("""{"type":"string","value":3}"""))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Int.unwrap(json("""{"type":"int"}"""))
    }
  }

  @Test
  fun `an object wraps its fields, defaults absent ones and refuses unknown ones`() {
    val ada = TypedFixtureContact("Ada", 2)
    val wrapped = fixtureContactCodec.wrap(ada)
    assertThat(wrapped)
      .isEqualTo(
        json(
          """{"type":"object","fields":{"name":{"type":"string","value":"Ada"},"unread":{"type":"int","value":2}}}"""
        )
      )
    assertThat(fixtureContactCodec.unwrap(wrapped)).isEqualTo(ada)
    assertThat(
        fixtureContactCodec.unwrap(
          json("""{"type":"object","fields":{"name":{"type":"string","value":"Bo"}}}""")
        )
      )
      .isEqualTo(TypedFixtureContact("Bo", 0))
    val missing =
      assertThrows(IllegalArgumentException::class.java) {
        fixtureContactCodec.unwrap(
          json("""{"type":"object","fields":{"unread":{"type":"int","value":1}}}""")
        )
      }
    assertThat(missing).hasMessageThat().contains("missing field `name`")
    assertThrows(IllegalArgumentException::class.java) {
      fixtureContactCodec.unwrap(
        json(
          """{"type":"object","fields":{"name":{"type":"string","value":"Bo"},"age":{"type":"int","value":1}}}"""
        )
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      fixtureContactCodec.unwrap(json("""{"type":"list","values":[]}"""))
    }
    assertThat(fixtureContactCodec.kotlinType).isEqualTo(CONTACT)
    assertThat(fixtureContactCodec.stateType).isNull()
  }

  @Test
  fun `lists of objects and objects holding lists and objects round trip`() {
    val contacts = AdapterValueCodecs.list(fixtureContactCodec)
    val people = listOf(TypedFixtureContact("Ada", 2), TypedFixtureContact("Bo", 0))
    val wrappedPeople = contacts.wrap(people)
    assertThat(wrappedPeople.getValue("values").jsonArray.map { it.jsonObject.getValue("type") })
      .containsExactly(JsonPrimitive("object"), JsonPrimitive("object"))
    assertThat(contacts.unwrap(wrappedPeople)).isEqualTo(people)

    val wrappedProfile = fixtureProfileCodec.wrap(fixtureProfile)
    assertThat(wrappedProfile)
      .isEqualTo(
        json(
          """
          {"type":"object","fields":{
            "owner":{"type":"object","fields":{
              "name":{"type":"string","value":"Ada"},"unread":{"type":"int","value":2}}},
            "address":{"type":"object","fields":{"city":{"type":"string","value":"Tallinn"}}},
            "tags":{"type":"list","values":[{"type":"string","value":"vip"}]}}}
          """
        )
      )
    assertThat(fixtureProfileCodec.unwrap(wrappedProfile)).isEqualTo(fixtureProfile)
    val nestedError =
      assertThrows(IllegalArgumentException::class.java) {
        fixtureProfileCodec.unwrap(
          json(
            """
            {"type":"object","fields":{
              "owner":{"type":"object","fields":{"name":{"type":"string","value":"Ada"}}},
              "address":{"type":"object","fields":{"city":{"type":"int","value":1}}}}}
            """
          )
        )
      }
    assertThat(nestedError).hasMessageThat().contains("`address`: `city`")
  }

  @Test
  fun `an object codec needs fields, distinct names and one construct`() {
    assertThrows(IllegalStateException::class.java) {
      AdapterValueCodecs.obj<TypedFixtureAddress>(ADDRESS) {
        field(TypedFixtureAddress::city, AdapterValueCodecs.String)
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.obj<TypedFixtureAddress>(ADDRESS) {
        construct { TypedFixtureAddress("x") }
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.obj<TypedFixtureAddress>(ADDRESS) {
        val city = field(TypedFixtureAddress::city, AdapterValueCodecs.String)
        field(TypedFixtureAddress::city, AdapterValueCodecs.String)
        construct { TypedFixtureAddress(it[city]) }
      }
    }
  }

  @Test
  fun `list and object properties publish a recursive editor and their wrapped defaults`() {
    val record = inbox()
    val adapter = InboxAdapter(record)
    assertThat(adapter.contacts.bindable).isFalse()
    val generated = generate(adapter, listOf(record))
    assertThat(generated.catalog.diagnostics).isEmpty()
    val published = generated.catalog.statusSemantics.components.getValue("acme/inbox")
    val contact =
      """
      {"jsonType":"object","literalType":"object","editor":{"control":"object",
        "objectType":"$CONTACT","fields":[
          {"name":"name","required":true,"jsonType":"string","literalType":"string"},
          {"name":"unread","required":false,"default":{"type":"int","value":0},
           "jsonType":"integer","literalType":"int"}]}}
      """
    assertThat(published.propertyCapabilities)
      .containsExactly(
        json(
          """
          {"name":"contacts","jsonType":"array","required":false,
           "editor":{"control":"list","element":$contact}}
          """
        ),
        json(
          """
          {"name":"profile","jsonType":"object","required":false,
           "editor":{"control":"object","objectType":"$PROFILE","fields":[
             ${contact.trim().removeSuffix("}")},"name":"owner","required":true},
             {"name":"address","required":true,"jsonType":"object","literalType":"object",
              "editor":{"control":"object","objectType":"$ADDRESS","fields":[
                {"name":"city","required":true,"jsonType":"string","literalType":"string"}]}},
             {"name":"tags","required":false,"default":{"type":"list","values":[]},
              "jsonType":"array","literalType":"list",
              "editor":{"control":"list","element":{"jsonType":"string","literalType":"string"}}}]}}
          """
        ),
      )
      .inOrder()
    val insert = published.insertContent!!.jsonObject.getValue("properties").jsonObject
    assertThat(insert.getValue("contacts"))
      .isEqualTo(adapter.contacts.codec.wrap(adapter.contacts.default))
    assertThat(adapter.profile.unwrap(insert.getValue("profile").jsonObject))
      .isEqualTo(fixtureProfile)
    assertThat(adapter.profile.unwrap(null)).isEqualTo(fixtureProfile)
  }

  @Test
  fun `scalar adapters keep their insert content and capabilities byte for byte`() {
    val published = generate().catalog.statusSemantics.components.getValue("acme/button")
    assertThat(published.insertContent)
      .isEqualTo(
        json(
          """{"properties":{"label":{"type":"string","value":"Button"},"enabled":{"type":"bool","value":true}}}"""
        )
      )
    assertThat(published.insertContent.toString())
      .isEqualTo(
        """{"properties":{"label":{"type":"string","value":"Button"},"enabled":{"type":"bool","value":true}}}"""
      )
    assertThat(published.propertyCapabilities!!.map { it.toString() })
      .containsExactly(
        """{"name":"label","jsonType":["string","object"],"required":false}""",
        """{"name":"enabled","jsonType":["boolean","object"],"required":false}""",
      )
      .inOrder()
  }

  @Test
  fun `an enum element publishes its allowed values`() {
    val tone =
      AdapterValueCodecs.enumOf(
        "acme.Tone",
        mapOf("loud" to "acme.Tone.Loud", "soft" to "acme.Tone.Soft"),
      )
    assertThat(AdapterValueCodecs.list(tone).editor)
      .isEqualTo(
        json(
          """
          {"control":"list","element":{"jsonType":"string","literalType":"string",
            "allowedValues":["loud","soft"]}}
          """
        )
      )
  }

  @Test
  fun `list and object parameters validate against the erased classifier and the class`() {
    assertThat(InboxAdapter(inbox()).validateAgainst(inbox())).isEmpty()
    val javaList = inbox(contacts = parameter("contacts", "List<Contact>", "java.util.List"))
    assertThat(InboxAdapter(inbox()).validateAgainst(javaList))
      .containsExactly(
        "acme/inbox.contacts: expected kotlin.collections.List, discovered java.util.List"
      )
    val nullable =
      inbox(
        contacts =
          parameter("contacts", "List<Contact>?", LIST)
            .newBuilder()
            .also { it.nullable = true }
            .build()
      )
    assertThat(InboxAdapter(inbox()).validateAgainst(nullable))
      .containsExactly(
        "acme/inbox.contacts: expected kotlin.collections.List, discovered kotlin.collections.List?"
      )
    val otherClass = inbox(profile = parameter("profile", "Profile", "acme.OtherProfile"))
    assertThat(InboxAdapter(inbox()).validateAgainst(otherClass))
      .containsExactly("acme/inbox.profile: expected $PROFILE, discovered acme.OtherProfile")
  }

  @Test
  fun `compiled list and object parameters are discovered as the erased classifier`() {
    ClassGraph()
      .enableClasspath()
      .enableClassInfo()
      .enableMethodInfo()
      .ignoreMethodVisibility()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val owner = scan.getClassInfo("ee.schimke.composeai.discovery.TypedComponentAdapterTestKt")
        val method = owner.methodInfo.single { it.name == "typedCollectionFixture" }
        val parameters = ComposableSignature.parametersOf(owner, method)
        assertThat(parameters.map { it.typeFqn }).containsExactly(LIST, PROFILE).inOrder()
        val record = inbox().newBuilder().also { it.parameters = parameters }.build()
        assertThat(InboxAdapter(record).validateAgainst(record)).isEmpty()
      }
  }

  @Test
  fun `a projection writes list and object properties as their erased and class types`() {
    val record = inbox()
    val adapter =
      object : TypedComponentAdapter<InboxProps>("acme/inbox", record) {
        val contacts =
          property(InboxProps::contacts, AdapterValueCodecs.list(fixtureContactCodec), emptyList())
        val profile = property(InboxProps::profile, fixtureProfileCodec, fixtureProfile)

        init {
          exportAs(target(record, mapOf("contacts" to from(contacts), "profile" to from(profile))))
        }
      }
    assertThat(adapter.validateAgainst(record)).isEmpty()
    val codec = adapter.contacts.codec.exportCodec
    assertThat(codec)
      .isEqualTo(
        ExportProjection.Codec.ListOf(
          ExportProjection.Codec.Object(
            CONTACT,
            mapOf(
              "name" to ExportProjection.Codec.Field(ExportProjection.Codec.Text),
              // The adapter's default travels with the export, for an object that omits it.
              "unread" to
                ExportProjection.Codec.Field(
                  ExportProjection.Codec.Whole,
                  "int",
                  default = json("""{"type":"int","value":0}"""),
                ),
            ),
          )
        )
      )
    val swapped = inbox(contacts = parameter("contacts", "Profile", PROFILE))
    assertThat(adapter.validateAgainst(swapped).joinToString("\n"))
      .contains("writes kotlin.collections.List, parameter takes $PROFILE")
  }
}

private const val LIST = "kotlin.collections.List"
private const val CONTACT = "ee.schimke.composeai.discovery.TypedFixtureContact"
private const val ADDRESS = "ee.schimke.composeai.discovery.TypedFixtureAddress"
private const val PROFILE = "ee.schimke.composeai.discovery.TypedFixtureProfile"

internal data class TypedFixtureContact(val name: String, val unread: Int)

internal data class TypedFixtureAddress(val city: String)

internal data class TypedFixtureProfile(
  val owner: TypedFixtureContact,
  val address: TypedFixtureAddress,
  val tags: List<String>,
)

private val fixtureContactCodec: AdapterObjectCodec<TypedFixtureContact> =
  AdapterValueCodecs.obj(CONTACT) {
    val name = field(TypedFixtureContact::name, AdapterValueCodecs.String)
    val unread = field(TypedFixtureContact::unread, AdapterValueCodecs.Int, default = 0)
    construct { TypedFixtureContact(name = it[name], unread = it[unread]) }
  }

private val fixtureAddressCodec: AdapterObjectCodec<TypedFixtureAddress> =
  AdapterValueCodecs.obj(ADDRESS) {
    val city = field(TypedFixtureAddress::city, AdapterValueCodecs.String)
    construct { TypedFixtureAddress(it[city]) }
  }

private val fixtureProfileCodec: AdapterObjectCodec<TypedFixtureProfile> =
  AdapterValueCodecs.obj(PROFILE) {
    val owner = field(TypedFixtureProfile::owner, fixtureContactCodec)
    val address = field(TypedFixtureProfile::address, fixtureAddressCodec)
    val tags =
      field(
        TypedFixtureProfile::tags,
        AdapterValueCodecs.list(AdapterValueCodecs.String),
        default = emptyList(),
      )
    construct { TypedFixtureProfile(it[owner], it[address], it[tags]) }
  }

private val fixtureProfile =
  TypedFixtureProfile(TypedFixtureContact("Ada", 2), TypedFixtureAddress("Tallinn"), listOf("vip"))

@Suppress("UNUSED_PARAMETER")
private fun typedAdapterFixture(label: String, enabled: Boolean, onClick: () -> Unit) {}

@Suppress("UNUSED_PARAMETER")
private fun typedCollectionFixture(
  contacts: List<TypedFixtureContact>,
  profile: TypedFixtureProfile,
) {}
