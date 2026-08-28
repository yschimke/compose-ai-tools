package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Runtime site administration ([ServeSiteAdmin]): publishing and retiring the hostnames a catalog
 * is served on **without a restart**, and writing them back to the operator's `catalogs.json`.
 *
 * The gap this closes is worth naming, because it is the reason the routes exist rather than a
 * nicety: `sites` was the only part of the committed deployment config with no admin route, so a
 * hostname added to `catalogs.json` on `main` reached a running box through nothing at all — the
 * file is seeded once and never overwritten, and the reconcile had no route to POST it to. Standing
 * a site up meant hand-editing the box's untracked `.env` and recreating the container.
 */
class ServeSiteAdminTest {

  private val fs = FakeFileSystem()
  private val path = "/config/catalogs.json".toPath()
  private val file = ServeCatalogsConfigFile(path, fs)

  private val served = mutableSetOf("m3-catalog", "wear-m3-catalog")

  private fun admin(
    registry: ServeSiteRegistry,
    configFile: ServeCatalogsConfigFile? = file,
  ): ServeSiteAdmin =
    ServeSiteAdmin(
      registry = registry,
      servedSystems = { served },
      configFile = configFile,
      onLog = {},
    )

  private fun site(host: String, system: String) = ServeCatalogsConfig.Site(host, system)

  @Test
  fun `publishing a site routes the host immediately and writes it back`() {
    file.save(
      ServeCatalogsConfig(
        catalogs = listOf(ServeCatalogsConfig.Entry(system = "wear-m3-catalog", repo = "a/b"))
      )
    )
    val registry = ServeSiteRegistry.empty()
    val result = admin(registry).add(site("wear.preview.coo.ee", "wear-m3-catalog"))

    assertEquals(ServeSiteAdmin.Result.Ok("wear.preview.coo.ee", warning = null), result)
    // The LIVE map, which is what a request in flight reads — not a copy that needs a restart.
    assertEquals("wear-m3-catalog", registry.systemFor("wear.preview.coo.ee"))
    assertEquals(
      listOf(ServeCatalogsConfig.Site("wear.preview.coo.ee", "wear-m3-catalog")),
      file.load().sites,
    )
    // The catalog set it was written beside is untouched: a site adds no catalog.
    assertEquals(listOf("wear-m3-catalog"), file.load().catalogs.map { it.system })
  }

  @Test
  fun `a host is matched the way a Host header arrives — case, port and trailing dot`() {
    val registry = ServeSiteRegistry.empty()
    val result = admin(registry).add(site("WEAR.Preview.Coo.EE", "wear-m3-catalog"))
    assertTrue(result is ServeSiteAdmin.Result.Ok, "$result")
    assertEquals("wear-m3-catalog", registry.systemFor("wear.preview.coo.ee:8080"))
    assertEquals("wear.preview.coo.ee", file.load().sites.single().host)
  }

  @Test
  fun `a site naming a catalog this server does not serve is refused`() {
    val registry = ServeSiteRegistry.empty()
    val result = admin(registry).add(site("nope.preview.coo.ee", "not-published"))

    val invalid = assertNotNull(result as? ServeSiteAdmin.Result.Invalid)
    assertTrue(invalid.reason.contains("not-published"), invalid.reason)
    assertTrue(registry.isEmpty)
    // Nothing was written: a rejected site must not leave a hostname in the file that the next
    // restart would then try — and fail — to serve.
    assertEquals(emptyList(), file.load().sites)
  }

  @Test
  fun `a site whose system collides with a built-in route is refused`() {
    served += "render"
    val result = admin(ServeSiteRegistry.empty()).add(site("render.preview.coo.ee", "render"))
    val invalid = assertNotNull(result as? ServeSiteAdmin.Result.Invalid)
    assertTrue(invalid.reason.contains("built-in route"), invalid.reason)
  }

  @Test
  fun `a host that is not a hostname is refused rather than normalised into one`() {
    val result = admin(ServeSiteRegistry.empty()).add(site("not a host/", "m3-catalog"))
    assertTrue(result is ServeSiteAdmin.Result.Invalid, "$result")
  }

