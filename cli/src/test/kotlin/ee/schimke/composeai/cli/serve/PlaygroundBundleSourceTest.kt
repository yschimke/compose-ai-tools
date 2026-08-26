package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okio.Path.Companion.toOkioPath

/**
 * Reading `--playground-bundle` as either a local path or a served catalog system id (issue #3212).
 * The split has to be structural, not a guess: an operator's `/config/x.bundle` and a system id
 * like `compose-m3` are both plain strings, and reading one as the other either disables the lane
 * or hunts for a file that was never meant to exist.
 */
class PlaygroundBundleSourceTest {

  @Test
  fun `anything with a path separator is a path`() {
    assertEquals(
      PlaygroundBundleSource.LocalPath("/config/playground-cmp.bundle"),
      PlaygroundBundleSource.parse("/config/playground-cmp.bundle") { false },
    )
    assertEquals(
      PlaygroundBundleSource.LocalPath("./x"),
      PlaygroundBundleSource.parse("./x") { false },
    )
    assertEquals(
      PlaygroundBundleSource.LocalPath("C:\\bundles\\x"),
      PlaygroundBundleSource.parse("C:\\bundles\\x") { false },
    )
  }

  @Test
  fun `a bare token is a served catalog system`() {
    assertEquals(
      PlaygroundBundleSource.ServedCatalog("compose-m3"),
      PlaygroundBundleSource.parse("compose-m3") { false },
    )
    // Whitespace an operator's .env picked up must not become part of the system id.
    assertEquals(
      PlaygroundBundleSource.ServedCatalog("wear-m3"),
      PlaygroundBundleSource.parse("  wear-m3  ") { false },
    )
  }

  @Test
  fun `a bare token that is a bundle file is a path, by suffix or by existing`() {
    // A catalog liveBundle is a packed .png, so a bare `bundle.png` is a file, not a system.
    assertEquals(
      PlaygroundBundleSource.LocalPath("compose-m3-bundle.png"),
      PlaygroundBundleSource.parse("compose-m3-bundle.png") { false },
    )
    assertEquals(
      PlaygroundBundleSource.LocalPath("x.bundle"),
      PlaygroundBundleSource.parse("x.bundle") { false },
    )
    // …and a suffix-less token that names a real file in the working directory is that file.
    assertEquals(
      PlaygroundBundleSource.LocalPath("compose-m3"),
      PlaygroundBundleSource.parse("compose-m3") { it == "compose-m3" },
    )
  }

  @Test
  fun `describe names the served catalog so a startup log is not ambiguous`() {
    assertTrue("compose-m3" in PlaygroundBundleSource.ServedCatalog("compose-m3").describe())
    assertEquals(
      "/config/x.bundle",
      PlaygroundBundleSource.LocalPath("/config/x.bundle").describe(),
    )
  }
}

/**
 * The deferred resolve (issue #3212). A served-catalog source names a bundle that does not exist
 * when the playground lane is wired — `InitialCatalogLoader` fetches it after the server is up — so
 * the supplier must keep answering "not yet" without poisoning the mode, then resolve once the
 * bundle lands.
 */
class PlaygroundClasspathSupplierTest {

  private val classpath =
    PlaygroundCompileService.Classpath(
      moduleName = "compose-m3",
      entries = listOf(File("/cache/m3.jar").toOkioPath()),
    )

  private fun bundleFile(): File =
    Files.createTempDirectory("pg-supplier").resolve("bundle.png").toFile().apply {
      writeBytes(byteArrayOf(1, 2, 3))
    }

  @Test
  fun `the startup source classification remains available without reparsing`() {
    val served =
      PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.ServedCatalog("compose-m3"),
        locateServedBundle = { null },
        resolve = { classpath },
      )
    val local =
      PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.LocalPath("compose-m3"),
        locateServedBundle = { null },
        resolve = { classpath },
      )

    assertEquals("compose-m3", served.servedCatalogSystem)
    assertNull(local.servedCatalogSystem)
  }

  @Test
  fun `a served catalog resolves on first use, not at construction`() {
    val bundle = bundleFile()
    var located: File? = null
    var resolves = 0
    val supplier =
      PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.ServedCatalog("compose-m3"),
        locateServedBundle = { if (it == "compose-m3") located else null },
        resolve = {
          resolves++
          classpath
        },
      )

    // Startup: the catalog has not loaded, so the mode reports unavailable — and nothing is cached.
    assertNull(supplier.classpath())
    assertEquals(0, resolves)

    // The catalog lands…
    located = bundle
    assertSame(classpath, supplier.classpath())
    assertEquals(1, resolves)

    // …and the second read is the memoized one, not a second unpack.
    assertSame(classpath, supplier.classpath())
    assertEquals(1, resolves)
  }

  @Test
  fun `a resolved classpath is pinned even when the catalog's bundle moves underneath it`() {
    // Auto-refresh re-points a catalog at a new bundle while snippet JVMs hold the old jars open.
    // Following that is explicitly out of scope for #3212; this asserts the safe behaviour rather
    // than leaving it to chance.
    val first = bundleFile()
    var current = first
    var resolves = 0
    val supplier =
      PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.ServedCatalog("compose-m3"),
        locateServedBundle = { current },
        resolve = {
          resolves++
          classpath
        },
      )

    assertSame(classpath, supplier.classpath())
    current = bundleFile()

    assertSame(classpath, supplier.classpath())
    assertEquals(1, resolves, "a refreshed branch head must not re-resolve under live snippets")
  }

  @Test
  fun `a missing local path stays unresolved and is retried, not cached as a failure`() {
    var resolves = 0
    val supplier =
      PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.LocalPath("/does/not/exist.bundle"),
        locateServedBundle = { null },
        resolve = {
          resolves++
          classpath
        },
      )

    assertNull(supplier.classpath())
    assertNull(supplier.classpath())
    assertEquals(0, resolves, "a file that isn't there never reaches the resolver")
  }

  @Test
  fun `a bundle that will not resolve is retried rather than latched`() {
    val bundle = bundleFile()
    var fails = 2
    var resolves = 0
    val supplier =
      PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.LocalPath(bundle.absolutePath),
        locateServedBundle = { null },
        resolve = {
          resolves++
          if (fails-- > 0) null else classpath
        },
      )

    assertNull(supplier.classpath())
    assertNull(supplier.classpath())
    assertSame(classpath, supplier.classpath())
    assertEquals(3, resolves)
    assertTrue(supplier.isResolved)
  }

  @Test
  fun `the reason a served catalog is not ready reaches the log`() {
    val logs = mutableListOf<String>()
    PlaygroundClasspathSupplier(
        source = PlaygroundBundleSource.ServedCatalog("wear-m3"),
        locateServedBundle = { null },
        resolve = { classpath },
        onLog = { logs += it },
      )
      .classpath()

    assertTrue(logs.any { "wear-m3" in it }, logs.toString())
  }
}
