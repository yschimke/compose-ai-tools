package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.fakeselection.FakeAppComposeSelection
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A one-shot Android `bundle render` orders its flat classpath by the daemon lane's own app-Compose
 * decision, asked of the renderer's jars in an isolated loader.
 */
class BundleAppComposeTest {
  private val leading = listOf(File("launcher"), File("config"), File("classes"))
  private val app = listOf(File("app-compose.jar"))
  private val renderer = listOf(File("renderer.jar"))
  private val trailing = listOf(File("android.jar"))

  @Test
  fun `the daemon's Compose runs ahead of an older app's`() {
    assertEquals(
      leading + renderer + app + trailing,
      BundleAppCompose.classpath(
        BundleAppCompose.Decision(preferApp = false, logLine = ""),
        leading,
        app,
        renderer,
        trailing,
      ),
    )
  }

  @Test
  fun `a newer app's Compose, or no decision, keeps the app first`() {
    for (decision in listOf(BundleAppCompose.Decision(preferApp = true, logLine = ""), null)) {
      assertEquals(
        leading + app + renderer + trailing,
        BundleAppCompose.classpath(decision, leading, app, renderer, trailing),
      )
    }
  }

  /** A jar holding the fake selection's classes plus the Kotlin standard library's jar. */
  private fun rendererJars(root: File): List<File> {
    val jar = File(root, "renderer.jar")
    val classes =
      listOf(
        FakeAppComposeSelection::class.java,
        FakeAppComposeSelection.Decision::class.java,
      )
    ZipOutputStream(jar.outputStream()).use { zip ->
      for (type in classes) {
        val name = type.name.replace('.', '/') + ".class"
        zip.putNextEntry(ZipEntry(name))
        type.classLoader.getResourceAsStream(name)!!.use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
    val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
    return listOf(jar, stdlib)
  }

  @Test
  fun `the decision comes from the renderer's own selection class`() {
    val root = Files.createTempDirectory("bundle-app-compose").toFile()
    try {
      val jars = rendererJars(root)
      val decide = { entry: String, mode: String ->
        BundleAppCompose.decide(
          jars,
          listOf(File(root, entry)),
          mode = mode,
          selectionClass = FakeAppComposeSelection::class.java.name,
        )
      }
      assertEquals(
        BundleAppCompose.Decision(preferApp = false, logLine = "fake mode=auto runs=daemon"),
        decide("app-older.jar", "auto"),
      )
      assertEquals(true, decide("app-newer.jar", "auto")!!.preferApp)
      assertEquals(true, decide("app-older.jar", "app")!!.preferApp)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a renderer without the selection keeps the old order`() {
    val root = Files.createTempDirectory("bundle-app-compose").toFile()
    try {
      val unrelated = File(root, "other.jar")
      ZipOutputStream(unrelated.outputStream()).use { it.putNextEntry(ZipEntry("x.txt")) }
      assertNull(BundleAppCompose.decide(listOf(unrelated), emptyList()))
      assertNull(BundleAppCompose.decide(emptyList(), emptyList()))
    } finally {
      root.deleteRecursively()
    }
  }
}
