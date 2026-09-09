package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the flag set [ServeBundleDaemon.androidDaemonStartupJvmArgs] hands a catalog's Android
 * daemon: an auto-created, per-classpath CDS archive on a JDK that has the feature, and remote
 * bytecode verification off, each behind its own opt-out.
 */
class ServeBundleDaemonStartupJvmArgsTest {

  private fun cdsDir(): File = Files.createTempDirectory("serve-daemon-cds").toFile()

  @Test
  fun `jdk 19 and later get an auto-created archive keyed by the classpath`() {
    val dir = cdsDir()
    val args =
      ServeBundleDaemon.androidDaemonStartupJvmArgs(
        daemonClasspath = listOf("/opt/catalog/material3.jar", "/opt/lib-daemon-android/a.jar"),
        javaFeatureVersion = 21,
        cdsDir = dir,
        cdsEnabled = true,
        verifyBytecode = false,
      )
    assertTrue("-XX:+AutoCreateSharedArchive" in args, args.toString())
    val archive = args.single { it.startsWith("-XX:SharedArchiveFile=") }
    assertTrue(
      archive.startsWith(
        "-XX:SharedArchiveFile=${dir.absolutePath}${File.separator}android-daemon-"
      ),
      archive,
    )
    assertTrue(archive.endsWith(".jsa"), archive)
    assertTrue(dir.isDirectory, "the archive directory is created so the JVM can write into it")
    // The daemon's stdout is the JSON-RPC channel: JVM warnings must go to stderr, and the dump's
    // own chatter (hundreds of "Skipping …" lines at exit) must not go anywhere.
    assertEquals(
      listOf("-Xlog:disable", "-Xlog:all=warning:stderr", "-Xlog:cds*=off:stderr"),
      args.filter { it.startsWith("-Xlog:") },
    )
    assertTrue("-XX:+DisplayVMOutputToStderr" in args, args.toString())
    assertTrue("-XX:+UnlockDiagnosticVMOptions" in args, args.toString())
    assertTrue("-XX:-BytecodeVerificationRemote" in args, args.toString())
  }

  @Test
  fun `a torn archive is unlinked before launch, an intact one is kept`() {
    val dir = cdsDir()
    val cp = listOf("/x/one.jar")
    val archivePath =
      ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 21, dir, cdsEnabled = true)
        .single { it.startsWith("-XX:SharedArchiveFile=") }
        .removePrefix("-XX:SharedArchiveFile=")
    val archive = File(archivePath)

    // Cut off mid-write: a few bytes of anything but the magic.
    archive.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
    ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 21, dir, cdsEnabled = true)
    assertFalse(archive.exists(), "a file without the dynamic-archive magic is removed")

    // Shorter than the magic itself.
    archive.writeBytes(byteArrayOf(1))
    ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 21, dir, cdsEnabled = true)
    assertFalse(archive.exists(), "a truncated header is removed")

    // A real dynamic archive starts with CDS_DYNAMIC_ARCHIVE_MAGIC in host byte order.
    val magic =
      java.nio.ByteBuffer.allocate(4)
        .order(java.nio.ByteOrder.nativeOrder())
        .putInt(0xf00baba8.toInt())
        .array()
    archive.writeBytes(magic + ByteArray(64))
    ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 21, dir, cdsEnabled = true)
    assertTrue(archive.exists(), "an archive with the right magic is left for the JVM")
    assertFalse(ServeBundleDaemon.validateArchive(archive))
  }

  @Test
  fun `the archive name follows the classpath, in order`() {
    val dir = cdsDir()
    fun archiveFor(cp: List<String>) =
      ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 21, dir, cdsEnabled = true).single {
        it.startsWith("-XX:SharedArchiveFile=")
      }
    val a = archiveFor(listOf("/x/one.jar", "/x/two.jar"))
    assertEquals(a, archiveFor(listOf("/x/one.jar", "/x/two.jar")), "same classpath, same archive")
    assertNotEquals(a, archiveFor(listOf("/x/two.jar", "/x/one.jar")), "order is part of the key")
    assertNotEquals(a, archiveFor(listOf("/x/one.jar")), "a different catalog gets its own file")
  }

  @Test
  fun `no archive flags below jdk 19 or when the archive is opted out`() {
    val dir = cdsDir()
    val cp = listOf("/x/one.jar")
    for (args in
      listOf(
        ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 17, dir, cdsEnabled = true),
        ServeBundleDaemon.androidDaemonStartupJvmArgs(cp, 21, dir, cdsEnabled = false),
      )) {
      assertFalse(args.any { it.contains("SharedArchive") }, args.toString())
      assertTrue("-XX:-BytecodeVerificationRemote" in args, args.toString())
    }
  }

  @Test
  fun `restoring verification leaves nothing but the archive flags`() {
    val args =
      ServeBundleDaemon.androidDaemonStartupJvmArgs(
        listOf("/x/one.jar"),
        21,
        cdsDir(),
        cdsEnabled = true,
        verifyBytecode = true,
      )
    assertFalse(args.any { it.contains("BytecodeVerification") }, args.toString())
    assertFalse("-XX:+UnlockDiagnosticVMOptions" in args, args.toString())
    assertEquals(6, args.size, args.toString())
  }

  @Test
  fun `everything off yields no flags at all`() {
    val args =
      ServeBundleDaemon.androidDaemonStartupJvmArgs(
        listOf("/x/one.jar"),
        21,
        cdsDir(),
        cdsEnabled = false,
        verifyBytecode = true,
      )
    assertEquals(emptyList(), args)
  }
}
