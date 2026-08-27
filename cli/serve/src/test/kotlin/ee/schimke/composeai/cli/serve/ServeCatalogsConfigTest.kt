package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The catalog set as **operator config** ([ServeCatalogsConfig]): parsing, validation, and the
 * read/modify/write round-trip the admin API depends on. Everything runs against a [FakeFileSystem]
 * — no disk, no server.
 */
class ServeCatalogsConfigTest {

  private val fs = FakeFileSystem()
  private val path = "/config/catalogs.json".toPath()

  @AfterTest
  fun tearDown() {
    fs.checkNoOpenFiles()
  }

  @Test
  fun `a catalog entry needs only its system id`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        { "catalogs": [ { "system": "compose-m3" } ] }
        """
          .trimIndent()
      )

    val entry = config.catalogs.single()
    assertEquals("compose-m3", entry.system)
    // No repo ⇒ the server's --catalog-repo; listed by default (the front door is the point).
    assertEquals(null, entry.repo)
    assertTrue(entry.listed)
    assertEquals(emptyList(), config.problems())
  }

  @Test
  fun `load priority defaults to zero and survives a round trip`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        {
          "catalogs": [
            { "system": "m3-catalog", "loadPriority": 20 },
            { "system": "cadence" }
          ]
        }
        """
          .trimIndent()
      )

    assertEquals(listOf(20, 0), config.catalogs.map { it.loadPriority })
    assertEquals(emptyList(), config.problems())
    // The admin API rewrites this file, so a priority an operator declared has to survive that.
    assertEquals(
      listOf(20, 0),
      ServeCatalogsConfig.parse(ServeCatalogsConfig.encode(config)).catalogs.map {
        it.loadPriority
      },
    )
  }

  @Test
  fun `unknown keys are ignored so a newer config still boots an older server`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        { "catalogs": [ { "system": "cadence", "listed": false, "somethingNew": 7 } ] }
        """
          .trimIndent()
      )

    assertEquals(false, config.catalogs.single().listed)
  }

  @Test
  fun `malformed ids, unknown groups, and duplicates are reported rather than thrown`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        {
          "groups": [ { "id": "design-systems", "heading": "Design Systems" } ],
          "catalogs": [
            { "system": "../etc/passwd" },
            { "system": "ok-one", "repo": "not-a-repo" },
            { "system": "ok-two", "group": "nope" },
            { "system": "dupe" },
            { "system": "dupe" }
          ]
        }
        """
          .trimIndent()
      )

    val problems = config.problems()
    assertTrue(problems.any { it.contains("../etc/passwd") }, problems.toString())
    assertTrue(problems.any { it.contains("invalid repo") }, problems.toString())
    assertTrue(problems.any { it.contains("unknown group 'nope'") }, problems.toString())
    assertTrue(problems.any { it.contains("duplicate catalog system 'dupe'") }, problems.toString())
  }

  @Test
  fun `a catalog named after one of the server's own routes is reported`() {
    // `api` is a perfectly good slug, so the id regex accepts it — but the registry refuses to name
    // a session after a rooted route, so such a catalog is scheduled for loading and then fails at
    // registration. That is a runtime error where the operator should have got the ordinary
    // malformed-entry warning and a skip, so the rule belongs in validation, which the startup
    // filter, `problems()` and the admin add all share.
    val config =
      ServeCatalogsConfig.parse(
        """
        { "catalogs": [ { "system": "api" }, { "system": "status" }, { "system": "wear-m3" } ] }
        """
          .trimIndent()
      )

    val problems = config.problems()
    assertEquals(2, problems.size, problems.toString())
    for (reserved in listOf("api", "status")) {
      assertTrue(
        problems.any { it.contains("'$reserved' is one of the server's own routes") },
        problems.toString(),
      )
    }
    assertNull(ServeCatalogsConfig.validateEntry(ServeCatalogsConfig.Entry(system = "wear-m3")))
  }

  @Test
  fun `a group is resolved by id`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        {
          "groups": [
            { "id": "samples", "heading": "android/compose-samples", "noun": "sample(s)" }
          ],
          "catalogs": [ { "system": "jetnews", "group": "samples" } ]
        }
        """
          .trimIndent()
      )

    val group = config.groupFor(config.catalogs.single())
    assertEquals("android/compose-samples", group?.heading)
    assertEquals("sample(s)", group?.noun)
  }

  @Test
  fun `an absent file reads as empty rather than failing`() {
    assertEquals(ServeCatalogsConfig.EMPTY, ServeCatalogsConfigFile(path, fs).load())
  }

  @Test
  fun `a saved config round-trips`() {
    val file = ServeCatalogsConfigFile(path, fs)
    val config =
      ServeCatalogsConfig(
        groups = listOf(ServeCatalogsConfig.Group("ds", "Design Systems", "design system(s)")),
        catalogs =
          listOf(
            ServeCatalogsConfig.Entry("compose-m3", "yschimke/compose-ai-tools", group = "ds"),
            ServeCatalogsConfig.Entry("cadence", "yschimke/cadence", listed = false),
          ),
      )

    file.save(config)

    assertEquals(config, file.load())
    // The staging file the atomic write goes through must not be left behind.
    assertEquals(listOf(path), fs.list(path.parent!!))
  }

  @Test
  fun `adding and removing entries preserves the rest of the config`() {
    val config =
      ServeCatalogsConfig(
        groups = listOf(ServeCatalogsConfig.Group("ds", "Design Systems")),
        catalogs = listOf(ServeCatalogsConfig.Entry("compose-m3", group = "ds")),
      )

    val added = config.withEntry(ServeCatalogsConfig.Entry("cadence", listed = false))
    assertEquals(listOf("compose-m3", "cadence"), added.catalogs.map { it.system })
    assertEquals(config.groups, added.groups)

    // Re-adding a known system replaces it in place rather than duplicating it.
    val replaced = added.withEntry(ServeCatalogsConfig.Entry("cadence", "other/repo"))
    assertEquals(listOf("compose-m3", "cadence"), replaced.catalogs.map { it.system })
    assertEquals("other/repo", replaced.catalogs.last().repo)

    assertEquals(listOf("compose-m3"), replaced.withoutEntry("cadence").catalogs.map { it.system })
  }

  @Test
  fun `save writes a file the operator can read and edit`() {
    ServeCatalogsConfigFile(path, fs)
      .save(ServeCatalogsConfig(catalogs = listOf(ServeCatalogsConfig.Entry("compose-m3"))))

    val text = fs.read(path) { readUtf8() }
    assertTrue(text.contains("\"system\": \"compose-m3\""), text)
    assertTrue(text.endsWith("\n"), "config files end with a newline")
  }
}