  @Test
  fun `re-posting the same site is a conflict, which is what makes the reconcile re-runnable`() {
    val registry = ServeSiteRegistry.of(listOf("m3.preview.coo.ee" to "m3-catalog"))
    val result = admin(registry).add(site("m3.preview.coo.ee", "m3-catalog"))

    // 409, which publish-config-to-box.sh counts as success — an unchanged committed config
    // re-applied on every push must not go red.
    assertTrue(result is ServeSiteAdmin.Result.Conflict, "$result")
    assertEquals("m3-catalog", registry.systemFor("m3.preview.coo.ee"))
  }

  @Test
  fun `re-pointing a host at another catalog converges in place`() {
    val registry = ServeSiteRegistry.of(listOf("m3.preview.coo.ee" to "m3-catalog"))
    val result = admin(registry).add(site("m3.preview.coo.ee", "wear-m3-catalog"))

    assertTrue(result is ServeSiteAdmin.Result.Ok, "$result")
    assertEquals("wear-m3-catalog", registry.systemFor("m3.preview.coo.ee"))
    // One entry, not two: a host maps to exactly one system, and the file has to say so or the
    // next boot picks whichever came first.
    assertEquals(
      listOf(ServeCatalogsConfig.Site("m3.preview.coo.ee", "wear-m3-catalog")),
      file.load().sites,
    )
  }

  @Test
  fun `retiring a site drops the host and leaves its neighbours serving`() {
    val registry =
      ServeSiteRegistry.of(
        listOf("m3.preview.coo.ee" to "m3-catalog", "wear.preview.coo.ee" to "wear-m3-catalog")
      )
    val result = admin(registry).remove("wear.preview.coo.ee")

    assertEquals(ServeSiteAdmin.Result.Ok("wear.preview.coo.ee", warning = null), result)
    assertNull(registry.systemFor("wear.preview.coo.ee"))
    assertEquals("m3-catalog", registry.systemFor("m3.preview.coo.ee"))
    assertEquals(listOf("m3.preview.coo.ee"), file.load().sites.map { it.host })
  }

  @Test
  fun `retiring a host that isn't configured is a conflict, not a silent success`() {
    val registry = ServeSiteRegistry.empty()
    val result = admin(registry).remove("wear.preview.coo.ee")
    assertTrue(result is ServeSiteAdmin.Result.Conflict, "$result")
  }

  @Test
  fun `a site published with no config file serves, and says it will not survive a restart`() {
    val registry = ServeSiteRegistry.empty()
    val result = admin(registry, configFile = null).add(site("m3.preview.coo.ee", "m3-catalog"))

    val ok = assertNotNull(result as? ServeSiteAdmin.Result.Ok)
    val warning = assertNotNull(ok.warning)
    assertTrue(warning.contains("not persisted"), warning)
    // Serving anyway: a registration that worked but couldn't be written back is not rolled back.
    assertEquals("m3-catalog", registry.systemFor("m3.preview.coo.ee"))
  }

  @Test
  fun `listing reports the configured sites in order`() {
    val registry =
      ServeSiteRegistry.of(
        listOf("m3.preview.coo.ee" to "m3-catalog", "wear.preview.coo.ee" to "wear-m3-catalog")
      )
    assertEquals(
      listOf(
        ServeCatalogsConfig.Site("m3.preview.coo.ee", "m3-catalog"),
        ServeCatalogsConfig.Site("wear.preview.coo.ee", "wear-m3-catalog"),
      ),
      admin(registry).list(),
    )
  }

  @Test
  fun `the written file round-trips through the startup path`() {
    // The value of writing it back is only realised if the NEXT boot reads the same thing, so
    // assert against the startup spelling ([ServeCatalogsConfig.siteMap]) rather than the map the
    // admin left in memory.
    file.save(
      ServeCatalogsConfig(
        catalogs = listOf(ServeCatalogsConfig.Entry(system = "wear-m3-catalog", repo = "a/b"))
      )
    )
    admin(ServeSiteRegistry.empty()).add(site("wear.preview.coo.ee", "wear-m3-catalog"))

    val reloaded = file.load()
    assertEquals(emptyList(), reloaded.problems())
    assertEquals("wear-m3-catalog", reloaded.siteMap().systemFor("wear.preview.coo.ee"))
  }
}
