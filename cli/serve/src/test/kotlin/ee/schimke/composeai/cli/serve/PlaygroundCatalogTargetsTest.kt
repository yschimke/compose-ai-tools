package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class PlaygroundCatalogTargetsTest {

  /**
   * Stand-in bundle bytes — [PlaygroundClasspathSupplier] requires a readable file before it
   * resolves, so the fake has to exist on disk even though `resolve` never opens it.
   */
  private val bundles =
    java.nio.file.Files.createTempDirectory("pg-catalog-targets").toFile().apply { deleteOnExit() }

  private fun classpath(system: String) =
    PlaygroundCompileService.Classpath(system, listOf("/cat/$system/app.jar".toPath()))

  /**
   * A supplier over a stand-in bundle file: real enough to clear the supplier's readability check,
   * with the expensive part ([PlaygroundCatalogClasspath.resolve]) replaced by a recorder. Mirrors
   * the production shape — a lazy, memoized, resolve-on-first-use classpath.
   */
  private fun supplier(
    system: String,
    resolvable: Boolean = true,
    resolves: MutableList<String> = mutableListOf(),
  ) =
    PlaygroundClasspathSupplier(
      source = PlaygroundBundleSource.ServedCatalog(system),
      locateServedBundle = { java.io.File(bundles, "$it.png").apply { writeText("bundle") } },
      resolve = {
        resolves += system
        if (resolvable) classpath(system) else null
      },
      onLog = {},
    )

  private fun targets(
    available: List<Pair<String, String>>,
    limit: Int = PlaygroundCatalogTargets.DEFAULT_LIMIT,
    androidWired: Boolean = true,
    rcWired: Boolean = true,
    suppliers: (String) -> PlaygroundClasspathSupplier,
    log: MutableList<String> = mutableListOf(),
  ) =
    PlaygroundCatalogTargets(
      available = {
        available.map { (system, backend) ->
          PlaygroundCatalogAvailable(system, system, "", backend)
        }
      },
      modesForBackend = { backend ->
        PlaygroundCatalogTargets.naturalModes(backend).filter {
          when (it) {
            PlaygroundMode.CMP -> true
            PlaygroundMode.ANDROID -> androidWired
            PlaygroundMode.REMOTE_COMPOSE -> rcWired
          }
        }
      },
      newSupplier = suppliers,
      limit = limit,
      onLog = { log += it },
    )

  @Test
  fun `a bundle backend decides the modes a catalog offers`() {
    assertEquals(listOf(PlaygroundMode.CMP), PlaygroundCatalogTargets.naturalModes("desktop"))
    assertEquals(
      listOf(PlaygroundMode.ANDROID, PlaygroundMode.REMOTE_COMPOSE),
      PlaygroundCatalogTargets.naturalModes("android"),
    )
    // A backend this build has no renderer for is not a catalog you can compile against, so it is
    // omitted from the selector rather than offered and refused on Run.
    assertEquals(emptyList(), PlaygroundCatalogTargets.naturalModes("wasm"))
  }

  @Test
  fun `a catalog whose modes are all unwired is not offered at all`() {
    val t =
      targets(
        available = listOf("m3" to "desktop", "wear" to "android"),
        androidWired = false,
        rcWired = false,
        suppliers = { supplier(it) },
      )
    assertEquals(listOf("m3"), t.targets().map { it.system })
  }

  @Test
  fun `an android catalog loses only the modes whose backend is missing`() {
    val t =
      targets(
        available = listOf("wear" to "android"),
        rcWired = false,
        suppliers = { supplier(it) },
      )
    assertEquals(listOf(PlaygroundMode.ANDROID), t.targets().single().modes)
    // …and asking for the missing one is a refusal, not a silent substitution.
    assertNull(t.classpath("wear", PlaygroundMode.REMOTE_COMPOSE))
    assertNotNull(t.classpath("wear", PlaygroundMode.ANDROID))
  }

  @Test
  fun `a catalog with no declared backend never reaches the selector`() {
    // `available` is already filtered to catalogs whose manifest could be read; an unknown backend
    // string still has to be survivable here, because the manifest is the catalog's to write.
    val t = targets(available = listOf("odd" to "fabric"), suppliers = { supplier(it) })
    assertEquals(emptyList(), t.targets())
    assertNull(t.classpath("odd", PlaygroundMode.CMP))
  }

  @Test
  fun `an unknown catalog is refused, never quietly served from another`() {
    val log = mutableListOf<String>()
    val t = targets(available = listOf("m3" to "desktop"), suppliers = { supplier(it) }, log = log)
    assertNull(t.classpath("not-served", PlaygroundMode.CMP))
    assertTrue(log.any { "not-served" in it }, "expected the refusal to name the catalog: $log")
  }

  @Test
  fun `a catalog resolves once and is memoized across requests`() {
    val resolves = mutableListOf<String>()
    val t =
      targets(
        available = listOf("m3" to "desktop"),
        suppliers = { supplier(it, resolves = resolves) },
      )
    assertEquals(classpath("m3"), t.classpath("m3", PlaygroundMode.CMP))
    assertEquals(classpath("m3"), t.classpath("m3", PlaygroundMode.CMP))
    assertEquals(listOf("m3"), resolves)
    assertEquals(1, t.resolvedCount())
    assertTrue(t.targets().single().resolved)
  }

  @Test
  fun `the resolved-catalog budget is a hard stop, not an eviction`() {
    val log = mutableListOf<String>()
    val t =
      targets(
        available = listOf("a" to "desktop", "b" to "desktop", "c" to "desktop"),
        limit = 2,
        suppliers = { supplier(it) },
        log = log,
      )
    assertNotNull(t.classpath("a", PlaygroundMode.CMP))
    assertNotNull(t.classpath("b", PlaygroundMode.CMP))
    // The third is refused rather than displacing one of the first two — their jars are open in
    // live snippet JVMs, so eviction is not a thing this can do.
    assertNull(t.classpath("c", PlaygroundMode.CMP))
    assertTrue(log.any { "budget spent" in it }, "expected a budget refusal: $log")
    // …and the ones already paid for keep working.
    assertEquals(classpath("a"), t.classpath("a", PlaygroundMode.CMP))
    assertEquals(2, t.resolvedCount())
  }

  @Test
  fun `a catalog that fails to resolve does not spend the budget`() {
    val t =
      targets(
        available = listOf("broken" to "desktop", "ok" to "desktop"),
        limit = 1,
        suppliers = { if (it == "broken") supplier(it, resolvable = false) else supplier(it) },
      )
    assertNull(t.classpath("broken", PlaygroundMode.CMP))
    assertEquals(0, t.resolvedCount())
    // The one budget slot is still there for a catalog that can actually use it.
    assertNotNull(t.classpath("ok", PlaygroundMode.CMP))
  }

  @Test
  fun `a retry of a previously failed catalog still respects a spent budget`() {
    // The supplier of a catalog whose FIRST resolve failed stays in the map, unresolved. If the cap
    // asked "have we seen this system before" rather than "is the budget spent", that retry would
    // sail past a full budget and resolve an N+1st catalog — a transient Maven miss would be all it
    // took to break the bound a public host relies on.
    var brokenResolvable = false
    val log = mutableListOf<String>()
    val t =
      targets(
        available = listOf("flaky" to "desktop", "a" to "desktop", "b" to "desktop"),
        limit = 2,
        suppliers = { system ->
          // Reads the flag at RESOLVE time, not at construction, so the retry below genuinely could
          // succeed — otherwise the test would pass with the cap removed.
          if (system == "flaky")
            PlaygroundClasspathSupplier(
              source = PlaygroundBundleSource.ServedCatalog(system),
              locateServedBundle = {
                java.io.File(bundles, "$it.png").apply { writeText("bundle") }
              },
              resolve = { if (brokenResolvable) classpath(system) else null },
              onLog = {},
            )
          else supplier(system)
        },
        log = log,
      )

    // First attempt fails and leaves an unresolved supplier behind.
    assertNull(t.classpath("flaky", PlaygroundMode.CMP))
    assertEquals(0, t.resolvedCount())
    // Two other catalogs then spend the whole budget.
    assertNotNull(t.classpath("a", PlaygroundMode.CMP))
    assertNotNull(t.classpath("b", PlaygroundMode.CMP))
    assertEquals(2, t.resolvedCount())

    // …and now the retry would succeed on its own terms. It must still be refused.
    brokenResolvable = true
    assertNull(t.classpath("flaky", PlaygroundMode.CMP))
    assertEquals(2, t.resolvedCount())
    assertTrue(log.any { "budget spent" in it }, "expected a budget refusal: $log")
  }

  @Test
  fun `catalogs are offered in a stable order`() {
    val t =
      targets(
        available = listOf("zebra" to "desktop", "alpha" to "desktop", "mid" to "desktop"),
        suppliers = { supplier(it) },
      )
    assertEquals(listOf("alpha", "mid", "zebra"), t.targets().map { it.system })
  }

  @Test
  fun `modules in one catalog resolve independent classpaths`() {
    val resolves = mutableListOf<String>()
    val available =
      listOf(
        PlaygroundCatalogAvailable("all", "all", ":mobile", "android"),
        PlaygroundCatalogAvailable("all@:tv", "all", ":tv", "android"),
      )
    val t =
      PlaygroundCatalogTargets(
        available = { available },
        modesForBackend = PlaygroundCatalogTargets::naturalModes,
        newSupplier = { id -> supplier(id, resolves = resolves) },
      )

    assertEquals(listOf(":mobile", ":tv"), t.targets().map { it.module })
    assertNotNull(t.classpath("all@:tv", PlaygroundMode.ANDROID))
    assertEquals(listOf("all@:tv"), resolves)
    assertTrue(t.targets().single { it.module == ":tv" }.resolved)
    assertTrue(!t.targets().single { it.module == ":mobile" }.resolved)
  }
}
