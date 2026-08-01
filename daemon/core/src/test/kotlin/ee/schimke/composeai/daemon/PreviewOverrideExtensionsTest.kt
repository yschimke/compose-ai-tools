package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOverrideExtensionsTest {

  @Test
  fun non_null_overrides_are_planned_by_active_extensions_in_registration_order() {
    val overrides = PreviewOverrides(widthPx = 320)
    val first = RecordingExtension("first")
    val inactive = RecordingExtension("inactive")
    val abstaining = RecordingExtension("abstaining", result = null)
    val last = RecordingExtension("last")
    val subject =
      PreviewOverrideExtensions(
        extensions = listOf(first, inactive, abstaining, last),
        isActive = { it !== inactive },
      )

    val plans = subject.plan(overrides)

    assertEquals(listOf("first", "last"), plans.map { it.id.value })
    assertSame(overrides, first.requests.single())
    assertTrue(inactive.requests.isEmpty())
    assertSame(overrides, abstaining.requests.single())
    assertSame(overrides, last.requests.single())
  }

  @Test
  fun null_overrides_plan_only_active_always_on_extensions_with_an_empty_bag() {
    val regular = RecordingExtension("regular")
    val first = AlwaysOnRecordingExtension("first")
    val inactive = AlwaysOnRecordingExtension("inactive")
    val abstaining = AlwaysOnRecordingExtension("abstaining", result = null)
    val last = AlwaysOnRecordingExtension("last")
    val subject =
      PreviewOverrideExtensions(
        extensions = listOf(regular, first, inactive, abstaining, last),
        isActive = { it !== inactive },
      )

    val plans = subject.plan(null)

    assertEquals(listOf("first", "last"), plans.map { it.id.value })
    assertTrue(regular.requests.isEmpty())
    assertTrue(inactive.requests.isEmpty())
    assertEquals(PreviewOverrides(), first.requests.single())
    assertSame(first.requests.single(), abstaining.requests.single())
    assertSame(first.requests.single(), last.requests.single())
  }

  @Test
  fun empty_registry_returns_no_plans_without_consulting_activation() {
    var activationChecked = false
    val subject =
      PreviewOverrideExtensions(emptyList()) {
        activationChecked = true
        true
      }

    assertTrue(subject.plan(PreviewOverrides()).isEmpty())
    assertTrue(subject.plan(null).isEmpty())
    assertFalse(activationChecked)
    assertTrue(PreviewOverrideExtensions.Empty.plan(PreviewOverrides()).isEmpty())
  }

  private open class RecordingExtension(name: String, result: PlannedDataExtension? = Plan(name)) :
    PreviewOverrideExtension {
    override val id = DataExtensionId(name)
    val requests = mutableListOf<PreviewOverrides>()
    private val plannedResult = result

    override fun plan(request: PreviewOverrides): PlannedDataExtension? {
      requests += request
      return plannedResult
    }
  }

  private class AlwaysOnRecordingExtension(
    name: String,
    result: PlannedDataExtension? = Plan(name),
  ) : RecordingExtension(name, result), AlwaysOnPreviewOverrideExtension

  private data class Plan(private val name: String) : PlannedDataExtension {
    override val id = DataExtensionId(name)
    override val hooks = emptySet<DataExtensionHookKind>()
    override val constraints = DataExtensionConstraints()
  }
}
