package ee.schimke.composeai.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reproduction harness for the bundle-daemon IR-replay classloader gap (Codex review on #1672).
 *
 * The daemon replays an IR-backed preview through code that the daemon module ships on its **launch
 * classpath** — `:renderer-android`'s `TileIrReplayComposable` (protolayout) or the connector's
 * `RemoteComposeIrReplay` (Remote Compose). Those classes are therefore loaded by the daemon's
 * **parent** classloader. The renderer/player libraries they call
 * (`androidx.wear.tiles.renderer.*`, `androidx.compose.remote.player.*`) are carried in the
 * *bundle* and land in `composeai.daemon.userClassDirs` — the **child** ([UserClassLoaderHolder])
 * loader. A class resolves its references through its own defining loader, and the parent never
 * consults the child, so the replay code throws `NoClassDefFoundError` at link time even though the
 * carried lib is physically present in the child. The `bundle daemon` launch `-cp` (sidecar jars +
 * `android.jar`) doesn't carry these libs, so there's nowhere on the parent for them to resolve.
 *
 * This is pure JVM classloading — no Android/Robolectric needed to pin it. The first test
 * reproduces the failure with the real [UserClassLoaderHolder] as the child; the second shows the
 * fix direction (the carried lib also on the parent `-cp`, i.e. what `BundleDaemonCommand` should
 * add) makes the same call link. The actual fix (and which entry point applies it) is a separate
 * change tracked off this harness; this only characterises the mechanism and guards the resolution.
 */
class IrReplayClassloaderTopologyTest {

  private val playerFqn = "androidx.fake.Player"
  private val playerSrc =
    "package androidx.fake; public class Player { public static String ping() { return \"player-ok\"; } }"

  // Stands in for the parent-loaded replay host (TileIrReplayComposable / RemoteComposeIrReplay);
  // the `ee.schimke.composeai.daemon.*` package is one the child loader also forces to the parent.
  private val replayFqn = "ee.schimke.composeai.daemon.FakeReplayHost"
  private val replaySrc =
    "package ee.schimke.composeai.daemon; public class FakeReplayHost { " +
      "public String touch() { return androidx.fake.Player.ping(); } }"

  @Test
  fun `parent-loaded replay cannot see a player carried only in the child loader`() {
    val playerDir = compile(emptyList(), playerFqn, playerSrc)
    // The replay host knows the player at compile time but is shipped without it on its own dir,
    // mirroring the daemon: the connector/renderer is a sidecar dep, the player is carried.
    val parentDir = compile(listOf(playerDir), replayFqn, replaySrc)

    // Parent = daemon launch `-cp` analogue: has the replay host, NOT the player.
    val parent =
      URLClassLoader(arrayOf(parentDir.toURI().toURL()), ClassLoader.getPlatformClassLoader())
    // Child = `userClassDirs` analogue: the carried player lib lives here, and only here.
    val holder =
      UserClassLoaderHolder(urls = listOf(playerDir.toURI().toURL()), parentSupplier = { parent })
    val child = holder.currentChildLoader()
    // The carried player is genuinely loadable *from the child* — even for an `androidx.*` name the
    // child's own URLs are searched once the parent misses (`super.loadClass` is parent-first, then
    // `findClass`), so this really does model a lib present in `userClassDirs`. The bug is that the
    // parent-loaded host below resolves against the parent and never consults this child.
    assertEquals(
      "the carried player must be loadable from the child (else the topology isn't modelled)",
      child,
      child.loadClass(playerFqn).classLoader,
    )

    val host = parent.loadClass(replayFqn).getDeclaredConstructor().newInstance()
    try {
      host.javaClass.getMethod("touch").invoke(host)
      fail("expected the parent-loaded replay host to fail linking the child-only player")
    } catch (e: InvocationTargetException) {
      assertTrue(
        "expected a linkage failure, got ${e.cause}",
        e.cause is NoClassDefFoundError || e.cause is ClassNotFoundException,
      )
    }
  }

  @Test
  fun `carrying the player on the parent classpath links the replay (fix direction)`() {
    val playerDir = compile(emptyList(), playerFqn, playerSrc)
    val parentDir = compile(listOf(playerDir), replayFqn, replaySrc)

    // Fix direction: the bundle-daemon launch `-cp` also carries the player (what
    // BundleDaemonCommand
    // should add), so the parent-loaded replay host links it.
    val parent =
      URLClassLoader(
        arrayOf(parentDir.toURI().toURL(), playerDir.toURI().toURL()),
        ClassLoader.getPlatformClassLoader(),
      )
    val host = parent.loadClass(replayFqn).getDeclaredConstructor().newInstance()
    assertEquals("player-ok", host.javaClass.getMethod("touch").invoke(host))
  }

  /** Compile [fqn] (with [source]) against [classpath] into a fresh dir and return that dir. */
  private fun compile(classpath: List<File>, fqn: String, source: String): File {
    val compiler =
      ToolProvider.getSystemJavaCompiler()
        ?: error("test requires a JDK (system Java compiler unavailable)")
    val srcRoot = newDir("ir-cl-src")
    val srcFile = File(srcRoot, fqn.replace('.', '/') + ".java")
    srcFile.parentFile.mkdirs()
    srcFile.writeText(source)
    val outDir = newDir("ir-cl-out")
    val args = mutableListOf("-d", outDir.absolutePath)
    if (classpath.isNotEmpty()) {
      args += "-classpath"
      args += classpath.joinToString(File.pathSeparator) { it.absolutePath }
    }
    args += srcFile.absolutePath
    val err = ByteArrayOutputStream()
    val rc = compiler.run(null, null, err, *args.toTypedArray())
    check(rc == 0) { "javac failed for $fqn:\n$err" }
    return outDir
  }

  private fun newDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }
}
