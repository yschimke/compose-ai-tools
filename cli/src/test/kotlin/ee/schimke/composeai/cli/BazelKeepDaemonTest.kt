package ee.schimke.composeai.cli

import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opt-in reusable Bazel render daemon's keying and reload decisions (#6104). */
class BazelKeepDaemonTest {
  private val root: File = Files.createTempDirectory("keep-daemon").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `the flag, the environment or bazel json opts in, and nothing else does`() {
    val off = BazelBuildConfig()
    assertFalse(BazelKeepDaemon.requested(listOf("render"), off) { null })
    assertTrue(BazelKeepDaemon.requested(listOf("render", "--keep-daemon"), off) { null })
    assertTrue(BazelKeepDaemon.requested(listOf("render"), off) { "1" })
    assertFalse(BazelKeepDaemon.requested(listOf("render"), off) { "0" })
    assertTrue(BazelKeepDaemon.requested(listOf("render"), off.copy(keepDaemon = true)) { null })
  }

  @Test
  fun `moving a preview keeps the daemon and changing the preview set does not`() {
    val manifest = root.resolve("previews.json")
    fun keyFor(previews: String): String {
      manifest.writeText("""{"module":"//ui","variant":"bin","previews":[$previews]}""")
      return BazelKeepDaemon.key("descriptor", emptyList(), manifest)
    }
    val original = keyFor("""{"id":"a","className":"A","functionName":"f","bodyLine":10}""")
    assertEquals(
      original,
      keyFor("""{"id":"a","className":"A","functionName":"f","bodyLine":42}"""),
    )
    assertNotEquals(
      original,
      keyFor(
        """{"id":"a","className":"A","functionName":"f","bodyLine":10},""" +
          """{"id":"b","className":"A","functionName":"g","bodyLine":20}"""
      ),
    )
  }

  @Test
  fun `a changed linked resource starts a fresh daemon`() {
    val manifest = root.resolve("previews.json").apply { writeText("{}") }
    val apk = root.resolve("resources.ap_").apply { writeText("one") }
    val before = BazelKeepDaemon.key("descriptor", listOf(apk), manifest)
    apk.writeText("two")
    assertNotEquals(before, BazelKeepDaemon.key("descriptor", listOf(apk), manifest))
  }

  @Test
  fun `a rebuilt jar is reported once, and only after a first render`() {
    val dir = root.resolve("daemon").apply { mkdirs() }
    val jar = root.resolve("ui.jar").apply { writeText("v1") }
    val lib = root.resolve("lib.jar").apply { writeText("lib") }
    val jars = listOf(jar.path, lib.path)
    assertNull(BazelKeepDaemon.recordJars(dir, jars), "nothing to reload on the first render")
    assertNull(BazelKeepDaemon.recordJars(dir, jars), "nothing changed")
    jar.writeText("version two")
    assertEquals(jar.path, BazelKeepDaemon.recordJars(dir, jars))
    assertNull(BazelKeepDaemon.recordJars(dir, jars), "already reported")
  }

  @Test
  fun `a project jar replaced with the same size and mtime is still reported`() {
    val dir = root.resolve("daemon").apply { mkdirs() }
    val jar = root.resolve("ui.jar").apply { writeText("aaaa") }
    val stamp = jar.lastModified()
    val jars = listOf(jar.path)
    val project = setOf(jar.absolutePath)
    assertNull(BazelKeepDaemon.recordJars(dir, jars, project))
    jar.writeText("bbbb")
    jar.setLastModified(stamp)
    assertEquals(jar.path, BazelKeepDaemon.recordJars(dir, jars, project))
  }

