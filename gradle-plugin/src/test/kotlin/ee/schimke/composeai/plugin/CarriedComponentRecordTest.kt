package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.BuilderPolicy
import ee.schimke.composeai.discovery.ComponentBinding
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import org.junit.Test

/**
 * Pins [carriedComponentRecord]: a bundle carrying only some previews of an overloaded component
 * publishes the overload the FULL record chose, so `components.json` and `ui-builder.json` (built
 * from the full record) describe one signature, while its bindings stay the bundle's own previews.
 */
class CarriedComponentRecordTest {

  private fun record(
    name: String,
    descriptor: String,
    parameter: String,
    previews: List<String>,
    builder: BuilderPolicy? = null,
  ) =
    ComponentRecord.Builder(
        canonicalId = "catalog/acme.${name}Kt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "acme.${name}Kt",
              callable = "acme.$name",
              name = name,
              origin = ComponentOrigin.PROJECT,
            )
            .also { b -> b.descriptor = descriptor }
            .build(),
      )
      .also { b ->
        b.parameters = listOf(TargetParameter.Builder(name = parameter, type = "String").build())
        b.bindings = previews.map { ComponentBinding.Builder(previewId = it).build() }
        b.signatureKnown = true
        b.builder = builder
      }
      .build()

  private fun file(vararg components: ComponentRecord) =
    ComponentRecordFile.Builder(":app", "debug", components.toList()).build()

  @Test
  fun `the full record's overload is carried, with only the bundle's own bindings`() {
    val bundleIds = assignBundleEntryIds(listOf("acme.ButtonText", "acme.ButtonIcon A"))
    val full =
      file(
        record(
          "Button",
          "(Ljava/lang/String;)V",
          "text",
          listOf("acme.ButtonText", "acme.ButtonText2", "acme.ButtonIcon A"),
          BuilderPolicy.Builder().also { b -> b.declaredBy = listOf("acme.ButtonIcon A") }.build(),
        ),
        record("Card", "()V", "title", listOf("acme.CardPreview")),
      )
    // Over the filtered manifest alone the other overload would win: the bundle's one preview of
    // it outnumbers the one remaining call of the overload the full catalog chose.
    val filtered =
      file(
        record(
          "Button",
          "(Lacme/Icon;)V",
          "icon",
          listOf(bundleIds.getValue("acme.ButtonIcon A")),
        )
      )

    val carried = carriedComponentRecord(full, filtered, bundleIds).components

    val button = carried.single()
    assertThat(button.symbol.descriptor).isEqualTo("(Ljava/lang/String;)V")
    assertThat(button.parameters.map { it.name }).containsExactly("text")
    assertThat(button.bindings.map { it.previewId })
      .containsExactly(bundleIds.getValue("acme.ButtonIcon A"))
    assertThat(button.builder!!.declaredBy).containsExactly(bundleIds.getValue("acme.ButtonIcon A"))
  }
}
