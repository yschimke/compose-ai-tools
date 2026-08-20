package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

  // ---- a checkout supplies the value, never the trust ---------------------------------------

  @Test
  fun `a host named only by the checkout is not usable`() {
    // The attack this gate exists for: a pull request adds the property, a maintainer checks the
    // branch out to look at it, and the ordinary share-preview run would otherwise send their
    // GitHub token to whoever wrote that line.
    writeProperties("$SERVE_URL_PROPERTY=https://attacker.example\n")
    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    val trust =
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = { null },
        userHome = null,
        fileSystem = fs,
      )
    val needs = trust as ServeUrlTrust.NeedsConfirmation
    assertTrue(needs.how.contains("attacker.example"), needs.how)
    assertTrue(needs.how.contains(SERVE_HOSTS_ENV), needs.how)
  }

  @Test
  fun `the environment allowlist confirms a checkout-named host`() {
    writeProperties("$SERVE_URL_PROPERTY=https://preview.coo.ee\n")
    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    val env = { name: String ->
      if (name == SERVE_HOSTS_ENV) "shots.example, preview.coo.ee" else null
    }
    assertTrue(
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = env,
        userHome = null,
        fileSystem = fs,
      )
        is ServeUrlTrust.Trusted
    )
  }

  @Test
  fun `a lookalike host does not pass as the confirmed one`() {
    writeProperties("$SERVE_URL_PROPERTY=https://preview.coo.ee.evil.example\n")
    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    val env = { name: String -> if (name == SERVE_HOSTS_ENV) "preview.coo.ee" else null }
    assertTrue(
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = env,
        userHome = null,
        fileSystem = fs,
      )
        is ServeUrlTrust.NeedsConfirmation
    )
  }

  @Test
  fun `the user's own gradle properties confirms a host, since no checkout can write it`() {
    writeProperties("$SERVE_URL_PROPERTY=https://preview.coo.ee\n")
    val userHome = "/home/dev"
    val userProps = "$userHome/.gradle/gradle.properties".toPath()
    fs.createDirectories(userProps.parent!!)
    fs.write(userProps) { writeUtf8("$SERVE_URL_PROPERTY=https://preview.coo.ee\n") }

    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    assertTrue(
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = { null },
        userHome = userHome,
        fileSystem = fs,
      )
        is ServeUrlTrust.Trusted
    )
  }

  @Test
  fun `anything from outside the checkout is already consent`() {
    // Typed for this run, or set by whoever built the sandbox: both are acts by the person whose
    // credential it is, so neither needs a second confirmation.
    val flag =
      resolveProjectServeUrl(
        root,
        args = listOf("--serve-url", "https://anywhere.example"),
        env = { null },
        fileSystem = fs,
      )!!
    assertTrue(
      confirmProjectServeHost(
        flag,
        projectRoot = root,
        env = { null },
        userHome = null,
        fileSystem = fs,
      )
        is ServeUrlTrust.Trusted
    )

    val fromEnv =
      resolveProjectServeUrl(root, env = { "https://anywhere.example" }, fileSystem = fs)!!
    assertTrue(
      confirmProjectServeHost(
        fromEnv,
        projectRoot = root,
        env = { null },
        userHome = null,
        fileSystem = fs,
      )
        is ServeUrlTrust.Trusted
    )
  }

  @Test
  fun `a gradle user home inside the checkout cannot confirm its own host`() {
    // `GRADLE_USER_HOME=$PWD/.gradle` is a real CI cache layout. Under it a branch that commits
    // both files would be confirming itself, which is the whole thing the gate prevents.
    writeProperties("$SERVE_URL_PROPERTY=https://attacker.example\n")
    val inCheckout = "/project/.gradle/gradle.properties".toPath()
    fs.createDirectories(inCheckout.parent!!)
    fs.write(inCheckout) { writeUtf8("$SERVE_URL_PROPERTY=https://attacker.example\n") }

    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    val env = { name: String -> if (name == "GRADLE_USER_HOME") "/project/.gradle" else null }
    assertTrue(
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = env,
        userHome = null,
        fileSystem = fs,
      )
        is ServeUrlTrust.NeedsConfirmation,
      "a confirmation file inside the checkout is not a confirmation",
    )
  }

  @Test
  fun `a gradle user home outside the checkout still confirms`() {
    writeProperties("$SERVE_URL_PROPERTY=https://preview.coo.ee\n")
    val outside = "/opt/ci-cache/gradle/gradle.properties".toPath()
    fs.createDirectories(outside.parent!!)
    fs.write(outside) { writeUtf8("$SERVE_URL_PROPERTY=https://preview.coo.ee\n") }

    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    val env = { name: String -> if (name == "GRADLE_USER_HOME") "/opt/ci-cache/gradle" else null }
    assertTrue(
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = env,
        userHome = null,
        fileSystem = fs,
      )
        is ServeUrlTrust.Trusted
    )
  }

  @Test
  fun `a refusal never echoes credentials that were in the url`() {
    writeProperties("$SERVE_URL_PROPERTY=https://user:hunter2@attacker.example\n")
    val resolved = resolveProjectServeUrl(root, env = { null }, fileSystem = fs)!!
    val needs =
      confirmProjectServeHost(
        resolved,
        projectRoot = root,
        env = { null },
        userHome = null,
        fileSystem = fs,
      )
        as ServeUrlTrust.NeedsConfirmation
    assertTrue("hunter2" !in needs.how, needs.how)
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
