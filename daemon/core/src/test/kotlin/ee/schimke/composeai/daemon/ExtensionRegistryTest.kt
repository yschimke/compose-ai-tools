package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewContext
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRegistryTest {

  private fun cap(kind: String) =
    DataProductCapability(
      kind = kind,
      schemaVersion = 1,
      transport = DataProductTransport.PATH,
      attachable = true,
      fetchable = true,
      requiresRerender = false,
    )

  private class RecordingRegistry(kind: String) : DataProductRegistry {
    val onRenderCount = java.util.concurrent.atomic.AtomicInteger(0)
    val onSubscribeCount = java.util.concurrent.atomic.AtomicInteger(0)
    val fetchCount = java.util.concurrent.atomic.AtomicInteger(0)
    override val capabilities =
      listOf(
        DataProductCapability(
          kind = kind,
          schemaVersion = 1,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
        )
      )

    override fun fetch(
      previewId: String,
      kind: String,
      params: JsonElement?,
      inline: Boolean,
    ): DataProductRegistry.Outcome {
      fetchCount.incrementAndGet()
      return DataProductRegistry.Outcome.Ok(DataFetchResult(kind = kind, schemaVersion = 1))
    }

    override fun attachmentsFor(
      previewId: String,
      kinds: Set<String>,
    ): List<DataProductAttachment> = emptyList()

    override fun onRender(
      previewId: String,
      result: RenderResult,
      overrides: PreviewOverrides?,
      previewContext: PreviewContext?,
    ) {
      onRenderCount.incrementAndGet()
    }

    override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
      onSubscribeCount.incrementAndGet()
    }
  }

  @Test
  fun `default registry advertises nothing public until enabled`() {
    val a = RecordingRegistry("a/kind")
    val registry = ExtensionRegistry(listOf(Extension(id = "ext/a", dataProductRegistry = a)))

    assertTrue(registry.publicDataProductCapabilities().isEmpty())
    assertEquals(emptyList<String>(), registry.publicIds().toList())
    assertFalse(registry.isPubliclyEnabled("ext/a"))
    assertFalse(registry.isActive("ext/a"))

    val list = registry.infoList()
    assertEquals(1, list.size)
    assertEquals("a/kind", list[0].dataProductKinds.single())
    assertFalse(list[0].publiclyEnabled)
    assertFalse(list[0].active)
  }

  @Test
  fun `enable publishes capabilities and activates the extension`() {
    val a = RecordingRegistry("a/kind")
    val registry = ExtensionRegistry(listOf(Extension(id = "ext/a", dataProductRegistry = a)))

    val out = registry.enable(listOf("ext/a"))
    assertEquals(listOf("ext/a"), out.newlyEnabled)
    assertEquals(emptyList<String>(), out.pulledIn)
    assertEquals(emptyList<String>(), out.unknown)
    assertTrue(registry.isPubliclyEnabled("ext/a"))
    assertTrue(registry.isActive("ext/a"))
    assertEquals(listOf("a/kind"), registry.publicDataProductCapabilities().map { it.kind })
  }

  @Test
  fun `enable unknown id is reported and skipped`() {
    val registry =
      ExtensionRegistry(
        listOf(Extension(id = "ext/a", dataProductRegistry = RecordingRegistry("a/kind")))
      )
    val out = registry.enable(listOf("ext/a", "nope"))
    assertEquals(listOf("ext/a"), out.newlyEnabled)
    assertEquals(listOf("nope"), out.unknown)
    assertTrue(registry.isPubliclyEnabled("ext/a"))
  }

  @Test
  fun `dependencies are pulled in but stay invisible to public surface`() {
    val a = RecordingRegistry("a/kind")
    val b = RecordingRegistry("b/kind")
    val registry =
      ExtensionRegistry(
        listOf(
          Extension(id = "ext/b", dataProductRegistry = b),
          Extension(id = "ext/a", dataProductRegistry = a, dependencies = listOf("ext/b")),
        )
      )

    val out = registry.enable(listOf("ext/a"))
    assertEquals(listOf("ext/a"), out.newlyEnabled)
    assertEquals(listOf("ext/b"), out.pulledIn)
    assertTrue(registry.isPubliclyEnabled("ext/a"))
    assertFalse(registry.isPubliclyEnabled("ext/b"))
    assertTrue(registry.isActive("ext/b"))

    // Public surface only reports `a/kind` — the dep's kind is invisible.
    assertEquals(listOf("a/kind"), registry.publicDataProductCapabilities().map { it.kind })
    assertFalse(registry.publicDataProducts().isKnown("b/kind"))
    // Active surface includes the dep so onRender flows through.
    assertTrue(registry.activeDataProducts().isKnown("b/kind"))
  }

  @Test
  fun `disable deactivates the extension and clears public capabilities`() {
    val a = RecordingRegistry("a/kind")
    val registry = ExtensionRegistry(listOf(Extension(id = "ext/a", dataProductRegistry = a)))
    registry.enable(listOf("ext/a"))

    val out = registry.disable(listOf("ext/a"))
    assertEquals(listOf("ext/a"), out.disabled)
    assertEquals(listOf("ext/a"), out.deactivated)
    assertTrue(out.stillActiveAsDependency.isEmpty())
    assertFalse(registry.isPubliclyEnabled("ext/a"))
    assertFalse(registry.isActive("ext/a"))
    assertTrue(registry.publicDataProductCapabilities().isEmpty())
  }

  @Test
  fun `disabling a public extension keeps it active when another dependent extension still wants it`() {
    val a = RecordingRegistry("a/kind")
    val b = RecordingRegistry("b/kind")
    val registry =
      ExtensionRegistry(
        listOf(
          Extension(id = "ext/b", dataProductRegistry = b),
          Extension(id = "ext/a", dataProductRegistry = a, dependencies = listOf("ext/b")),
        )
      )
    registry.enable(listOf("ext/a", "ext/b"))

    val out = registry.disable(listOf("ext/b"))
    assertEquals(listOf("ext/b"), out.disabled)
    assertTrue(out.deactivated.isEmpty())
    assertEquals(listOf("ext/b"), out.stillActiveAsDependency)
    assertFalse(registry.isPubliclyEnabled("ext/b"))
    assertTrue(registry.isActive("ext/b"))
  }

  @Test
  fun `active dataProducts onRender fans out to public and dep extensions`() {
    val a = RecordingRegistry("a/kind")
    val b = RecordingRegistry("b/kind")
    val registry =
      ExtensionRegistry(
        listOf(
          Extension(id = "ext/b", dataProductRegistry = b),
          Extension(id = "ext/a", dataProductRegistry = a, dependencies = listOf("ext/b")),
        )
      )
    registry.enable(listOf("ext/a"))

    val result =
      RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test", pngPath = null)
    registry.activeDataProducts().onRender(previewId = "p1", result = result)
    assertEquals(1, a.onRenderCount.get())
    assertEquals(1, b.onRenderCount.get())
  }

  @Test
  fun `public dataProducts fetch routes only to public extension and skips deps`() {
    val a = RecordingRegistry("a/kind")
    val b = RecordingRegistry("b/kind")
    val registry =
      ExtensionRegistry(
        listOf(
          Extension(id = "ext/b", dataProductRegistry = b),
          Extension(id = "ext/a", dataProductRegistry = a, dependencies = listOf("ext/b")),
        )
      )
    registry.enable(listOf("ext/a"))

    val outcomeOk =
      registry
        .publicDataProducts()
        .fetch(previewId = "p1", kind = "a/kind", params = null, inline = false)
    assertTrue(outcomeOk is DataProductRegistry.Outcome.Ok)
    assertEquals(1, a.fetchCount.get())

    val outcomeUnknown =
      registry
        .publicDataProducts()
        .fetch(previewId = "p1", kind = "b/kind", params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcomeUnknown)
    assertEquals(0, b.fetchCount.get())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `dependency cycle is rejected at construction`() {
    ExtensionRegistry(
      listOf(
        Extension(id = "a", dependencies = listOf("b")),
        Extension(id = "b", dependencies = listOf("a")),
      )
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `unknown dependency is rejected at construction`() {
    ExtensionRegistry(listOf(Extension(id = "a", dependencies = listOf("b"))))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `duplicate ids are rejected at construction`() {
    ExtensionRegistry(listOf(Extension(id = "a"), Extension(id = "a")))
  }
}
