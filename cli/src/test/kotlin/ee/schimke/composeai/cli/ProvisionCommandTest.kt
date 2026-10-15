package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProvisionCommandTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("provision").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  /** Every step recorded, nothing fetched; [failing] parts throw as an unreachable host would. */
  private class FakeSteps(val root: File, val failing: Set<String> = emptySet()) : ProvisionSteps {
    val calls = mutableListOf<String>()
    val cache = File(root, "cache/composeai")

    private fun maybeFail(step: String) {
      calls += step
      if (step in failing) error("$step: HTTP 403")
    }

    override fun sidecar(sidecar: DaemonSidecarProvision.Sidecar): List<File> {
      maybeFail(sidecar.name)
      return sidecar.directories.map { name ->
        File(cache, "preview-daemon/1.0/$name").apply {
          mkdirs()
          File(this, "$name.jar").writeText("")
        }
      }
    }

    override fun skiko(desktopSidecarJars: List<File>): File {
      maybeFail("skiko")
      assertTrue(desktopSidecarJars.any { it.name == "lib-renderer.jar" })
      return File(cache, "skiko/skiko-awt-runtime.jar")
    }

    override fun robolectricDepsDir(): File = File(cache, "robolectric-deps")

    override fun androidAll(
      androidSidecarJars: List<File>,
      apiLevel: Int,
    ): RobolectricDepsProvision.Resolved {
      maybeFail("android-all $apiLevel")
      assertEquals(listOf("lib-daemon-android.jar"), androidSidecarJars.map { it.name })
      return RobolectricDepsProvision.Resolved(
        apiLevel,
        RobolectricDepsProvision.Artifact(
          "org.robolectric",
          "android-all-instrumented",
          "$apiLevel-robolectric-1-i7",
        ),
        requiredJava = 17,
      )
    }

    override fun fetchAndroidAll(
      artifact: RobolectricDepsProvision.Artifact,
      directory: File,
    ): File {
      calls += "fetch ${artifact.fileName}"
      return File(directory, artifact.fileName)
    }

    override fun distribution(distribution: ReleasedDistribution): File? {
      maybeFail(distribution.binary)
      return File(cache, "${distribution.cacheDirName}/4.2.0/bin/${distribution.binary}")
    }

    override fun xr(): File? {
      maybeFail("xr")
      return null
    }

    override fun cacheRoot(): File = cache
  }

  @Test
  fun `parts default to desktop and android, and --all takes every one`() {
    assertEquals(
      setOf(ProvisionCommand.Part.DESKTOP, ProvisionCommand.Part.ANDROID),
      ProvisionCommand.parts(emptyList()),
    )
    assertEquals(setOf(ProvisionCommand.Part.MCP), ProvisionCommand.parts(listOf("--mcp")))
    assertEquals(
      ProvisionCommand.Part.entries.toSet(),
      ProvisionCommand.parts(listOf("--all", "--json")),
    )
  }

  @Test
  fun `android levels default to the render default and accept lists`() {
    assertEquals(listOf(AndroidBundleLaunch.DEFAULT_SDK), ProvisionCommand.androidLevels(listOf()))
    assertEquals(
      listOf(35, 36),
      ProvisionCommand.androidLevels(listOf("--android-sdk", "35,36", "--android-sdk=36")),
    )
    assertFailsWith<IllegalArgumentException> {
      ProvisionCommand.androidLevels(listOf("--android-sdk", "Baklava"))
    }
    assertFailsWith<IllegalArgumentException> {
      ProvisionCommand.androidLevels(listOf("--android-sdk", "19"))
    }
  }

  @Test
  fun `an android-sdk flag without levels is an error, not a silent default`() {
    for (args in
      listOf(
        listOf("--android", "--android-sdk="),
        listOf("--android", "--android-sdk"),
        listOf("--android-sdk", "35,"),
        listOf("--android", "--android-sdk=35", "--android-sdk"),
        listOf("--android-sdk", "--json"),
      )) {
      assertFailsWith<IllegalArgumentException>(args.toString()) {
        ProvisionCommand.androidLevels(args)
      }
    }
  }

  @Test
  fun `desktop and android provision the sidecars, skiko and android-all`() = fixture { root ->
    val steps = FakeSteps(root)
    val report =
      ProvisionCommand(emptyList(), steps)
        .provision(
          setOf(ProvisionCommand.Part.DESKTOP, ProvisionCommand.Part.ANDROID),
          listOf(35, 36),
        )
    assertTrue(report.ok)
    assertEquals(
      listOf(
        "DESKTOP",
        "skiko",
        "ANDROID",
        "android-all 35",
        "fetch android-all-instrumented-35-robolectric-1-i7.jar",
        "android-all 36",
        "fetch android-all-instrumented-36-robolectric-1-i7.jar",
      ),
      steps.calls,
    )
    val deps = File(steps.cache, "robolectric-deps").absolutePath
    assertEquals(deps, report.env[ROBOLECTRIC_DEPS_DIR_ENV])
    assertEquals("1", report.env["COMPOSE_PREVIEW_OFFLINE"])
    assertEquals(steps.cache.parentFile.absolutePath, report.env["XDG_CACHE_HOME"])
    assertEquals(steps.cache.absolutePath, report.cacheRoot)
    assertTrue(DaemonSidecarProvision.VERSION_ENV in report.env)
    assertEquals(
      "$deps${File.separator}android-all-instrumented-36-robolectric-1-i7.jar",
      report.items.single { it.name == "android-all SDK 36" }.path,
    )
  }

  @Test
  fun `servers report their version and pin it for the offline run`() = fixture { root ->
    val report =
      ProvisionCommand(emptyList(), FakeSteps(root))
        .provision(setOf(ProvisionCommand.Part.SERVER, ProvisionCommand.Part.MCP), emptyList())
    assertTrue(report.ok)
    assertEquals("4.2.0", report.env["COMPOSE_PREVIEW_SERVER_VERSION"])
    assertEquals("4.2.0", report.env["COMPOSE_PREVIEW_MCP_VERSION"])
    assertNull(report.env[ROBOLECTRIC_DEPS_DIR_ENV])
  }

  @Test
  fun `a failed part is reported, the rest still run, and the command exits 1`() = fixture { root ->
    val steps = FakeSteps(root, failing = setOf("ANDROID"))
    val printed = mutableListOf<String>()
    var exitCode: Int? = null
    ProvisionCommand(
        listOf("--desktop", "--android", "--xr", "--json"),
        steps,
        out = { printed += it },
        exit = { exitCode = it },
      )
      .run()
    assertEquals(1, exitCode)
    // No android-all without the sidecar whose Robolectric names it.
    assertFalse(steps.calls.any { it.startsWith("android-all") })
    val report = Json.decodeFromString(ProvisionReport.serializer(), printed.single())
    assertEquals("compose-preview-provision/v1", report.schema)
    assertFalse(report.ok)
    assertEquals(
      listOf(
        "desktop daemon" to true,
        "skiko native" to true,
        "android daemon" to false,
        "xr-composite" to false,
      ),
      report.items.map { it.name to it.ok },
    )
    assertTrue(report.items.single { it.name == "android daemon" }.error!!.contains("HTTP 403"))
    assertNull(report.env["COMPOSE_PREVIEW_OFFLINE"], "never advertise an offline run that fails")
  }

  @Test
  fun `text output names the cache root and the environment to export`() = fixture { root ->
    val printed = mutableListOf<String>()
    ProvisionCommand(listOf("--android"), FakeSteps(root), out = { printed += it }, exit = {}).run()
    val text = printed.single()
    assertTrue(text.contains("cache root ${File(root, "cache/composeai").absolutePath}"), text)
    assertTrue(text.contains("export $ROBOLECTRIC_DEPS_DIR_ENV="), text)
    assertTrue(text.contains("export COMPOSE_PREVIEW_OFFLINE=1"), text)
  }

  @Test
  fun `provision is a routed setup command with validated flags`() {
    assertEquals(
      CliRouter.Route.Run("provision", listOf("--android")),
      CliRouter.route(arrayOf("setup", "provision", "--android")),
    )
    assertEquals(
      CliRouter.Route.Run("provision", listOf("--android-sdk", "36")),
      CliRouter.route(arrayOf("--android-sdk", "36", "provision")),
    )
    assertEquals(
      emptyList(),
      CliFlagValidation.unknownFlags(
        "provision",
        listOf("--all", "--json", "--android-sdk", "35", "--desktop", "--android"),
      ),
    )
    assertEquals(
      listOf("--andriod"),
      CliFlagValidation.unknownFlags("provision", listOf("--andriod")),
    )
  }
}
