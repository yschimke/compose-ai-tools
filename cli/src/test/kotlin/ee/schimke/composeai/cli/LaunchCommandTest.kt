package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchCommandTest {

  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("launch").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun File.child(name: String, text: String = name): File =
    File(this, name).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  // -----------------------------------------------------------------------------------------------
  // ApkConversionCache

  @Test
  fun `an apk converts once per converter, then comes from the cache`() = fixture { root ->
    val apk = root.child("app.apk", "dex bytes")
    val cache = ApkConversionCache(File(root, "cache"))
    var conversions = 0
    val convert: (File) -> String = { out ->
      conversions++
      out.writeText("classes")
      """{"classes":1}"""
    }

    val first = cache.ensure(apk, "stamp-a", convert)
    val second = cache.ensure(apk, "stamp-a", convert)

    assertEquals(1, conversions)
    assertFalse(first.cached)
    assertTrue(second.cached)
    assertEquals(first.classesJar, second.classesJar)
    assertEquals("""{"classes":1}""", second.summary)
    assertEquals(
      apk.absolutePath,
      File(first.classesJar.parentFile.parentFile, "source.txt").readText().trim(),
    )
  }

  @Test
  fun `a rebuilt apk or a new converter converts again, and replaces the old stamp`() =
    fixture { root ->
      val apk = root.child("app.apk", "v1")
      val cache = ApkConversionCache(File(root, "cache"))
      val convert: (File) -> String = { out ->
        out.writeText("classes")
        "{}"
      }

      val v1 = cache.ensure(apk, "stamp-a", convert)
      val newConverter = cache.ensure(apk, "stamp-b", convert)
      apk.writeText("v2")
      val rebuilt = cache.ensure(apk, "stamp-b", convert)

      assertFalse(newConverter.cached)
      assertFalse(rebuilt.cached)
      assertFalse(v1.classesJar.exists(), "the superseded stamp is removed")
      assertTrue(newConverter.classesJar.exists())
      assertTrue(
        rebuilt.classesJar.parentFile.parentFile != newConverter.classesJar.parentFile.parentFile
      )
    }

  @Test
  fun `a failed conversion leaves nothing behind`() = fixture { root ->
    val apk = root.child("app.apk")
    val cache = ApkConversionCache(File(root, "cache"))
    val failure = runCatching {
      cache.ensure(apk, "stamp") { out ->
        out.writeText("half")
        error("dex2jar fell over")
      }
    }
    assertTrue(failure.isFailure)
    val retried =
      cache.ensure(apk, "stamp") { out ->
        out.writeText("classes")
        "{}"
      }
    assertFalse(retried.cached)
    assertEquals("classes", retried.classesJar.readText())
  }

  @Test
  fun `only the most recently used apks are kept`() = fixture { root ->
    val cache = ApkConversionCache(File(root, "cache"), keepApks = 2)
    val entries =
      (1..3).map { i ->
        val apk = root.child("app$i.apk", "apk $i")
        cache
          .ensure(apk, "stamp") { out ->
            out.writeText("classes")
            "{}"
          }
          .also { it.classesJar.parentFile.parentFile.setLastModified(1_000L * i) }
      }
    // A fourth use prunes down to two.
    cache.ensure(root.child("app4.apk", "apk 4"), "stamp") { out ->
      out.writeText("classes")
      "{}"
    }
    assertFalse(entries[0].classesJar.exists())
    assertFalse(entries[1].classesJar.exists())
    assertTrue(entries[2].classesJar.exists())
  }

  @Test
  fun `the converter stamp follows the converter jars`() = fixture { root ->
    val platform = root.child("android-36/android.jar", "platform")
    val host = root.child("simulator-host.jar", "v1")
    val dex2jar = root.child("dex-tools.jar", "d2j")
    val before = ApkConversionCache.converterStamp(listOf(host, dex2jar), platform)
    assertEquals(before, ApkConversionCache.converterStamp(listOf(dex2jar, host), platform))
    host.writeText("v2")
    assertTrue(before != ApkConversionCache.converterStamp(listOf(host, dex2jar), platform))
  }

  // -----------------------------------------------------------------------------------------------
  // SimulatorJava

  private fun jdk(root: File, name: String, version: String): File =
    File(root, name).apply {
      child("release", "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"$version\"\n")
      child("bin/java", "#!/bin/sh")
    }

  @Test
  fun `java versions parse to their feature release`() {
    assertEquals(21, SimulatorJava.parseFeature("21.0.2"))
    assertEquals(25, SimulatorJava.parseFeature("25"))
    assertEquals(8, SimulatorJava.parseFeature("1.8.0_402"))
    assertEquals(26, SimulatorJava.parseFeature("26-ea"))
    assertNull(SimulatorJava.parseFeature("unknown"))
  }

  @Test
  fun `the first jdk that is new enough wins, skipping older ones`() = fixture { root ->
    val old = jdk(root, "jdk17", "17.0.20")
    val new = jdk(root, "jdk25", "25.0.5")
    val result =
      SimulatorJava.resolve(
        explicit = null,
        env = { if (it == "JAVA_HOME") new.path else null },
        ownHome = old.path,
      )
    assertEquals(
      SimulatorJava.Result.Found(SimulatorJava.Java(File(new, "bin/java"), 25)),
      result,
    )
  }

  @Test
  fun `an explicit jdk that is too old is an error, not a cue to look elsewhere`() =
    fixture { root ->
      val old = jdk(root, "jdk17", "17.0.20")
      val new = jdk(root, "jdk25", "25.0.5")
      val result =
        SimulatorJava.resolve(
          explicit = File(old, "bin/java").path,
          env = { null },
          ownHome = new.path,
        )
      assertTrue(result is SimulatorJava.Result.NotFound)
      assertTrue(result.tried.single().contains("Java 17"), result.tried.toString())
    }

  // -----------------------------------------------------------------------------------------------
  // The launched command

  @Test
  fun `the simulator command carries the options as system properties, apk first`() {
    val command =
      LaunchCommand.simulatorCommand(
        java = File("/jdk/bin/java"),
        classpath = listOf(File("/lib-simulator/simulator-host.jar"), File("/sdk/android.jar")),
        apk = File("/apps/app.apk"),
        classes = listOf(File("/cache/classes.jar"), File("/extra.jar")),
        options =
          LaunchCommand.Options(
            sdk = 35,
            qualifiers = "w411dp-h891dp-xxhdpi",
            fontScale = "1.3",
            headless = true,
            control = true,
            installProviders = false,
            showAgent = false,
          ),
        robolectricProperties = mapOf("robolectric.offline" to "true"),
        robolectricJvmArgs = listOf("--add-opens=java.base/java.lang=ALL-UNNAMED"),
      )

    assertEquals("/jdk/bin/java", command.first())
    assertTrue("--add-opens=java.base/java.lang=ALL-UNNAMED" in command)
    assertTrue("-Djava.awt.headless=true" in command)
    for (property in
      listOf(
        "-Drobolectric.deviceconfig.sdk=35",
        "-Dcomposeai.simulator.qualifiers=w411dp-h891dp-xxhdpi",
        "-Dcomposeai.simulator.fontScale=1.3",
        "-Dcomposeai.simulator.control=stdio",
        "-Dcomposeai.simulator.skipProviders=true",
        "-Dcomposeai.simulator.showAgent=false",
        "-Drobolectric.offline=true",
      )) {
      assertTrue(property in command, "$property in $command")
    }
    assertEquals(
      listOf(LaunchCommand.LAUNCH_MAIN, "/apps/app.apk", "/cache/classes.jar", "/extra.jar"),
      command.takeLast(4),
    )
  }

  @Test
  fun `a window launch with the defaults sets neither headless nor control`() {
    val command =
      LaunchCommand.simulatorCommand(
        java = File("java"),
        classpath = emptyList(),
        apk = File("app.apk"),
        classes = emptyList(),
        options =
          LaunchCommand.Options(
            sdk = 35,
            qualifiers = LaunchCommand.DEFAULT_QUALIFIERS,
            fontScale = null,
            headless = false,
            control = false,
            installProviders = true,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertFalse(command.any { it.startsWith("-Djava.awt.headless") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.control") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.skipProviders") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.fontScale") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.showAgent") })
  }

  @Test
  fun `a simulator and robolectric from different releases are reported`() {
    val sidecar = listOf(File("0109-robolectric-4.17.jar"), File("0126-shadows-framework-4.17.jar"))
    assertNull(LaunchCommand.robolectricMismatch(listOf(File("simulator-4.17.jar")), sidecar))
    assertEquals(
      "Robolectric simulator 4.18-beta-1 runs on the sidecar's Robolectric 4.17",
      LaunchCommand.robolectricMismatch(listOf(File("simulator-4.18-beta-1.jar")), sidecar),
    )
  }
}
