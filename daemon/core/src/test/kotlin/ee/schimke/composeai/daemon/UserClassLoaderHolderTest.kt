package ee.schimke.composeai.daemon

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [UserClassLoaderHolder] — the parent/child classloader split that lands B2.0
 * (see [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)).
 *
 * The soak loop here is the cheap belt-and-braces pin that recycled child loaders actually GC — if
 * a future change in [UserClassLoaderHolder] grows a strong reference (e.g. caches old loaders for
 * diagnostics), the [WeakReference]-based [UserClassLoaderHolder.liveLoaderCount] count stays > 1
 * and the test fails loudly.
 */
class UserClassLoaderHolderTest {

  @Test
  fun `currentChildLoader is lazily allocated and stable until swap`() {
    val tempDir = Files.createTempDirectory("user-classes-").toFile().also { it.deleteOnExit() }
    val holder = UserClassLoaderHolder(urls = listOf(tempDir.toURI().toURL()))

    val first = holder.currentChildLoader()
    val second = holder.currentChildLoader()
    assertNotNull(first)
    assertEquals(first, second)

    holder.swap()
    val third = holder.currentChildLoader()
    assertNotSame(
      "swap() must produce a fresh URLClassLoader on next currentChildLoader() read",
      first,
      third,
    )
  }

  @Test
  fun `urlsFromSysprop returns empty when unset`() {
    val previous = System.getProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
    System.clearProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
    try {
      assertTrue(UserClassLoaderHolder.urlsFromSysprop().isEmpty())
    } finally {
      if (previous != null) System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, previous)
    }
  }

  @Test
  fun `urlsFromSysprop splits on path separator and filters non-existent`() {
    val tempA = Files.createTempDirectory("user-classes-a-").toFile().also { it.deleteOnExit() }
    val tempB = Files.createTempDirectory("user-classes-b-").toFile().also { it.deleteOnExit() }
    val nonExistent = File(tempA.parentFile, "this-does-not-exist-${System.nanoTime()}")
    val raw =
      listOf(tempA.absolutePath, nonExistent.absolutePath, tempB.absolutePath)
        .joinToString(File.pathSeparator)
    val previous = System.getProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
    System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, raw)
    try {
      val urls = UserClassLoaderHolder.urlsFromSysprop()
      assertEquals(2, urls.size)
      assertTrue(urls[0].path.contains(tempA.name))
      assertTrue(urls[1].path.contains(tempB.name))
    } finally {
      if (previous != null) System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, previous)
      else System.clearProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
    }
  }

  @Test
  fun `onSwap callback fires on initial allocation and after swap`() {
    val tempDir = Files.createTempDirectory("user-classes-cb-").toFile().also { it.deleteOnExit() }
    val seen = mutableListOf<URLClassLoader>()
    val holder =
      UserClassLoaderHolder(urls = listOf(tempDir.toURI().toURL()), onSwap = { seen.add(it) })

    val first = holder.currentChildLoader()
    holder.swap()
    val second = holder.currentChildLoader()

    assertEquals(2, seen.size)
    assertEquals(first, seen[0])
    assertEquals(second, seen[1])
  }

  @Test
  fun `urlsFromSysprop sorts directories before jars`() {
    // AGP's variant classpath lists the runtime-bundled jar before the kotlinc output directory
    // for the same set of user classes; the daemon must consult the directory first because that's
    // where compileDebugKotlin writes fresh bytes on every save. URLClassLoader.findClass walks
    // declaration order and returns the first match, so this ordering is load-bearing for the
    // edit → render save loop. Regression marker for the "first edit updates, subsequent edits
    // stick" symptom diagnosed via the user-class-dirs log line in DaemonMain.
    val parent =
      Files.createTempDirectory("user-classes-order-").toFile().also { it.deleteOnExit() }
    val jarFirst = File(parent, "runtime_app_classes_jar.jar").apply { writeBytes(byteArrayOf()) }
    val dirSecond = File(parent, "compileDebugKotlin-classes").apply { mkdirs() }
    val jarThird = File(parent, "R.jar").apply { writeBytes(byteArrayOf()) }
    val dirFourth = File(parent, "kotlin-classes").apply { mkdirs() }
    val raw =
      listOf(
          jarFirst.absolutePath,
          dirSecond.absolutePath,
          jarThird.absolutePath,
          dirFourth.absolutePath,
        )
        .joinToString(File.pathSeparator)
    val previous = System.getProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
    System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, raw)
    try {
      val urls = UserClassLoaderHolder.urlsFromSysprop()
      assertEquals(4, urls.size)
      // First two are directories; relative order preserved (dirSecond → dirFourth).
      assertTrue("urls[0] should be a directory: ${urls[0]}", urls[0].path.endsWith("/"))
      assertTrue("urls[1] should be a directory: ${urls[1]}", urls[1].path.endsWith("/"))
      assertTrue(urls[0].path.contains(dirSecond.name))
      assertTrue(urls[1].path.contains(dirFourth.name))
      // Last two are jars; relative order preserved (jarFirst → jarThird).
      assertTrue("urls[2] should be a jar: ${urls[2]}", urls[2].path.endsWith(".jar"))
      assertTrue("urls[3] should be a jar: ${urls[3]}", urls[3].path.endsWith(".jar"))
      assertTrue(urls[2].path.contains(jarFirst.name))
      assertTrue(urls[3].path.contains(jarThird.name))
    } finally {
      if (previous != null) System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, previous)
      else System.clearProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
    }
  }

  @Test
  fun `soak loop GCs recycled loaders within bounded window`() {
    // CLASSLOADER.md § Risks 1: assert that recycled loaders collect within 2 GCs (roughly — see
    // liveLoaderCount which forces 2 system.gc() calls). After 50 swaps with no work between
    // them, only the current child loader should be live; 49 prior loaders should have GC'd.
    val tempDir =
      Files.createTempDirectory("user-classes-soak-").toFile().also { it.deleteOnExit() }
    val holder = UserClassLoaderHolder(urls = listOf(tempDir.toURI().toURL()))

    // Trigger initial allocation, then swap 50 times (each swap drops the ref; no work in between
    // so old loaders don't accidentally end up referenced from the test method's locals).
    holder.currentChildLoader()
    repeat(50) {
      holder.swap()
      // Force a fresh allocation between each swap so the *previous* loader has actually been
      // observable as the current one — without this we'd never allocate any of them.
      holder.currentChildLoader()
    }

    val live = holder.liveLoaderCount()
    assertTrue(
      "After 50 swaps, only the current loader should be live; got $live (CLASSLOADER.md § Risks 1).",
      live <= 1,
    )
  }
}