  @Test
  fun `a dependency jar with an unchanged content digest is not reported after a remount`() {
    val dir = root.resolve("daemon").apply { mkdirs() }
    val lib = root.resolve("lib.jar").apply { writeText("lib") }
    val jars = listOf(lib.path)
    var digest = byteArrayOf(1, 2, 3)
    val stamper = FileStamper("digest", readDigest = { digest })
    assertNull(BazelKeepDaemon.recordJars(dir, jars, stamp = stamper::stamp))
    // A FUSE remount rotates mtimes on unchanged outputs; the digest attribute stays put.
    lib.setLastModified(lib.lastModified() - 60_000)
    assertNull(BazelKeepDaemon.recordJars(dir, jars, stamp = stamper::stamp))
    digest = byteArrayOf(4, 5, 6)
    assertEquals(lib.path, BazelKeepDaemon.recordJars(dir, jars, stamp = stamper::stamp))
  }

  @Test
  fun `a dependency jar without a content digest is still stamped by size and mtime`() {
    val dir = root.resolve("daemon").apply { mkdirs() }
    val lib = root.resolve("lib.jar").apply { writeText("lib") }
    val jars = listOf(lib.path)
    val stamper = FileStamper(null)
    assertNull(BazelKeepDaemon.recordJars(dir, jars, stamp = stamper::stamp))
    lib.setLastModified(lib.lastModified() - 60_000)
    assertEquals(lib.path, BazelKeepDaemon.recordJars(dir, jars, stamp = stamper::stamp))
  }

  @Test
  fun `project jars are hashed through the supplied memo`() {
    val dir = root.resolve("daemon").apply { mkdirs() }
    val jar = root.resolve("ui.jar").apply { writeText("aaaa") }
    val hashed = mutableListOf<File>()
    val hash = { file: File ->
      hashed += file
      "memo"
    }
    BazelKeepDaemon.recordJars(dir, listOf(jar.path), setOf(jar.absolutePath), hash = hash)
    assertEquals(listOf(jar), hashed)
  }

  @Test
  fun `a different idle timeout is a different daemon`() {
    fun dirFor(idle: Int) = prepare(idle).dir
    assertNotEquals(dirFor(60), dirFor(3600))
  }

  @Test
  fun `the shared descriptor names a socket and an idle timeout`() {
    val prepared = prepare(120)
    val shared = DaemonLaunchBuilder.decode(prepared.descriptor.readText())
    assertEquals(
      prepared.dir.resolve("d.sock").absolutePath,
      shared.systemProperties["composeai.daemon.socketPath"],
    )
    assertEquals("120000", shared.systemProperties["composeai.daemon.keepAliveIdleMs"])
    assertEquals("ui.jar", shared.systemProperties["composeai.daemon.userClassDirs"])
  }

  @Test
  fun `stopping waits for the daemon to close its socket`() {
    val socket = root.resolve("d.sock")
    val server =
      java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).apply {
        bind(java.net.UnixDomainSocketAddress.of(socket.toPath()))
      }
    val accepting = Thread { runCatching { while (true) server.accept().close() } }
    accepting.start()
    assertFalse(BazelKeepDaemon.awaitGone(socket, timeoutMs = 200), "still listening")
    Thread {
      Thread.sleep(300)
      server.close()
    }
      .start()
    assertTrue(BazelKeepDaemon.awaitGone(socket), "gone once the listener closes")
    accepting.join(5_000)
  }

  private fun prepare(idleTimeoutSeconds: Int): BazelKeepDaemon.Prepared {
    val descriptor =
      root.resolve("daemon-launch.json").apply {
        writeText(
          DaemonLaunchBuilder.encode(
            DaemonLaunchBuilder.build(
              modulePath = "//ui",
              variant = "bin",
              mainClass = "Main",
              classpath = emptyList(),
              jvmArgs = emptyList(),
              systemProperties = mapOf("composeai.daemon.userClassDirs" to "ui.jar"),
              workingDirectory = root.path,
              manifestPath = "",
            )
          )
        )
      }
    return BazelKeepDaemon.prepare(
      descriptor,
      launchInputs = emptyList(),
      previewsManifest = root.resolve("previews.json"),
      idleTimeoutSeconds = idleTimeoutSeconds,
      cacheRoot = root.resolve("daemons"),
    )
  }
}
