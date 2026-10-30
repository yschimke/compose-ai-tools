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
    val convert: (File, File?) -> String = { out, _ ->
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
      val convert: (File, File?) -> String = { out, _ ->
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
      assertTrue(rebuilt.apkDir != newConverter.apkDir)
      assertTrue(rebuilt.classesJar.exists())
      assertFalse(newConverter.classesJar.exists(), "the earlier build of the same path is removed")
    }

  @Test
  fun `a failed conversion leaves nothing behind`() = fixture { root ->
    val apk = root.child("app.apk")
    val cache = ApkConversionCache(File(root, "cache"))
    val failure = runCatching {
      cache.ensure(apk, "stamp") { out, _ ->
        out.writeText("half")
        error("dex2jar fell over")
      }
    }
    assertTrue(failure.isFailure)
    val retried =
      cache.ensure(apk, "stamp") { out, _ ->
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
          .ensure(apk, "stamp") { out, _ ->
            out.writeText("classes")
            "{}"
          }
          .also { it.classesJar.parentFile.parentFile.setLastModified(1_000L * i) }
      }
    // A fourth use prunes down to two.
    cache.ensure(root.child("app4.apk", "apk 4"), "stamp") { out, _ ->
      out.writeText("classes")
      "{}"
    }
    assertFalse(entries[0].classesJar.exists())
    assertFalse(entries[1].classesJar.exists())
    assertTrue(entries[2].classesJar.exists())
  }

  @Test
  fun `the converter stamp follows the converter, not the rest of the simulator host`() =
    fixture { root ->
      val platform = root.child("android-36/android.jar", "platform")
      val host = File(root, "simulator-host.jar")
      fun writeHost(converter: String, other: String) =
        java.util.zip.ZipOutputStream(host.outputStream()).use { zip ->
          for ((name, text) in
            listOf(
              "${ApkConversionCache.CONVERTER_CLASSES}.class" to converter,
              "${ApkConversionCache.CONVERTER_CLASSES}\$Index.class" to converter,
              "ee/schimke/composeai/simulator/Navigator.class" to other,
            )) {
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
          }
        }
      val dex2jar = root.child("dex-tools.jar", "d2j")
      writeHost("converter v1", "navigator v1")
      val before = ApkConversionCache.converterStamp(listOf(host, dex2jar), platform)
      assertEquals(before, ApkConversionCache.converterStamp(listOf(dex2jar, host), platform))
      writeHost("converter v1", "navigator v2")
      assertEquals(before, ApkConversionCache.converterStamp(listOf(host, dex2jar), platform))
      writeHost("converter v2", "navigator v2")
      assertTrue(before != ApkConversionCache.converterStamp(listOf(host, dex2jar), platform))
      dex2jar.writeText("d2j v2")
      assertTrue(before != ApkConversionCache.converterStamp(listOf(host, dex2jar), platform))
    }

  @Test
  fun `a rebuilt apk is converted from its previous build, which is then removed`() =
    fixture { root ->
      val apk = root.child("app.apk", "build 1")
      val cache = ApkConversionCache(File(root, "cache"))
      val previousSeen = mutableListOf<File?>()
      val convert: (File, File?) -> String = { out, previous ->
        previousSeen += previous
        out.writeText("classes")
        ApkConversionCache.indexOf(out).writeText("index")
        "{}"
      }
      val first = cache.ensure(apk, "stamp", convert)
      assertTrue(ApkConversionCache.indexOf(first.classesJar).isFile, "the index is kept")
      apk.writeText("build 2")
      val second = cache.ensure(apk, "stamp", convert)

      assertEquals(listOf(null, first.classesJar), previousSeen)
      assertFalse(first.apkDir.exists(), "the superseded build is removed")
      assertTrue(second.classesJar.isFile)
    }

  @Test
  fun `an entry in use is never pruned`() = fixture { root ->
    val cache = ApkConversionCache(File(root, "cache"), keepApks = 1)
    val convert: (File, File?) -> String = { out, _ ->
      out.writeText("classes")
      "{}"
    }
    val running = cache.ensure(root.child("a.apk", "a"), "stamp", convert)
    cache.use(running).use {
      // A newer APK's conversion would evict the older one, but its simulator still runs.
      cache.ensure(root.child("b.apk", "b"), "stamp", convert)
      assertTrue(running.classesJar.isFile)
    }
    cache.ensure(root.child("c.apk", "c"), "stamp", convert)
    assertFalse(running.classesJar.exists(), "evicted once released")
  }

  @Test
  fun `pruning deletes an entry but never its lock files`() = fixture { root ->
    val cacheDir = File(root, "cache")
    val cache = ApkConversionCache(cacheDir, keepApks = 1)
    val convert: (File, File?) -> String = { out, _ ->
      out.writeText("classes")
      "{}"
    }
    val old = cache.ensure(root.child("a.apk", "a"), "stamp", convert)
    cache.use(old).close()
    cache.ensure(root.child("b.apk", "b"), "stamp", convert)
    assertFalse(old.apkDir.exists())
    // A waiter may hold these inodes: unlinking one would let a newcomer lock a fresh file.
    assertTrue(File(cacheDir, "${old.apkDir.name}.lock").isFile)
    assertTrue(File(cacheDir, "${old.apkDir.name}.inuse").isFile)
  }

  @Test
  fun `the cache is held under its size cap`() = fixture { root ->
    val cache = ApkConversionCache(File(root, "cache"), maxBytes = 25)
    val entries =
      (1..3).map { i ->
        cache
          .ensure(root.child("app$i.apk", "apk $i"), "stamp") { out, _ ->
            out.writeText("0123456789")
            "{}"
          }
          .also { it.apkDir.setLastModified(1_000L * i) }
      }
    // Each entry, with its summary and source path, is over the cap: only the newest is kept.
    assertFalse(entries[0].classesJar.exists())
    assertFalse(entries[1].classesJar.exists())
    assertTrue(entries[2].classesJar.exists())
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
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.device") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.displays") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.launcher") })
  }

  @Test
  fun `the Wear launcher and its tiles become system properties`() {
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
            headless = true,
            control = true,
            installProviders = true,
            launcher = true,
            tiles = listOf(".StatusTile", "com.example.WeatherTile"),
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.launcher=true" in command)
    assertTrue("-Dcomposeai.simulator.tiles=.StatusTile,com.example.WeatherTile" in command)
  }

  @Test
  fun `a device preset becomes a system property`() {
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
            headless = true,
            control = true,
            installProviders = true,
            device = "foldable",
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.device=foldable" in command)
    assertTrue("foldable" in LaunchCommand.DEVICES)
  }

  @Test
  fun `each --display joins one system property`() {
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
            headless = true,
            control = true,
            installProviders = true,
            displays = listOf("1280x720@mdpi,name=TV", "rear"),
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.displays=1280x720@mdpi,name=TV;rear" in command)
  }

  @Test
  fun `a build fingerprint becomes a system property, and is absent by default`() {
    fun command(fingerprint: String?) =
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
            headless = true,
            control = true,
            installProviders = true,
            fingerprint = fingerprint,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue(
      "-Dcomposeai.simulator.fingerprint=google/sim/sim:16/X/1:user/release-keys" in
        command("google/sim/sim:16/X/1:user/release-keys")
    )
    assertTrue(command(null).none { it.startsWith("-Dcomposeai.simulator.fingerprint") })
  }

  @Test
  fun `a launcher launch passes the home screen and the widgets to place`() {
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
            headless = true,
            control = true,
            installProviders = true,
            launcher = true,
            widgets = listOf("WeatherAppWidgetReceiver", "com.example.CounterWidgetReceiver"),
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.launcher=true" in command)
    assertTrue(
      "-Dcomposeai.simulator.widgets=WeatherAppWidgetReceiver;com.example.CounterWidgetReceiver" in
        command
    )
  }

  @Test
  fun `a max heap becomes the simulator jvm's -Xmx`() {
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
            headless = true,
            control = true,
            installProviders = true,
            maxHeap = "2g",
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Xmx2g" in command)
    assertTrue(command.indexOf("-Xmx2g") < command.indexOf("-cp"))
  }

  @Test
  fun `the simulator heap defaults to an eighth of the machine, within bounds`() {
    val gb = 1L shl 30
    assertEquals("2048m", LaunchCommand.defaultMaxHeap(64 * gb))
    assertEquals("2048m", LaunchCommand.defaultMaxHeap(16 * gb))
    assertEquals("1024m", LaunchCommand.defaultMaxHeap(8 * gb))
    assertEquals("768m", LaunchCommand.defaultMaxHeap(2 * gb))
    assertEquals("768m", LaunchCommand.defaultMaxHeap(null))
  }

  @Test
  fun `every simulator collects periodically, so idle graphics release their native memory`() {
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
            headless = true,
            control = true,
            installProviders = true,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue(command.indexOf("-XX:G1PeriodicGCInterval=5000") in 1 until command.indexOf("-cp"))
  }

  @Test
  fun `the converter runs on a capped serial heap and exits on running out`() {
    val command =
      LaunchCommand.converterCommand(
        java = File("java"),
        converterJars = listOf(File("host.jar"), File("dex2jar.jar")),
        apk = File("app.apk"),
        out = File("classes.jar"),
        androidJar = File("android.jar"),
        heap = LaunchCommand.CONVERTER_HEAPS.first(),
      )
    assertEquals("-Xmx1g", command[1])
    assertTrue("-XX:+UseSerialGC" in command)
    assertTrue("-XX:+ExitOnOutOfMemoryError" in command)
    assertEquals(
      listOf(LaunchCommand.CONVERTER_MAIN, "app.apk", "classes.jar", "android.jar"),
      command.takeLast(4),
    )
    assertTrue(LaunchCommand.CONVERTER_HEAPS.size > 1, "a retry with a larger heap")
  }

  @Test
  fun `no home screen without --launcher`() {
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
            headless = true,
            control = true,
            installProviders = true,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.launcher") })
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.widgets") })
  }

  @Test
  fun `a network mode becomes a system property and java net is opened`() {
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
            headless = true,
            control = true,
            installProviders = true,
            network = "cellular,airplane",
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.network=cellular,airplane" in command)
    assertTrue("--add-opens=java.base/java.net=ALL-UNNAMED" in command)
    assertTrue(LaunchCommand.NETWORK_MODES.containsAll(listOf("offline", "captive", "airplane")))
  }

  @Test
  fun `a Play services state becomes a system property, and installed is the default`() {
    fun command(playServices: String?) =
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
            headless = true,
            control = true,
            installProviders = true,
            playServices = playServices,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.playServices=none" in command("none"))
    assertFalse(command(null).any { it.startsWith("-Dcomposeai.simulator.playServices") })
    assertTrue(
      LaunchCommand.PLAY_SERVICES_STATES.containsAll(listOf("installed", "none", "outdated"))
    )
  }

  @Test
  fun `a wear launch passes the round watch display and its form factor`() {
    val command =
      LaunchCommand.simulatorCommand(
        java = File("java"),
        classpath = emptyList(),
        apk = File("watch.apk"),
        classes = emptyList(),
        options =
          LaunchCommand.Options(
            sdk = 35,
            qualifiers = ApkFormFactor.WEAR.qualifiers,
            formFactor = ApkFormFactor.WEAR.id,
            fontScale = null,
            headless = true,
            control = true,
            installProviders = true,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.qualifiers=w227dp-h227dp-round-watch-xhdpi" in command)
    assertTrue("-Dcomposeai.simulator.formFactor=wear" in command)
    assertFalse(command.any { it.startsWith("-Dcomposeai.simulator.launchActivity") })
  }

  @Test
  fun `a glimmer launch names the projected activity to start`() {
    val command =
      LaunchCommand.simulatorCommand(
        java = File("java"),
        classpath = emptyList(),
        apk = File("glasses.apk"),
        classes = emptyList(),
        options =
          LaunchCommand.Options(
            sdk = 35,
            qualifiers = ApkFormFactor.GLIMMER.qualifiers,
            formFactor = ApkFormFactor.GLIMMER.id,
            launchActivity = "com.example.GlassesActivity",
            fontScale = null,
            headless = true,
            control = true,
            installProviders = true,
          ),
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assertTrue("-Dcomposeai.simulator.launchActivity=com.example.GlassesActivity" in command)
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
