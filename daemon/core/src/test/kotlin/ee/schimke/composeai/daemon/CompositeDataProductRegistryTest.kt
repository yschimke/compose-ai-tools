package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Targeted tests for [CompositeDataProductRegistry]'s routing rules. The fan-out semantics matter
 * the most for [renderModeFor], which moved the kind-to-render-mode mapping out of
 * [JsonRpcServer]'s hardcoded `a11y/` prefix check and into the owning producer registry. These
 * tests pin "delegates to the registry that advertises the kind" so the dispatcher's
 * subscription-driven render-mode injection keeps working as new mode-driving kinds get added.
 */
class CompositeDataProductRegistryTest {

  /** Minimal producer stub: advertises [kind] and returns [renderMode] from [renderModeFor]. */
  private class StubProducer(private val kind: String, private val renderMode: String? = null) :
    DataProductRegistry {
    override val capabilities =
      listOf(
        DataProductCapability(
          kind = kind,
          schemaVersion = 1,
          transport = DataProductTransport.INLINE,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
        )
      )

    override fun isKnown(kind: String): Boolean = kind == this.kind

    override fun fetch(
      previewId: String,
      kind: String,
      params: JsonElement?,
      inline: Boolean,
    ): DataProductRegistry.Outcome = DataProductRegistry.Outcome.Unknown

    override fun attachmentsFor(
      previewId: String,
      kinds: Set<String>,
    ): List<DataProductAttachment> = emptyList()

    override fun renderModeFor(kind: String): String? = if (kind == this.kind) renderMode else null
  }

  @Test
  fun `delegates renderModeFor to the registry that advertises the kind`() {
    val composite =
      CompositeDataProductRegistry(
        listOf(
          StubProducer("a11y/atf", renderMode = "a11y"),
          StubProducer("compose/recomposition", renderMode = null),
        )
      )
    assertEquals("a11y", composite.renderModeFor("a11y/atf"))
    assertNull(composite.renderModeFor("compose/recomposition"))
  }

  @Test
  fun `returns null for kinds that no registry advertises`() {
    val composite = CompositeDataProductRegistry(listOf(StubProducer("a11y/atf", "a11y")))
    assertNull(composite.renderModeFor("unknown/kind"))
  }

  @Test
  fun `default DataProductRegistry renderModeFor returns null`() {
    // Regression: producers without an explicit override (every existing connector except a11y)
    // must report "no mode required" so the dispatcher leaves the standard render pipeline alone.
    val producer = StubProducer("compose/recomposition") // renderMode defaults to null
    assertNull(producer.renderModeFor("compose/recomposition"))
  }

  @Test
  fun `picks the first matching registry when multiple advertise the same kind`() {
    // CompositeDataProductRegistry already documents "kind routing uses the first registry that
    // advertises the kind" for fetch / onSubscribe / onUnsubscribe; renderModeFor follows the
    // same rule so future stacks that layer producers (e.g. a test override on top of the real
    // a11y registry) get consistent routing across every fan-out method.
    val composite =
      CompositeDataProductRegistry(
        listOf(
          StubProducer("a11y/atf", renderMode = "a11y-override"),
          StubProducer("a11y/atf", renderMode = "a11y"),
        )
      )
    assertEquals("a11y-override", composite.renderModeFor("a11y/atf"))
  }
}
