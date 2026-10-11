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

  private val library =
    TypedAdapterDiscovery.LibraryCallable(
      "ee.schimke.composeai.discovery.TypedAdapterDiscoveryTestKt",
      "libraryOverloaded",
    )

  @Test
  fun `a library callable publishes every overload and takes its parameters from the one named`() {
    val record =
      TypedAdapterDiscovery.discoverLibrary(
          "lib",
          "release",
          listOf(library.copy(declaring = setOf("label"))),
        )
        .components
        .single()
    assertEquals(ComponentOrigin.LIBRARY, record.symbol.origin)
    assertEquals(listOf("onClick", "label"), record.parameters.map { it.name })
    // The deprecated overload is neither picked nor published.
    assertEquals(
      setOf(listOf("onClick", "label"), listOf("onClick", "content")),
      record.overloads.map { o -> o.parameters.map { it.name } }.toSet(),
    )
    val content =
      TypedAdapterDiscovery.discoverLibrary(
          "lib",
          "release",
          listOf(library.copy(declaring = setOf("content"))),
        )
        .components
        .single()
    assertEquals(listOf("onClick", "content"), content.parameters.map { it.name })
  }

  @Test
  fun `an overloaded library callable with no names to pick one is refused, naming its overloads`() {
    val refused =
      assertThrows(IllegalArgumentException::class.java) {
        TypedAdapterDiscovery.discoverLibrary("lib", "release", listOf(library))
      }
    assertTrue(refused.message!!, "label: kotlin.String)" in refused.message!!)
    assertTrue(refused.message!!, "content: kotlin.Int)" in refused.message!!)
  }

  @Test
  fun `overloads that differ only in a type are picked by naming it`() {
    val typed =
      TypedAdapterDiscovery.LibraryCallable(
        "ee.schimke.composeai.discovery.TypedAdapterDiscoveryTestKt",
        "libraryTyped",
      )
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discoverLibrary("lib", "release", listOf(typed))
    }
    val record =
      TypedAdapterDiscovery.discoverLibrary(
          "lib",
          "release",
          listOf(typed.copy(types = mapOf("text" to "kotlin.String"))),
        )
        .components
        .single()
    assertEquals("kotlin.String", record.parameters.single().typeFqn)
    assertEquals(2, record.overloads.size)
  }

  @Test
  fun `an overload that adds a parameter to another is excluded by naming it`() {
    val painted =
      TypedAdapterDiscovery.LibraryCallable(
        "ee.schimke.composeai.discovery.TypedAdapterDiscoveryTestKt",
        "libraryPainted",
        declaring = setOf("label"),
      )
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discoverLibrary("lib", "release", listOf(painted))
    }
    val record =
      TypedAdapterDiscovery.discoverLibrary(
          "lib",
          "release",
          listOf(painted.copy(excluding = setOf("painter"))),
        )
        .components
        .single()
    assertEquals(listOf("label"), record.parameters.map { it.name })
  }

  @Test
  fun `only composable overloads are candidates, never a plain helper of the same name`() {
    val record =
      TypedAdapterDiscovery.discoverLibrary(
          "lib",
          "release",
          listOf(
            TypedAdapterDiscovery.LibraryCallable(
              "ee.schimke.composeai.discovery.TypedAdapterDiscoveryTestKt",
              "libraryPainted",
              declaring = setOf("label"),
              excluding = setOf("painter"),
            )
          ),
        )
        .components
        .single()
    assertTrue(record.overloads.none { o -> o.parameters.any { it.name == "helper" } })
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
  fun `a factory's overloads are discovered with names, defaults and return type`() {
    val owner = "ee.schimke.composeai.discovery.AdapterFactoryFixture"
    val overloads =
      TypedAdapterDiscovery.discoverFactories(
        listOf(TypedAdapterDiscovery.Callable(owner, "colors"))
      )
    assertEquals(2, overloads.size)
    assertTrue(overloads.all { it.callableFqn == "$owner.colors" })
    assertTrue(
      overloads.all { it.returnTypeFqn == "ee.schimke.composeai.discovery.AdapterFactoryColors" }
    )
    val full = overloads.single { it.parameters.isNotEmpty() }
    assertEquals(listOf("container", "content"), full.parameters.map { it.name })
    assertEquals(listOf("kotlin.Long", "kotlin.String"), full.parameters.map { it.typeFqn })
    assertEquals(listOf(false, true), full.parameters.map { it.hasDefault })
  }

  @Test
  fun `a factory's return type carries every supertype, transitively`() {
    val spaced =
      TypedAdapterDiscovery.discoverFactories(
          listOf(
            TypedAdapterDiscovery.Callable(
              "ee.schimke.composeai.discovery.AdapterFactoryFixture",
              "spaced",
            )
          )
        )
        .single()
    assertEquals(
      listOf(
          "AdapterFactoryAxis",
          "AdapterFactoryBase",
          "AdapterFactoryHorizontal",
          "AdapterFactoryVertical",
        )
        .map { "ee.schimke.composeai.discovery.$it" }
        .toSet(),
      spaced.returnSupertypes,
    )
    val nested =
      TypedAdapterDiscovery.discoverFactories(
          listOf(
            TypedAdapterDiscovery.Callable(
              "ee.schimke.composeai.discovery.AdapterFactoryFixture",
              "nested",
            )
          )
        )
        .single()
    assertEquals("ee.schimke.composeai.discovery.AdapterFactoryNesting.Both", nested.returnTypeFqn)
    assertEquals(
      setOf("ee.schimke.composeai.discovery.AdapterFactoryNesting.Vertical"),
      nested.returnSupertypes,
    )
    // A final class with nothing above it but `Any` has none.
    val colors =
      TypedAdapterDiscovery.discoverFactories(
        listOf(
          TypedAdapterDiscovery.Callable(
            "ee.schimke.composeai.discovery.AdapterFactoryFixture",
            "colors",
          )
        )
      )
    assertTrue(colors.all { it.returnSupertypes.isEmpty() })
  }

  @Test
  fun `an unknown factory is refused rather than guessed`() {
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discoverFactories(
        listOf(
          TypedAdapterDiscovery.Callable(
            "ee.schimke.composeai.discovery.AdapterFactoryFixture",
            "colours",
          )
        )
      )
    }
  }

  @Test
  fun `a class's member is not a factory, since its call needs an instance`() {
    assertThrows(IllegalArgumentException::class.java) {
      TypedAdapterDiscovery.discoverFactories(
        listOf(
          TypedAdapterDiscovery.Callable(
            "ee.schimke.composeai.discovery.AdapterFactoryColors",
            "derive",
          )
        )
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

class AdapterFactoryColors {
  fun derive(): AdapterFactoryColors = error("not executed")
}

interface AdapterFactoryAxis

interface AdapterFactoryVertical : AdapterFactoryAxis

interface AdapterFactoryHorizontal : AdapterFactoryAxis

abstract class AdapterFactoryBase

class AdapterFactorySpacing : AdapterFactoryBase(), AdapterFactoryVertical, AdapterFactoryHorizontal

/** Nested, as `Arrangement.HorizontalOrVertical` is: metadata and class files spell it apart. */
object AdapterFactoryNesting {
  interface Vertical

  interface Both : Vertical
}

@Suppress("UNUSED_PARAMETER")
object AdapterFactoryFixture {
  fun spaced(space: Int): AdapterFactorySpacing = error("not executed")

  fun nested(space: Int): AdapterFactoryNesting.Both = error("not executed")

  fun colors(): AdapterFactoryColors = error("not executed")

  fun colors(container: Long, content: String = ""): AdapterFactoryColors = error("not executed")
}

// Discovery must read class bytes without triggering the export facade's initializer.
@Suppress("unused")
private val adapterFacadeInitialization: Nothing = error("discovery must never initialize app code")

@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryOverloaded(onClick: () -> Unit, label: String): Unit = error("not executed")

@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryOverloaded(onClick: () -> Unit, content: Int): Unit = error("not executed")

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryOverloaded(onClick: () -> Unit, old: Boolean): Unit = error("not executed")

@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryTyped(text: String): Unit = error("not executed")

@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryTyped(text: CharSequence): Unit = error("not executed")

@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryPainted(label: String): Unit = error("not executed")

@Suppress("UNUSED_PARAMETER")
@androidx.compose.runtime.Composable
fun libraryPainted(painter: Int, label: String): Unit = error("not executed")

/** A plain helper beside a composable of the same name: never a component. */
@Suppress("UNUSED_PARAMETER") fun libraryPainted(helper: Long): Unit = error("not executed")
