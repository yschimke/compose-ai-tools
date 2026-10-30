package ee.schimke.composeai.cli

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobolectricDepsProvisionTest {
  /** A real Robolectric's jars, resolved by `:cli`'s `robolectricProbe` configuration. */
  private val robolectricJars: List<File> =
    System.getProperty("composeai.test.robolectricJars")
      .orEmpty()
      .split(File.pathSeparator)
      .filter { it.isNotBlank() }
      .map(::File)

  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("robolectric-deps").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `the artifact is the one Robolectric's DefaultSdkProvider names`() {
    assertTrue(robolectricJars.isNotEmpty(), "robolectricProbe jars not handed to the test")
    // Robolectric is not on this test's classpath: it is found only in the jars given.
    assertFailsWith<ClassNotFoundException> {
      Class.forName("org.robolectric.plugins.DefaultSdkProvider")
    }
    val resolved = RobolectricDepsProvision.artifactFor(robolectricJars, 35)
    assertEquals(35, resolved.apiLevel)
    assertEquals("org.robolectric", resolved.artifact.groupId)
    assertEquals("android-all-instrumented", resolved.artifact.artifactId)
    assertTrue(
      Regex("""15-robolectric-\d+(-\d+)?-i\d+""").matches(resolved.artifact.version),
      resolved.artifact.version,
    )
    assertEquals(
      "android-all-instrumented-${resolved.artifact.version}.jar",
      resolved.artifact.fileName,
    )
    assertEquals(
      "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/" +
        "${resolved.artifact.version}/${resolved.artifact.fileName}",
      resolved.artifact.url(),
    )
  }

  @Test
  fun `the field reconstruction agrees with Robolectric's own answer`() {
    // The fallback for a level this JDK cannot run must name what Robolectric would on a newer one.
    val loader =
      URLClassLoader(
        robolectricJars.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
    loader.use {
      for (level in listOf(28, 33, 35)) {
        val sdk = RobolectricDepsProvision.defaultSdk(it, level)
        assertEquals(
          RobolectricDepsProvision.artifactFor(robolectricJars, level).artifact,
          RobolectricDepsProvision.preinstrumentedFromFields(sdk),
        )
      }
    }
    // A level above this JDK's reach still resolves (Robolectric 4.17 needs JDK 21 from SDK 36).
    val newest = RobolectricDepsProvision.artifactFor(robolectricJars, 36)
    assertTrue(newest.artifact.version.startsWith("16-robolectric-"), newest.artifact.version)
    assertEquals(21, newest.requiredJava)
  }

  @Test
  fun `an unknown level names the levels Robolectric ships`() {
    val e =
      assertFailsWith<IllegalStateException> {
        RobolectricDepsProvision.artifactFor(robolectricJars, 99)
      }
    assertTrue(e.message!!.contains("35"), e.message)
  }

  @Test
  fun `the newest provisioned level stands in for one with no android-all`() = fixture { dir ->
    val sdk35 = RobolectricDepsProvision.artifactFor(robolectricJars, 35).artifact
    File(dir, sdk35.fileName).writeText("framework")
    val newest = { level: Int ->
      RobolectricDepsProvision.highestProvisionedLevel(robolectricJars, dir, level, floor = 21)
    }
    assertEquals(35, newest(35))
    // 36 (and anything Robolectric knows above it) is not there: settle for 35.
    assertEquals(35, newest(36))
    assertEquals(35, newest(37))
    // Nothing at or below the request: no answer, so the caller keeps the request.
    assertEquals(null, newest(34))
    val logs = mutableListOf<String>()
    val offline = mapOf("robolectric.dependency.dir" to dir.path)
    assertEquals(35, provisionedSdkLevel(36, robolectricJars, offline, log = { logs += it }))
    assertTrue(logs.single().contains("no android-all for SDK 36"), logs.toString())
    // Online, or already provisioned: unchanged and silent.
    assertEquals(36, provisionedSdkLevel(36, robolectricJars, emptyMap(), log = { logs += it }))
    assertEquals(35, provisionedSdkLevel(35, robolectricJars, offline, log = { logs += it }))
    assertEquals(1, logs.size)
  }

  private val artifact =
    RobolectricDepsProvision.Artifact(
      "org.robolectric",
      "android-all-instrumented",
      "15-robolectric-1-i7",
    )

  /** What [dir] holds besides the per-artifact lock files, which stay. */
  private fun files(dir: File): List<String> =
    dir.list()!!.filterNot { it.endsWith(".lock") }.sorted()

  @Test
  fun `a download lands under its Maven name once its checksum matches`() = fixture { dir ->
    val body = "framework bytes"
    val bodyFile = File(dir, "body").apply { writeText(body) }
    val sha256 = RobolectricDepsProvision.digest(bodyFile, "SHA-256")
    val fetched = mutableListOf<String>()
    val fetcher = RobolectricDepsProvision.Fetcher { url, dest ->
      fetched += url
      when {
        url.endsWith(".jar") -> dest.writeText(body)
        url.endsWith(".sha512") -> error("HTTP 404")
        url.endsWith(".sha256") -> dest.writeText("$sha256  android-all.jar\n")
        else -> error("unexpected $url")
      }
    }
    val out = File(dir, "deps")
    val jar =
      RobolectricDepsProvision.ensure(artifact, out, fetcher = fetcher, pause = {}, log = {})
    assertEquals(File(out, "android-all-instrumented-15-robolectric-1-i7.jar"), jar)
    assertEquals(body, jar.readText())
    assertEquals(listOf(jar.name), files(out), "no temporary files left behind")
    assertEquals(
      listOf(artifact.url(), artifact.url() + ".sha512", artifact.url() + ".sha256"),
      fetched,
    )

    // Present: nothing is fetched again.
    fetched.clear()
    RobolectricDepsProvision.ensure(artifact, out, fetcher = fetcher, pause = {}, log = {})
    assertTrue(fetched.isEmpty())
  }

  @Test
  fun `a checksum mismatch keeps the download out`() = fixture { dir ->
    val fetcher = RobolectricDepsProvision.Fetcher { url, dest ->
      if (url.endsWith(".jar")) dest.writeText("tampered")
      else if (url.endsWith(".sha1")) dest.writeText("0".repeat(40)) else error("HTTP 404")
    }
    val out = File(dir, "deps")
    val e =
      assertFailsWith<IllegalStateException> {
        RobolectricDepsProvision.ensure(artifact, out, fetcher = fetcher, pause = {}, log = {})
      }
    assertTrue(e.message!!.contains("SHA-1"), e.message)
    assertFalse(File(out, artifact.fileName).exists())
    assertTrue(files(out).isEmpty())
  }

  @Test
  fun `a rate-limited fetch is retried and a missing one is not`() = fixture { dir ->
    var jarAttempts = 0
    val body = "bytes"
    val sha1 = RobolectricDepsProvision.digest(File(dir, "b").apply { writeText(body) }, "SHA-1")
    val pauses = mutableListOf<Int>()
    val fetcher = RobolectricDepsProvision.Fetcher { url, dest ->
      when {
        url.endsWith(".jar") -> if (++jarAttempts < 3) error("HTTP 429") else dest.writeText(body)
        url.endsWith(".sha1") -> dest.writeText(sha1)
        else -> error("HTTP 404")
      }
    }
    RobolectricDepsProvision.ensure(
      artifact,
      File(dir, "deps"),
      fetcher = fetcher,
      pause = { pauses += it },
      log = {},
    )
    assertEquals(3, jarAttempts)
    assertEquals(listOf(1, 2), pauses)

    val missing = RobolectricDepsProvision.Fetcher { _, _ ->
      jarAttempts++
      error("HTTP 404")
    }
    jarAttempts = 0
    assertFailsWith<IllegalStateException> {
      RobolectricDepsProvision.ensure(
        artifact,
        File(dir, "other"),
        fetcher = missing,
        pause = {},
        log = {},
      )
    }
    assertEquals(1, jarAttempts)
  }

  @Test
  fun `Robolectric's own verified copy is linked instead of fetched`() = fixture { dir ->
    val m2 = File(dir, "m2")
    val seed =
      File(m2, "org/robolectric/android-all-instrumented/${artifact.version}/${artifact.fileName}")
    seed.parentFile.mkdirs()
    seed.writeText("framework bytes")
    File(seed.path + ".sha512").writeText(RobolectricDepsProvision.digest(seed, "SHA-512"))
    assertEquals(seed, RobolectricDepsProvision.mavenLocalCopy(artifact, m2))
    val fetcher = RobolectricDepsProvision.Fetcher { url, _ -> error("fetched $url") }
    val out = File(dir, "deps")
    val jar =
      RobolectricDepsProvision.ensure(
        artifact,
        out,
        fetcher = fetcher,
        pause = {},
        log = {},
        seed = seed,
      )
    assertEquals("framework bytes", jar.readText())
    assertEquals(listOf(jar.name), files(out))
  }

  @Test
  fun `a copy that fails its checksum is fetched again`() = fixture { dir ->
    val body = "framework bytes"
    val sha1 = RobolectricDepsProvision.digest(File(dir, "b").apply { writeText(body) }, "SHA-1")
    // Cut short, as a copy across file systems is when its process is killed.
    val seed =
      File(dir, "seed/${artifact.fileName}").apply {
        parentFile.mkdirs()
        writeText("framework")
      }
    val fetched = mutableListOf<String>()
    val fetcher = RobolectricDepsProvision.Fetcher { url, dest ->
      fetched += url
      when {
        url.endsWith(".jar") -> dest.writeText(body)
        url.endsWith(".sha1") -> dest.writeText(sha1)
        else -> error("HTTP 404")
      }
    }
    val jar =
      RobolectricDepsProvision.ensure(
        artifact,
        File(dir, "deps"),
        fetcher = fetcher,
        pause = {},
        log = {},
        seed = seed,
      )
    assertEquals(body, jar.readText())
    assertTrue(artifact.url() in fetched, "$fetched")
  }

  @Test
  fun `a fetch a killed process left behind is cleared`() = fixture { dir ->
    val body = "bytes"
    val sha1 = RobolectricDepsProvision.digest(File(dir, "b").apply { writeText(body) }, "SHA-1")
    val out = File(dir, "deps").apply { mkdirs() }
    File(out, ".${artifact.fileName}.dead.tmp").writeText("half a jar")
    val fetcher = RobolectricDepsProvision.Fetcher { url, dest ->
      when {
        url.endsWith(".jar") -> dest.writeText(body)
        url.endsWith(".sha1") -> dest.writeText(sha1)
        else -> error("HTTP 404")
      }
    }
    RobolectricDepsProvision.ensure(artifact, out, fetcher = fetcher, pause = {}, log = {})
    assertEquals(listOf(artifact.fileName), files(out))
  }

  @Test
  fun `concurrent callers fetch an artifact once`() = fixture { dir ->
    val body = "bytes"
    val sha1 = RobolectricDepsProvision.digest(File(dir, "b").apply { writeText(body) }, "SHA-1")
    val jarFetches = java.util.concurrent.atomic.AtomicInteger()
    val fetcher = RobolectricDepsProvision.Fetcher { url, dest ->
      when {
        url.endsWith(".jar") -> {
          jarFetches.incrementAndGet()
          Thread.sleep(200)
          dest.writeText(body)
        }
        url.endsWith(".sha1") -> dest.writeText(sha1)
        else -> error("HTTP 404")
      }
    }
    val out = File(dir, "deps")
    val threads =
      List(4) {
        Thread {
          RobolectricDepsProvision.ensure(artifact, out, fetcher = fetcher, pause = {}, log = {})
        }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertEquals(1, jarFetches.get())
    assertEquals(body, File(out, artifact.fileName).readText())
  }
}
