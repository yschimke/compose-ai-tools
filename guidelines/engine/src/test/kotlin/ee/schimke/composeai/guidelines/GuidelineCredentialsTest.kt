package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.After
import org.junit.Test

class GuidelineCredentialsTest {
  private val dir = Files.createTempDirectory("credentials").toFile()
  private val file = File(dir, "compose-preview/credentials.json")
  private val secret = "sk-or-v1-not-a-real-key-1234"

  @After fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun `the environment wins over the file`() {
    GuidelineCredentials.store(file, secret)
    val resolved =
      GuidelineCredentials.openRouter(
        env = { if (it == GuidelineCredentials.ENV) " sk-or-env " else null },
        file = file,
      )
    assertThat(resolved)
      .isEqualTo(GuidelineCredentials.Resolved("sk-or-env", GuidelineCredentials.ENV))
  }

  @Test
  fun `a stored key is read back from the file when the environment has none`() {
    GuidelineCredentials.store(file, "  $secret\n")
    val resolved = GuidelineCredentials.openRouter(env = { "" }, file = file)
    assertThat(resolved).isEqualTo(GuidelineCredentials.Resolved(secret, file.path))
    assertThat(resolved!!.masked).isEqualTo("…1234")
    assertThat(resolved.toString()).doesNotContain(secret)
  }

  @Test
  fun `the file is created owner-only`() {
    GuidelineCredentials.store(file, secret)
    val permissions = Files.getPosixFilePermissions(file.toPath())
    assertThat(permissions)
      .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
  }

  @Test
  fun `other providers' keys survive a store and a forget`() {
    file.parentFile.mkdirs()
    file.writeText("""{"schema":"${GuidelineCredentials.SCHEMA_V1}","keys":{"other":"k-other"}}""")
    GuidelineCredentials.store(file, secret)
    assertThat(GuidelineCredentials.forget(file)).isTrue()
    assertThat(GuidelineCredentials.forget(file)).isFalse()
    assertThat(file.readText()).contains("k-other")
    assertThat(file.readText()).doesNotContain(secret)
    assertThat(GuidelineCredentials.openRouter(env = { null }, file = file)).isNull()
  }

  @Test
  fun `an unreadable file is reported without quoting it`() {
    file.parentFile.mkdirs()
    file.writeText("""{"keys": {"openrouter": "$secret" """)
    val warnings = mutableListOf<String>()
    assertThat(GuidelineCredentials.openRouter(env = { null }, file = file) { warnings += it })
      .isNull()
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).doesNotContain(secret)
  }

  @Test
  fun `a key readable by others is still used, with a warning`() {
    GuidelineCredentials.store(file, secret)
    Files.setPosixFilePermissions(
      file.toPath(),
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OTHERS_READ,
      ),
    )
    val warnings = mutableListOf<String>()
    assertThat(GuidelineCredentials.openRouter(env = { null }, file = file) { warnings += it }?.key)
      .isEqualTo(secret)
    assertThat(warnings.single()).contains("chmod 600")
  }

  @Test
  fun `a key with spaces or several lines is refused`() {
    assertThat(GuidelineCredentials.validKey("sk-or a")).isFalse()
    assertThat(GuidelineCredentials.validKey("sk-or\nb")).isFalse()
    assertThat(GuidelineCredentials.validKey("")).isFalse()
    assertThat(GuidelineCredentials.validKey(secret)).isTrue()
  }

  @Test
  fun `the default location follows XDG, then HOME, and never the working directory`() {
    val env = mutableMapOf<String, String>()
    assertThat(GuidelineCredentials.defaultFileOrNull(prop = { null }) { env[it] }).isNull()
    assertThat(GuidelineCredentials.defaultFileOrNull(prop = { "relative" }) { env[it] }).isNull()
    env["HOME"] = "/home/u"
    assertThat(GuidelineCredentials.defaultFileOrNull(prop = { null }) { env[it] })
      .isEqualTo(File("/home/u/.config/compose-preview/credentials.json"))
    env["XDG_CONFIG_HOME"] = "/xdg"
    assertThat(GuidelineCredentials.defaultFileOrNull(prop = { null }) { env[it] })
      .isEqualTo(File("/xdg/compose-preview/credentials.json"))
    env[GuidelineCredentials.FILE_ENV] = "/elsewhere/keys.json"
    assertThat(GuidelineCredentials.defaultFileOrNull(prop = { null }) { env[it] })
      .isEqualTo(File("/elsewhere/keys.json"))
  }

  @Test
  fun `trust is per canonical project root and kept beside the key`() {
    val project = File(dir, "app").apply { mkdirs() }
    val child = File(project, "nested").apply { mkdirs() }
    GuidelineCredentials.store(file, secret)
    assertThat(GuidelineCredentials.isTrusted(file, project)).isFalse()

    GuidelineCredentials.trust(file, File(project, "../app"))
    GuidelineCredentials.trust(file, project)
    assertThat(GuidelineCredentials.isTrusted(file, project)).isTrue()
    assertThat(GuidelineCredentials.isTrusted(file, child)).isFalse()
    assertThat(GuidelineCredentials.trustedProjects(file)).containsExactly(project.canonicalPath)
    assertThat(GuidelineCredentials.openRouter(env = { null }, file = file)?.key).isEqualTo(secret)

    assertThat(GuidelineCredentials.untrust(file, project)).isTrue()
    assertThat(GuidelineCredentials.untrust(file, project)).isFalse()
    assertThat(GuidelineCredentials.isTrusted(file, project)).isFalse()
  }

  @Test
  fun `a key from the environment is not a stored one`() {
    assertThat(GuidelineCredentials.Resolved("k", GuidelineCredentials.ENV).stored).isFalse()
    assertThat(GuidelineCredentials.Resolved("k", file.path).stored).isTrue()
  }
}
