package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComponentRecordsTest {

  private fun target(
    className: String,
    functionName: String,
    parameters: List<TargetParameter> = emptyList(),
    sourceFile: String? = null,
  ) =
    PreviewTarget(
      className = className,
      functionName = functionName,
      sourceFile = sourceFile,
      confidence = TargetConfidence.HIGH,
      parameters = parameters,
    )

  private fun preview(
    id: String,
    componentTargets: List<PreviewTarget> = emptyList(),
    targets: List<PreviewTarget> = emptyList(),
  ) =
    PreviewInfo(
      id = id,
      functionName = id.substringAfterLast('.'),
      className = "com.example.PreviewsKt",
      targets = targets,
      componentTargets = componentTargets,
    )

  private fun manifest(vararg previews: PreviewInfo) =
    PreviewManifest(module = "app", variant = "debug", previews = previews.toList())

  @Test
  fun `a library target and a project target are both recorded, and told apart by origin`() {
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(target("androidx.compose.material3.CardKt", "Card")),
          ),
          preview(
            "p2",
            targets = listOf(target("com.example.HomeKt", "HomeScreen", sourceFile = "src/Home.kt")),
          ),
        )
      )

    assertThat(file.components.map { it.symbol.origin })
      .containsExactly(ComponentOrigin.LIBRARY, ComponentOrigin.PROJECT)
      .inOrder()
    assertThat(file.schemaVersion).isEqualTo(COMPONENT_RECORD_SCHEMA_VERSION)
  }

  @Test
  fun `the relation is inverted - one component lists every preview that renders it`() {
    // previews.json says "this render came from that component"; this says the reverse, which is
    // what makes a component addressable at all.
    val card = target("androidx.compose.material3.CardKt", "Card")
    val file =
      ComponentRecords.from(
        manifest(
          preview("b", componentTargets = listOf(card)),
          preview("a", componentTargets = listOf(card)),
        )
      )

    val record = file.components.single()
    assertThat(record.bindings.map { it.previewId }).containsExactly("a", "b").inOrder()
  }

  @Test
  fun `components and bindings are ordered, so the file is byte-reproducible`() {
    // A data product that reorders itself between builds is a diff nobody can read.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "z",
            componentTargets = listOf(target("androidx.compose.material3.TextKt", "Text")),
          ),
          preview(
            "a",
            componentTargets = listOf(target("androidx.compose.material3.CardKt", "Card")),
          ),
        )
      )

    assertThat(file.components.map { it.canonicalId }).isInOrder()
  }

  @Test
  fun `the richest signature wins when one component is seen twice`() {
    // A target resolved through a path that could not read metadata reports no parameters. Letting
    // that overwrite a populated signature would lose the API for every consumer.
    val withParams =
      target(
        "androidx.compose.material3.CardKt",
        "Card",
        parameters = listOf(TargetParameter("modifier", "Modifier", hasDefault = true)),
      )
    val withNone = target("androidx.compose.material3.CardKt", "Card")

    val file =
      ComponentRecords.from(
        manifest(
          preview("a", componentTargets = listOf(withNone)),
          preview("b", componentTargets = listOf(withParams)),
        )
      )

    assertThat(file.components.single().parameters.map { it.name }).containsExactly("modifier")
  }

  @Test
  fun `a top-level function's callable drops the synthetic file facade`() {
    // Deriving an import from the JVM owner would print `androidx.compose.material3.ButtonKt`,
    // which does not resolve.
    assertThat(
        ComponentRecords.callableFqn(target("androidx.compose.material3.ButtonKt", "Button"))
      )
      .isEqualTo("androidx.compose.material3.Button")
  }

  @Test
  fun `a member of a real class keeps its owner`() {
    assertThat(ComponentRecords.callableFqn(target("com.example.Screens", "Home")))
      .isEqualTo("com.example.Screens.Home")
  }

  @Test
  fun `canonicalId is module-qualified and always present`() {
    assertThat(ComponentRecords.canonicalId("app", target("com.example.HomeKt", "HomeScreen")))
      .isEqualTo("app/com.example.HomeKt.HomeScreen")
  }

  @Test
  fun `a composable lambda parameter becomes a slot, carrying its receiver scope`() {
    val slots =
      ComponentRecords.slotsOf(
        listOf(
          TargetParameter("onClick", "() -> Unit"),
          TargetParameter("modifier", "Modifier", hasDefault = true),
          TargetParameter("content", "RowScope.() -> Unit", composableSlot = true),
          TargetParameter("footer", "() -> Unit", hasDefault = true, composableSlot = true),
        )
      )

    // `onClick` is function-typed but not `@Composable`: a callback, not a slot.
    assertThat(slots.map { it.name }).containsExactly("content", "footer").inOrder()
    assertThat(slots[0].receiverScope).isEqualTo("RowScope")
    // Requiredness is about the LAMBDA argument, never about how many children it may emit.
    assertThat(slots[0].required).isTrue()
    // An unscoped slot records no receiver rather than an empty string.
    assertThat(slots[1].receiverScope).isNull()
    assertThat(slots[1].required).isFalse()
  }

  @Test
  fun `a module with no inferred targets still produces a file`() {
    // An empty component list is a fact worth publishing — it says inference found nothing.
    val file = ComponentRecords.from(manifest(preview("p1")))

    assertThat(file.components).isEmpty()
    assertThat(file.module).isEqualTo("app")
  }
}
