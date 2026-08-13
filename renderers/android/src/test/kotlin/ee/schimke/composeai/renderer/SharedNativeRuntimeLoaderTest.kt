package ee.schimke.composeai.renderer

import android.database.CursorWindow
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.pluginapi.NativeRuntimeLoader
import org.robolectric.util.inject.Injector

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SharedNativeRuntimeLoaderTest {
  @Test
  fun `service selects shared loader and publishes a complete versioned cache`() {
    val injector = Injector.Builder(CursorWindow::class.java.classLoader).build()

    val loader = injector.getInstance(NativeRuntimeLoader::class.java)

    assertTrue(loader is SharedNativeRuntimeLoader)
    val cache = SharedNativeRuntimeLoader.cacheRoot().resolve(SharedNativeRuntimeLoader.cacheKey())
    assertTrue(Files.isRegularFile(cache.resolve(".complete")))
    assertTrue(
      Files.isRegularFile(cache.resolve(System.mapLibraryName("robolectric-nativeruntime")))
    )
  }

  @Test
  fun `sandbox library copies are distinct and exclude shared runtime data`() {
    val cache = SharedNativeRuntimeLoader.cacheRoot().resolve(SharedNativeRuntimeLoader.cacheKey())
    val cachedLibrary = cache.resolve(System.mapLibraryName("robolectric-nativeruntime"))

    val first = SharedNativeRuntimeLoader.createSandboxLibraryCopy(cache)
    val second = SharedNativeRuntimeLoader.createSandboxLibraryCopy(cache)
    try {
      assertNotEquals(first, second)
      assertTrue(!Files.isSameFile(cachedLibrary, first))
      assertTrue(!Files.isSameFile(cachedLibrary, second))
      assertTrue(Files.size(cachedLibrary) == Files.size(first))
      assertTrue(Files.size(cachedLibrary) == Files.size(second))
      assertEquals(first.parent, second.parent)
      assertTrue(first.parent.fileName.toString().startsWith("jvm-"))
      assertEquals("sandbox-libraries", first.parent.parent.parent.fileName.toString())
    } finally {
      Files.deleteIfExists(first)
      Files.deleteIfExists(second)
    }
  }

  /**
   * Regression test for issue #3754.
   *
   * A render JVM must never register a path shared with other render JVMs for deletion at exit.
   * Every worker unlinks its own copy as soon as `System.load` returns, so a shared directory is
   * typically empty when a worker exits — its `deleteOnExit` hook then succeeds and removes the
   * directory out from under a concurrent worker that is midway through creating its own copy
   * there, failing that render with `NoSuchFileException`.
   */
  @Test
  fun `exit deletion claims only paths private to this process`() {
    // Hermetic: a stand-in cache directory holding just the library the copy step reads, so the
    // seam under test is exercised without depending on a real 200 MB extraction.
    val root = Files.createTempDirectory("shared-native-runtime-test")
    val cache = Files.createDirectory(root.resolve("runtime-fake"))
    Files.writeString(cache.resolve(System.mapLibraryName("robolectric-nativeruntime")), "so")
    val sharedRoot = root.resolve("sandbox-libraries").resolve("runtime-fake")

    val claimed = mutableListOf<java.nio.file.Path>()
    val previousRegistrar = SharedNativeRuntimeLoader.deleteAtExit
    val previousRoot = System.getProperty(SharedNativeRuntimeLoader.CACHE_DIR_PROPERTY)
    System.setProperty(SharedNativeRuntimeLoader.CACHE_DIR_PROPERTY, root.toString())
    SharedNativeRuntimeLoader.deleteAtExit = java.util.function.Consumer { claimed.add(it) }

    try {
      val first = SharedNativeRuntimeLoader.createSandboxLibraryCopy(cache)
      val second = SharedNativeRuntimeLoader.createSandboxLibraryCopy(cache)

      assertEquals(first.parent, second.parent)
      assertTrue("expected both copies to be claimed", claimed.containsAll(listOf(first, second)))
      // The regression: claiming the key directory shared with every other render JVM. That
      // directory is empty whenever a worker exits between copies, so its deleteOnExit hook
      // succeeds and a concurrent worker's createTempFile then fails with NoSuchFileException.
      assertTrue(
        "claimed a path shared with other render JVMs: $claimed",
        claimed.none { it == sharedRoot || sharedRoot.startsWith(it) },
      )
      assertTrue(
        "claimed a path outside this JVM's own directory: $claimed",
        claimed.all { it.startsWith(first.parent) },
      )
      assertTrue(first.parent.fileName.toString().startsWith("jvm-"))
      assertEquals(sharedRoot, first.parent.parent)
    } finally {
      SharedNativeRuntimeLoader.deleteAtExit = previousRegistrar
      if (previousRoot == null) {
        System.clearProperty(SharedNativeRuntimeLoader.CACHE_DIR_PROPERTY)
      } else {
        System.setProperty(SharedNativeRuntimeLoader.CACHE_DIR_PROPERTY, previousRoot)
      }
      root.toFile().deleteRecursively()
    }
  }
}
