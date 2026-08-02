package ee.schimke.composeai.daemon

import java.net.URLClassLoader
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewResourceReaderTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  @OptIn(ExperimentalResourceApi::class)
  fun `reader resolves compose resources carried only by preview classloader`() {
    val resourcePath =
      "composeResources/com.example.generated.resources/values/strings.commonMain.cvr"
    val expected = "string|catalog title".encodeToByteArray()
    val resourceFile = tempFolder.root.toPath().resolve(resourcePath)
    Files.createDirectories(resourceFile.parent)
    Files.write(resourceFile, expected)

    URLClassLoader(arrayOf(tempFolder.root.toURI().toURL()), null).use { previewClassLoader ->
      val actual = runBlocking { previewResourceReader(previewClassLoader).read(resourcePath) }
      assertArrayEquals(expected, actual)
    }
  }
}
