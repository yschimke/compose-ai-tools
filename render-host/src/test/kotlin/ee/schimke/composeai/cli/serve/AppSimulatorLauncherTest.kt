package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.simulator.launch.SimulatorJvm
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class AppSimulatorLauncherTest {
  @TempDir lateinit var dir: Path

  private val environment =
    AppSimulatorLauncher.Environment(
      java = File("/jdk/bin/java"),
      simulatorJars =
        listOf(
          File("/lib-simulator/simulator-host-1.jar"),
          File("/lib-simulator/simulator-core-1.jar"),
        ),
      robolectricJars = listOf(File("/lib-daemon-android/robolectric-4.jar")),
      dex2jarJars = listOf(File("/lib-dex2jar/dex-tools.jar")),
      androidJar = File("/sdk/platforms/android-36/android.jar"),
    )

  private fun properties(command: List<String>): Map<String, String> =
    command
      .filter { it.startsWith("-D") }
      .associate { it.removePrefix("-D").substringBefore('=') to it.substringAfter('=') }

  @Test
  fun `a phone runs one simulator JVM with a control channel and no launcher JVM`() {
    val command =
      AppSimulatorLauncher.command(
        environment,
        AppSimulatorLaunch(
          mapOf(AppSimulatorDevice.PHONE to File("/apps/phone.apk")),
          network = "offline",
        ),
        AppSimulatorDevice.PHONE,
        File("/apps/phone.apk"),
        File("/cache/classes.jar"),
        hubPort = null,
        robolectricProperties = mapOf("robolectric.offline" to "true"),
        physicalMemoryBytes = 16L shl 30,
      )
    assertEquals("/jdk/bin/java", command.first())
    assertTrue("-Xmx2048m" in command, "an eighth of 16 GB, capped at 2 GB")
    assertTrue("-javaagent:/lib-simulator/simulator-host-1.jar" in command)
    assertEquals(
      listOf(SimulatorJvm.LAUNCH_MAIN, "/apps/phone.apk", "/cache/classes.jar"),
      command.takeLast(3),
    )
    val props = properties(command)
    assertEquals("stdio", props[SimulatorJvm.PROPERTY_CONTROL])
    assertEquals("phone", props[SimulatorJvm.PROPERTY_FORM_FACTOR])
    assertEquals("w360dp-h640dp-hdpi", props[SimulatorJvm.PROPERTY_QUALIFIERS])
    assertEquals("offline", props[SimulatorJvm.PROPERTY_NETWORK])
    assertEquals("true", props["robolectric.offline"])
    assertNull(props[SimulatorJvm.PROPERTY_DATALAYER_HUB], "a lone phone is not paired")
    assertNull(props[SimulatorJvm.PROPERTY_LAUNCHER])
    assertTrue("-Djava.awt.headless=true" in command)
  }

  @Test
  fun `a paired watch joins the hub as the watch node, round`() {
    val launch =
      AppSimulatorLaunch(
        mapOf(
          AppSimulatorDevice.PHONE to File("/apps/phone.apk"),
          AppSimulatorDevice.WATCH to File("/apps/watch.apk"),
        ),
        launcher = true,
        maxHeap = "1g",
      )
    val command =
      AppSimulatorLauncher.command(
        environment,
        launch,
        AppSimulatorDevice.WATCH,
        File("/apps/watch.apk"),
        File("/cache/w.jar"),
        hubPort = 41234,
        robolectricProperties = emptyMap(),
      )
    val props = properties(command)
    assertEquals("wear", props[SimulatorJvm.PROPERTY_FORM_FACTOR])
    assertEquals("w227dp-h227dp-round-watch-xhdpi", props[SimulatorJvm.PROPERTY_QUALIFIERS])
    assertEquals("41234", props[SimulatorJvm.PROPERTY_DATALAYER_HUB])
    assertEquals("watch-sim", props[SimulatorJvm.PROPERTY_DATALAYER_NODE])
    assertEquals("Simulated Watch", props[SimulatorJvm.PROPERTY_DATALAYER_NAME])
    assertEquals("true", props[SimulatorJvm.PROPERTY_LAUNCHER])
    assertTrue("-Xmx1g" in command)
  }

  @Test
  fun `a launch's own properties win over the launcher's`() {
    val command =
      AppSimulatorLauncher.command(
        environment,
        AppSimulatorLaunch(
          mapOf(AppSimulatorDevice.PHONE to File("/a.apk")),
          properties = mapOf(SimulatorJvm.PROPERTY_QUALIFIERS to "w411dp-h891dp-xxhdpi"),
        ),
        AppSimulatorDevice.PHONE,
        File("/a.apk"),
        File("/c.jar"),
        hubPort = null,
        robolectricProperties = emptyMap(),
      )
    assertEquals("w411dp-h891dp-xxhdpi", properties(command)[SimulatorJvm.PROPERTY_QUALIFIERS])
  }

  @Test
  fun `java is found by its release file and must be 21 or newer`() {
    fun jdk(name: String, version: String): File =
      File(dir.toFile(), name).apply {
        File(this, "bin").mkdirs()
        File(this, "bin/java").apply {
          writeText("#!/bin/sh\n")
          setExecutable(true)
        }
        File(this, "release").writeText("IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"$version\"\n")
      }
    val old = jdk("jdk17", "17.0.9")
    val new = jdk("jdk21", "21.0.2")
    assertEquals(17, AppSimulatorLauncher.featureVersion(old))
    assertEquals(
      File(new, "bin/java"),
      AppSimulatorLauncher.javaBinary {
        if (it == AppSimulatorLauncher.JAVA_ENV) new.path else null
      },
    )
    assertEquals(
      File(new, "bin/java"),
      AppSimulatorLauncher.javaBinary {
        if (it == AppSimulatorLauncher.JAVA_ENV) File(new, "bin/java").path else null
      },
    )
  }

  /**
   * Phone, watch and a pair on the real simulator. Off unless `APP_SIMULATOR_E2E_DIR` holds
   * `phone.apk`, `watch.apk` and a same-package pair `pairphone.apk` / `pairwatch.apk` (the phone's
   * button messages every connected node; the watch shows "Watch got hi 1"), and the sidecars are
   * found as on a server (`-Dcomposeai.cli.lib…Dir` or `APP_HOME`). Writes `launcher-results.json`
   * there: time to ready, RSS of each session's JVMs.
   */
  @Test
  fun `phone, watch and paired sessions run on the real simulator`() {
    val e2e = System.getenv("APP_SIMULATOR_E2E_DIR")?.let(::File)
    assumeTrue(e2e != null && File(e2e, "phone.apk").isFile, "APP_SIMULATOR_E2E_DIR not set")
    val reason = AppSimulatorLauncher.unavailableReason()
    assertNull(reason, reason)
    val results = linkedMapOf<String, String>()
    for ((label, apks) in
      listOf(
        "phone" to mapOf(AppSimulatorDevice.PHONE to "phone.apk"),
        "watch" to mapOf(AppSimulatorDevice.WATCH to "watch.apk"),
        "paired" to
          mapOf(
            AppSimulatorDevice.PHONE to "pairphone.apk",
            AppSimulatorDevice.WATCH to "pairwatch.apk",
          ),
      )) {
      val events = CopyOnWriteArrayList<String>()
      val t0 = System.nanoTime()
      AppSimulatorLauncher.start(
          AppSimulatorLaunch(apks.mapValues { File(e2e, it.value) }, network = "offline"),
        ) { device, event ->
          events += "${device.id}:${event["event"]}"
        }
        .use { session ->
          assertTrue(
            session.awaitReady(180_000),
            "$label never ready: ${session.devices.associateWith { session.stderrTail(it).takeLast(5) }}",
          )
          val readyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
          for (device in session.devices) {
            val shot = session.command(device, cmd("screenshot")) as JsonObject
            val png = Base64.getDecoder().decode((shot["png"] as JsonPrimitive).content)
            File(e2e, "launcher-$label-${device.id}.png").writeBytes(png)
            if (device == AppSimulatorDevice.WATCH) {
              assertEquals(JsonPrimitive(454), shot["width"])
            }
          }
          if (label == "paired") {
            awaitTrue("the pair never connected") {
              ((session.command(AppSimulatorDevice.PHONE, cmd("dataLayer")) as JsonObject)["peers"]
                  as JsonArray)
                .isNotEmpty()
            }
            awaitTrue("the watch never got the phone's message") {
              session.command(AppSimulatorDevice.PHONE, tap())
              texts(session, AppSimulatorDevice.WATCH).any { it.startsWith("Watch got hi") }
            }
          } else {
            val device = session.devices.single()
            val label0 = if (device == AppSimulatorDevice.WATCH) "Watch" else "Phone"
            awaitTrue("no tap reached the app") {
              session.command(device, tap())
              texts(session, device).none { it == "$label0: 0 taps" }
            }
          }
          Thread.sleep(3_000)
          val rssMb = session.pids().sumOf { rssKb(it) } / 1024
          session.pause()
          session.resume()
          assertTrue(session.command(session.devices.first(), cmd("ping")) != null)
          results[label] = "ready ${readyMs}ms, ${session.pids().size} JVM(s), ${rssMb} MB"
        }
    }
    File(e2e, "launcher-results.json")
      .writeText(
        JsonObject(results.mapValues { JsonPrimitive(it.value) }).toString(),
      )
  }

  private fun cmd(name: String) = buildJsonObject { put("cmd", name) }

  private fun tap() = buildJsonObject {
    put("cmd", "tap")
    put("text", "TAP ME")
  }

  private fun texts(session: AppSimulatorSession, device: AppSimulatorDevice): List<String> {
    val tree =
      session.command(
        device,
        buildJsonObject {
          put("cmd", "hierarchy")
          put("flat", true)
        },
      ) as JsonObject
    return (tree["nodes"] as JsonArray).mapNotNull {
      (it.jsonObject["text"] as? JsonPrimitive)?.content
    }
  }

  private fun awaitTrue(why: String, condition: () -> Boolean) {
    val until = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < until) {
      if (runCatching(condition).getOrDefault(false)) return
      Thread.sleep(500)
    }
    throw AssertionError(why)
  }

  private fun rssKb(pid: Long): Long = runCatching {
    File("/proc/$pid/status")
      .readLines()
      .first { it.startsWith("VmRSS:") }
      .split(Regex("\\s+"))[1]
      .toLong()
  }
    .getOrDefault(0L)

  @Test
  fun `a launch needs an apk`() {
    assertFalse(runCatching { AppSimulatorLaunch(emptyMap()) }.isSuccess)
  }
}
