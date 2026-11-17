package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** [ServeBundleDaemon.dwfModuleDescriptor]: a module of watch faces renders in `DwfDaemonMain`. */
class ServeBundleDaemonDwfModuleTest {
  private val json = Json { encodeDefaults = true }
  private lateinit var root: File

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("dwf-module-").toFile()
    val rcjvm = File(root, "lib-rcjvm").apply { mkdirs() }
    File(rcjvm, "rc-render-jvm.jar").writeText("")
    val desktop = File(root, "lib-daemon-desktop").apply { mkdirs() }
    File(desktop, "daemon-desktop.jar").writeText("")
    System.setProperty("composeai.cli.libRcjvmDir", rcjvm.absolutePath)
    System.setProperty("composeai.cli.libDaemonDesktopDir", desktop.absolutePath)
  }

  @AfterTest
  fun tearDown() {
    System.clearProperty("composeai.cli.libRcjvmDir")
    System.clearProperty("composeai.cli.libDaemonDesktopDir")
    root.deleteRecursively()
  }

  private fun module(
    previews: String,
    mainClass: String = "ee.schimke.composeai.daemon.DaemonMain",
    watch: Boolean = true,
  ): File {
    val project = File(root, "app").apply { mkdirs() }
    val res = File(project, "src/main/res/raw").apply { mkdirs() }.parentFile
    File(res, "raw/watchface.xml").writeText("<WatchFace width=\"450\" height=\"450\"/>")
    val output = File(project, "build/compose-previews").apply { mkdirs() }
    val previewsJson = File(output, "previews.json").apply { writeText(previews) }
    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = 2,
          modulePath = ":app",
          variant = "debug",
          enabled = true,
          mainClass = mainClass,
          classpath = listOf("/module/classes"),
          jvmArgs = emptyList(),
          systemProperties =
            mapOf(
              "composeai.daemon.previewsJsonPath" to previewsJson.absolutePath,
              "composeai.daemon.moduleProjectDir" to project.absolutePath,
            ),
          workingDirectory = project.absolutePath,
          manifestPath = previewsJson.absolutePath,
        )
        .build()
    // `SourceWatch`'s constructor is internal to the protocol; the Gradle plugin writes it as JSON.
    val encoded =
      json.encodeToJsonElement(DaemonLaunchDescriptor.serializer(), descriptor).jsonObject
    val withWatch =
      if (!watch) encoded
      else
        JsonObject(
          encoded +
            ("sourceWatch" to
              buildJsonObject {
                put("sourceDirs", JsonArray(emptyList()))
                put(
                  "resourceDirs",
                  JsonArray(
                    listOf(res.absolutePath, File(project, "src/debug/res").absolutePath).map {
                      JsonPrimitive(it)
                    },
                  ),
                )
                put("buildFiles", JsonArray(emptyList()))
              }),
        )
    return File(output, "daemon-launch.json").apply { writeText(withWatch.toString()) }
  }

  private val dwfOnly =
    """
    {"module":"app","variant":"debug","previews":[
      {"id":"dwf__raw_watchface","functionName":"watchface","className":"",
       "params":{"name":"watchface","dwfAssetPath":"raw/watchface.xml"},"captures":[]}
    ]}
    """
      .trimIndent()

  private val mixed =
    """
    {"module":"app","variant":"debug","previews":[
      {"id":"dwf__raw_watchface","functionName":"watchface","className":"",
       "params":{"name":"watchface","dwfAssetPath":"raw/watchface.xml"},"captures":[]},
      {"id":"com.example.Previews.Button","functionName":"Button","className":"com.example.Previews",
       "params":{"name":"Button"},"captures":[]}
    ]}
    """
      .trimIndent()

  @Test
  fun watchFaceModuleLaunchesTheDwfDaemonOnItsSourceResources() {
    val moduleDescriptor = module(dwfOnly)
    val derived =
      assertNotNull(ServeBundleDaemon.dwfModuleDescriptor(moduleDescriptor, watchFacesOnly = true))
    assertEquals(
      File(moduleDescriptor.parentFile, ServeBundleDaemon.DWF_MODULE_DESCRIPTOR),
      derived,
    )
    val launch = assertNotNull(ServeBundleDaemon.readLaunchDescriptor(derived))
    assertEquals("ee.schimke.composeai.rcjvm.DwfDaemonMain", launch.mainClass)
    assertEquals(":app", launch.modulePath)
    // Only directories that exist; the source tree, not a build output, so an edit needs no build.
    assertEquals(
      File(root, "app/src/main/res").absolutePath,
      launch.systemProperties["composeai.daemon.resDirs"],
    )
    assertTrue(launch.classpath.any { it.endsWith("rc-render-jvm.jar") })
    assertNotNull(launch.sourceWatch)
  }

  @Test
  fun keepsTheModuleDescriptorWhenTheSessionServesAComposable() {
    assertNull(ServeBundleDaemon.dwfModuleDescriptor(module(mixed), watchFacesOnly = false))
  }

  @Test
  fun aMixedModuleFilteredToItsWatchFacesUsesTheDwfDaemon() {
    // `serve --module app --filter dwf`: the manifest still names the composable, which the DWF
    // daemon skips.
    assertNotNull(ServeBundleDaemon.dwfModuleDescriptor(module(mixed), watchFacesOnly = true))
  }

  @Test
  fun leavesBundleAndAlreadyDwfDescriptorsAlone() {
    assertNull(
      ServeBundleDaemon.dwfModuleDescriptor(module(dwfOnly, watch = false), watchFacesOnly = true),
    )
    assertNull(
      ServeBundleDaemon.dwfModuleDescriptor(
        module(dwfOnly, mainClass = "ee.schimke.composeai.rcjvm.DwfDaemonMain"),
        watchFacesOnly = true,
      ),
    )
  }
}
