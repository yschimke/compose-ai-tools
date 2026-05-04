package ee.schimke.composeai.data.render.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

  @Test
  fun expandsRequestedOutputsThroughTypedProductDependencies() {
    val semantics = product("render/semantics")
    val hierarchy = product("a11y/hierarchy")
    val atf = product("a11y/atf")
    val overlay = product("a11y/overlay")

    val hierarchyExtension =
      extension(
        id = "a11y",
        phase = DataExtensionPhase.Capture,
        inputs = setOf(semantics),
        outputs = setOf(hierarchy),
      )
    val atfExtension =
      extension(
        id = "atf",
        phase = DataExtensionPhase.PostProcess,
        inputs = setOf(hierarchy),
        outputs = setOf(atf),
      )
    val overlayExtension =
      extension(
        id = "overlay",
        phase = DataExtensionPhase.Publish,
        inputs = setOf(hierarchy, atf),
        outputs = setOf(overlay),
      )

    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(overlayExtension, atfExtension, hierarchyExtension),
        requestedOutputs = setOf(overlay),
        initialProducts = setOf(semantics),
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(listOf("a11y", "atf", "overlay"), result.orderedIds())
  }

  @Test
  fun reportsMissingProviderForRequestedTypedProduct() {
    val result =
      DataExtensionPlanner.planOutputs(
        extensions = emptyList(),
        requestedOutputs = setOf(product("a11y/overlay")),
      )

    assertFalse(result.isValid)
    assertEquals(listOf("MissingProductProvider"), result.errors.map { it.code })
  }

  @Test
  fun reportsDuplicateTypedProductProviders() {
    val hierarchy = product("a11y/hierarchy")

    val result =
      DataExtensionPlanner.planOutputs(
        extensions =
          listOf(
            extension("android-hierarchy", outputs = setOf(hierarchy)),
            extension("desktop-hierarchy", outputs = setOf(hierarchy)),
          ),
        requestedOutputs = setOf(hierarchy),
      )

    assertFalse(result.isValid)
    assertEquals(listOf("DuplicateProductProvider"), result.errors.map { it.code })
  }

  @Test
  fun filtersTypedProductProvidersByTargetBeforeResolvingGraph() {
    val semantics = product("render/semantics")
    val hierarchy = product("a11y/hierarchy")

    val result =
      DataExtensionPlanner.planOutputs(
        extensions =
          listOf(
            extension(
              "android-hierarchy",
              inputs = setOf(semantics),
              outputs = setOf(hierarchy),
              targets = setOf(DataExtensionTarget.Android),
            ),
            extension(
              "desktop-hierarchy",
              inputs = setOf(semantics),
              outputs = setOf(hierarchy),
              targets = setOf(DataExtensionTarget.Desktop),
            ),
          ),
        requestedOutputs = setOf(hierarchy),
        initialProducts = setOf(semantics),
        target = DataExtensionTarget.Desktop,
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(listOf("desktop-hierarchy"), result.orderedIds())
  }

  @Test
  fun scopedStoreAllowsDeclaredPutAndGet() {
    val hierarchy = product("a11y/hierarchy")
    val atf = product("a11y/atf")
    val producer = extension("a11y", outputs = setOf(hierarchy))
    val consumer = extension("atf", inputs = setOf(hierarchy), outputs = setOf(atf))

    val store = RecordingDataProductStore()
    store.scopedFor(producer).put(hierarchy, "nodes")
    val view = store.scopedFor(consumer)

    assertEquals("nodes", view.get(hierarchy))
    assertEquals("nodes", view.require(hierarchy))
  }

  @Test
  fun scopedStoreRejectsUndeclaredPut() {
    val hierarchy = product("a11y/hierarchy")
    val producer = extension("hierarchy")

    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        RecordingDataProductStore().scopedFor(producer).put(hierarchy, "nodes")
      }
    assertTrue(ex.message!!.contains("undeclared product"))
  }

  @Test
  fun scopedStoreRejectsUndeclaredGet() {
    val hierarchy = product("a11y/hierarchy")
    val consumer = extension("atf")

    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        RecordingDataProductStore().scopedFor(consumer).get(hierarchy)
      }
    assertTrue(ex.message!!.contains("undeclared product"))
  }

  @Test
  fun scopedStoreReturnsNullForDeclaredButMissingInput() {
    val hierarchy = product("a11y/hierarchy")
    val consumer = extension("atf", inputs = setOf(hierarchy))

    assertNull(RecordingDataProductStore().scopedFor(consumer).get(hierarchy))
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
    inputs: Set<DataProductKey<*>> = emptySet(),
    outputs: Set<DataProductKey<*>> = emptySet(),
    targets: Set<DataExtensionTarget> = emptySet(),
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
      inputs = inputs,
      outputs = outputs,
      targets = targets,
    )

  private fun cap(value: String): DataExtensionCapability = DataExtensionCapability(value)

  private fun product(kind: String): DataProductKey<String> =
    DataProductKey(kind, schemaVersion = 1, String::class.java)
}
