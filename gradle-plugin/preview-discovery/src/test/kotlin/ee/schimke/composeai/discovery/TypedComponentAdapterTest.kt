package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import io.github.classgraph.ClassGraph
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
        parameter("onClick", "() -> Unit", "kotlin.Function0")
          .newBuilder()
          .also { it.nullable = true }
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
}

@Suppress("UNUSED_PARAMETER")
private fun typedAdapterFixture(label: String, enabled: Boolean, onClick: () -> Unit) {}
