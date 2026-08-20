package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The project's preview server, and its precedence. Same shape as the version pin's tests, because
 * it is the same contract: a fact about the project, overridable per run, and never worse broken
 * than absent.
 */
class ProjectPreviewServerTest {

  private val fs = FakeFileSystem()

  private val root = File("/project")

  private fun writeProperties(text: String) {
    val path = "/project/gradle.properties".toPath()
    fs.createDirectories(path.parent!!)
    fs.write(path) { writeUtf8(text) }
  }

  @Test
  fun `a project that names a server configures share-preview without a flag`() {
    writeProperties("$SERVE_URL_PROPERTY=https://preview.coo.ee\n")
    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)
    assertEquals("https://preview.coo.ee", resolved?.url)
    assertEquals(ServeUrlSource.GRADLE_PROPERTIES, resolved?.source)
  }

  @Test
  fun `the flag beats the environment, which beats the project`() {
    writeProperties("$SERVE_URL_PROPERTY=https://from-the-project\n")
    assertEquals(
      "https://from-the-flag",
      resolveProjectServeUrl(
          root,
          args = listOf("--serve-url", "https://from-the-flag"),
          env = { "https://from-the-env" },
          fileSystem = fs,
        )
        ?.url,
    )
    assertEquals(
      "https://from-the-env",
      resolveProjectServeUrl(root, env = { "https://from-the-env" }, fileSystem = fs)?.url,
    )
    assertEquals(
      "https://from-the-project",
      resolveProjectServeUrl(root, env = { null }, fileSystem = fs)?.url,
    )
  }

  @Test
  fun `a trailing slash is not a different host`() {
    writeProperties("$SERVE_URL_PROPERTY=https://preview.coo.ee/\n")
    assertEquals(
      "https://preview.coo.ee",
      resolveProjectServeUrl(root, env = { null }, fileSystem = fs)?.url,
    )
  }

  @Test
  fun `an empty or absent setting configures nothing`() {
    writeProperties("$SERVE_URL_PROPERTY=\n")
    assertNull(resolveProjectServeUrl(root, env = { null }, fileSystem = fs))

    writeProperties("composePreview.version=1.2.3\n")
    assertNull(resolveProjectServeUrl(root, env = { null }, fileSystem = fs))
  }

  @Test
  fun `no project is not an error, and the overrides still apply`() {
    assertNull(resolveProjectServeUrl(null, env = { null }, fileSystem = fs))
    assertEquals(
      "https://from-the-env",
      resolveProjectServeUrl(null, env = { "https://from-the-env" }, fileSystem = fs)?.url,
    )
  }

  @Test
  fun `an unreadable properties file is no worse than an absent one`() {
    // Nothing written at all: the read fails and resolution falls through rather than throwing.
    assertNull(resolveProjectServeUrl(root, env = { null }, fileSystem = fs))
  }

  @Test
  fun `a server setting does not disturb the version pin beside it`() {
    writeProperties(
      """
      composePreview.version=1.2.3
      $SERVE_URL_PROPERTY=https://preview.coo.ee
      """
        .trimIndent()
    )
    assertEquals("1.2.3", readGradlePropertiesPin(root, fs))
    assertEquals(
      "https://preview.coo.ee",
      resolveProjectServeUrl(root, env = { null }, fileSystem = fs)?.url,
    )
  }
}
