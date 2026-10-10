package ee.schimke.composeai.discovery

import org.junit.Assert.*
import org.junit.Assert.assertThrows
import org.junit.Test

class TypedAdapterDiscoveryTest {
  private val entry =
    TypedAdapterDiscovery.Callable(
      "ee.schimke.composeai.discovery.TypedAdapterDiscoveryTestKt",
      "adapterDiscoveryFixture",
    )

  @Test
  fun `an external export entry can recover a compiled signature without executing app code`() {
    val records = TypedAdapterDiscovery.discover(":app", "jvm", listOf(entry))
    val record = records.components.single()
    assertTrue(record.signatureKnown)
    assertEquals("ee.schimke.composeai.discovery.adapterDiscoveryFixture", record.symbol.callable)
    assertEquals(listOf("label", "onChange"), record.parameters.map { it.name })
    assertEquals("kotlin.String", record.parameters.first().typeFqn)
    assertEquals("kotlin.Unit", record.parameters.last().lambdaReturnTypeFqn)
    assertTrue(record.parameters.first().hasDefault)
    assertNotNull(record.symbol.descriptor)
  }

  @Test
  fun `missing and overloaded entry points are refused rather than guessed`() {
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discover(":app", "jvm", listOf(entry.copy(name = "renamed")))
    }
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discover(
        ":app",
        "jvm",
        listOf(entry.copy(name = "adapterDiscoveryOverload")),
      )
    }
  }

  @Test
  fun `zero argument entry points are known rather than missing metadata`() {
    val record =
      TypedAdapterDiscovery.discover(":app", "jvm", listOf(entry.copy(name = "adapterNoArgs")))
        .components
        .single()
    assertTrue(record.signatureKnown)
    assertTrue(record.parameters.isEmpty())
    assertEquals("adapterNoArgs()", record.code!!.call)
  }

  @Test
  fun `source and JVM names stay distinct for annotated wrappers`() {
    val record =
      TypedAdapterDiscovery.discover(":app", "jvm", listOf(entry.copy(name = "adapterJvmAlias")))
        .components
        .single()
    assertEquals("ee.schimke.composeai.discovery.adapterSourceName", record.symbol.callable)
    assertEquals("adapterJvmAlias", record.symbol.jvmName)
    val normal =
      ComponentRecords.from(
          PreviewManifest(
            module = ":app",
            variant = "jvm",
            previews =
              listOf(
                PreviewInfo(
                  id = "alias-preview",
                  functionName = "preview",
                  className = entry.owner,
                  targets =
                    listOf(
                      PreviewTarget(
                        className = entry.owner,
                        functionName = "adapterSourceName",
                        jvmName = "adapterJvmAlias",
                        confidence = TargetConfidence.HIGH,
                        signatureKnown = true,
                      )
                    ),
                )
              ),
          )
        )
        .components
        .single()
    assertEquals(normal.canonicalId, record.canonicalId)
    val adapter = TypedComponentAdapter<Unit>("acme/aliased", record)
    val generated =
      TypedAdapterCatalog.generate(
        ComponentRecordFile.Builder(":app", "jvm", listOf(record)).build(),
        UiBuilderCatalogs.CoverSheet("acme", "Acme"),
        UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
        listOf(adapter),
      )
    assertEquals(normal.canonicalId, generated.record.components.single().canonicalId)
  }

  @Test
  fun `static members are refused even when their JVM signature looks top level`() {
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discover(
        ":app",
        "jvm",
        listOf(
          TypedAdapterDiscovery.Callable(
            "ee.schimke.composeai.discovery.AdapterMemberFixture",
            "member",
          )
        ),
      )
    }
  }

  @Test
  fun `duplicate selections cannot create conflicting records`() {
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discover(":app", "jvm", listOf(entry, entry))
    }
  }
}

@Suppress("UNUSED_PARAMETER")
fun adapterDiscoveryFixture(label: String = "Default", onChange: (String) -> Unit): Unit =
  error("discovery must never execute this")

@Suppress("UNUSED_PARAMETER")
fun adapterDiscoveryOverload(label: String): Unit = error("not executed")

@Suppress("UNUSED_PARAMETER") fun adapterDiscoveryOverload(label: Int): Unit = error("not executed")

fun adapterNoArgs(): Unit = error("not executed")

@JvmName("adapterJvmAlias") fun adapterSourceName(): Unit = error("not executed")

object AdapterMemberFixture {
  @JvmStatic fun member(): Unit = error("not executed")
}

// Discovery must read class bytes without triggering the export facade's initializer.
@Suppress("unused")
private val adapterFacadeInitialization: Nothing = error("discovery must never initialize app code")
