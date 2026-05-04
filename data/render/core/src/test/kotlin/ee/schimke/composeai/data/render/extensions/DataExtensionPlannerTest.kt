package ee.schimke.composeai.data.render.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExtensionPlannerTest {
  @Test
  fun ordersExtensionsByPhaseAndExplicitConstraints() {
    val theme = extension("theme", phase = DataExtensionPhase.UserEnvironment)
    val layout =
      extension(
        "layout",
        phase = DataExtensionPhase.Instrumentation,
        after = setOf("theme"),
        provides = setOf("slotTables"),
      )
    val a11y =
      extension(
        "a11y",
        phase = DataExtensionPhase.PostProcess,
        after = setOf("layout"),
        requires = setOf("slotTables"),
      )
    val fonts =
      extension("fonts", phase = DataExtensionPhase.Instrumentation, before = setOf("layout"))

    val result = DataExtensionPlanner.plan(listOf(a11y, layout, fonts, theme))

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(listOf("theme", "fonts", "layout", "a11y"), result.orderedIds())
  }

  @Test
  fun reportsMissingCapabilitiesAfterOrdering() {
    val a11y =
      extension(
        "a11y",
        phase = DataExtensionPhase.PostProcess,
        requires = setOf("semanticsRoot", "imageArtifact"),
      )

    val result =
      DataExtensionPlanner.plan(listOf(a11y), initialCapabilities = setOf(cap("imageArtifact")))

    assertFalse(result.isValid)
    assertEquals(listOf("MissingCapability"), result.errors.map { it.code })
    assertEquals(listOf("a11y"), result.errors.single().extensions.map { it.value })
  }

  @Test
  fun reportsUnknownOrderingTargets() {
    val theme = extension("theme", before = setOf("missing"))

    val result = DataExtensionPlanner.plan(listOf(theme))

    assertFalse(result.isValid)
    assertEquals("UnknownOrderingTarget", result.errors.single().code)
    assertEquals(listOf("theme", "missing"), result.errors.single().extensions.map { it.value })
  }

  @Test
  fun reportsOrderingCycles() {
    val theme = extension("theme", after = setOf("layout"))
    val layout = extension("layout", after = setOf("theme"))

    val result = DataExtensionPlanner.plan(listOf(theme, layout))

    assertFalse(result.isValid)
    assertTrue(result.errors.any { it.code == "OrderingCycle" })
  }

  @Test
  fun reportsConflictingCapabilities() {
    val first =
      extension(
        "scrollGif",
        phase = DataExtensionPhase.Scenario,
        provides = setOf("frameClockOwner"),
      )
    val second =
      extension(
        "animation",
        phase = DataExtensionPhase.Scenario,
        conflictsWith = setOf("frameClockOwner"),
      )

    val result = DataExtensionPlanner.plan(listOf(second, first))

    assertFalse(result.isValid)
    assertEquals("ConflictingCapability", result.errors.single().code)
    assertEquals(
      listOf("animation", "scrollGif"),
      result.errors.single().extensions.map { it.value },
    )
  }

  @Test
  fun buildsPlanFromRequestParticipatingExtensionsOnly() {
    data class Request(val enabled: Set<String>)

    class TestExtension(private val name: String) : DataExtension<Request> {
      override val id: DataExtensionId = DataExtensionId(name)

      override fun plan(request: Request): PlannedDataExtension? =
        if (name in request.enabled) SimplePlannedDataExtension(id = id) else null
    }

    val result =
      DataExtensionPlanner.planRequest(
        extensions = listOf(TestExtension("theme"), TestExtension("a11y"), TestExtension("fonts")),
        request = Request(enabled = setOf("theme", "fonts")),
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(listOf("fonts", "theme"), result.orderedIds())
  }

  private fun DataExtensionPlanningResult.orderedIds(): List<String> = orderedExtensions.map {
    it.id.value
  }

  private fun extension(
    id: String,
    phase: DataExtensionPhase = DataExtensionPhase.Instrumentation,
    before: Set<String> = emptySet(),
    after: Set<String> = emptySet(),
    requires: Set<String> = emptySet(),
    provides: Set<String> = emptySet(),
    conflictsWith: Set<String> = emptySet(),
  ): SimplePlannedDataExtension =
    SimplePlannedDataExtension(
      id = DataExtensionId(id),
      constraints =
        DataExtensionConstraints(
          phase = phase,
          before = before.map(::DataExtensionId).toSet(),
          after = after.map(::DataExtensionId).toSet(),
          requires = requires.map(::cap).toSet(),
          provides = provides.map(::cap).toSet(),
          conflictsWith = conflictsWith.map(::cap).toSet(),
        ),
    )

  private fun cap(value: String): DataExtensionCapability = DataExtensionCapability(value)
}
