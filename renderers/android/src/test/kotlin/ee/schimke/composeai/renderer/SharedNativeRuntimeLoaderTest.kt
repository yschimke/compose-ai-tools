package ee.schimke.composeai.renderer

import android.database.CursorWindow
import java.nio.file.Files
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
}
