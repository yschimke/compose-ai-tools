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
    TargetParameter.Builder(name, type).also { it.typeFqn = fqn }.build()

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
  ) =
    TypedAdapterCatalog.generate(
      ComponentRecordFile.Builder(":app", "desktop", discovered).build(),
      UiBuilderCatalogs.CoverSheet("acme", "Acme app"),
      UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
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
