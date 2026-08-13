package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the precedence list and the file surgery behind the project version pin (issue #3738). The
 * VS Code extension's `versionPin.test.ts` and the composite actions' `test_resolve_version.py`
 * assert the same precedence against their own implementations — if this list changes, all three
 * move together.
 */
class VersionPinTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(): File =
    Files.createTempDirectory("compose-preview-pin-").toFile().also { tempDirs += it }

  private fun projectWith(gradleProperties: String? = null, catalog: String? = null): File {
    val dir = tempDir()
    gradleProperties?.let { File(dir, "gradle.properties").writeText(it) }
    catalog?.let {
      File(dir, "gradle").mkdirs()
      File(dir, "gradle/libs.versions.toml").writeText(it)
    }
    return dir
  }

  // --- sources ------------------------------------------------------------

  @Test
  fun `no pin anywhere resolves to null`() {
    assertNull(resolveVersionPin(projectWith(), env = { null }))
  }

  @Test
  fun `gradle properties supplies the pin`() {
    val root = projectWith(gradleProperties = "composePreview.version=1.2.3\n")
    val pin = resolveVersionPin(root, env = { null })
    assertEquals("1.2.3", pin?.version)
    assertEquals(VersionPinSource.GRADLE_PROPERTIES, pin?.source)
  }

  @Test
  fun `gradle properties tolerates whitespace, colon form and a leading v`() {
    val root = projectWith(gradleProperties = "composePreview.version : v1.2.3  \n")
    assertEquals("1.2.3", resolveVersionPin(root, env = { null })?.version)
  }

  @Test
  fun `a commented-out pin is not a pin`() {
    val root = projectWith(gradleProperties = "# composePreview.version=9.9.9\n")
    assertNull(resolveVersionPin(root, env = { null }))
  }

  @Test
  fun `an empty pin value is treated as absent`() {
    val root = projectWith(gradleProperties = "composePreview.version=\n")
    assertNull(resolveVersionPin(root, env = { null }))
  }

  @Test
  fun `version catalog supplies the pin when gradle properties does not`() {
    val root =
      projectWith(
        catalog =
          """
          [versions]
          agp = "9.1.1"
          composePreviewCli = "1.0.5"

          [plugins]
          android = { id = "com.android.application", version.ref = "agp" }
          """
            .trimIndent()
      )
    val pin = resolveVersionPin(root, env = { null })
    assertEquals("1.0.5", pin?.version)
    assertEquals(VersionPinSource.VERSION_CATALOG, pin?.source)
  }

  @Test
  fun `catalog lookup is scoped to the versions table`() {
    // An identically named key in another table must not be mistaken for the pin.
    val root =
      projectWith(
        catalog =
          """
          [versions]
          agp = "9.1.1"

          [libraries]
          composePreviewCli = "not-a-version"
          """
            .trimIndent()
      )
    assertNull(resolveVersionPin(root, env = { null }))
  }

  @Test
  fun `gradle properties wins over the version catalog`() {
    val root =
      projectWith(
        gradleProperties = "composePreview.version=2.0.0\n",
        catalog = "[versions]\ncomposePreviewCli = \"1.0.5\"\n",
      )
    assertEquals("2.0.0", resolveVersionPin(root, env = { null })?.version)
  }

  @Test
  fun `environment wins over both files`() {
    val root =
      projectWith(
        gradleProperties = "composePreview.version=2.0.0\n",
        catalog = "[versions]\ncomposePreviewCli = \"1.0.5\"\n",
      )
    val pin = resolveVersionPin(root, env = { if (it == VERSION_PIN_ENV) "3.0.0" else null })
    assertEquals("3.0.0", pin?.version)
    assertEquals(VersionPinSource.ENV, pin?.source)
  }

  @Test
  fun `--plugin-version wins over everything`() {
    val root = projectWith(gradleProperties = "composePreview.version=2.0.0\n")
    val pin =
      resolveVersionPin(
        root,
        args = listOf("--plugin-version", "4.0.0"),
        env = { if (it == VERSION_PIN_ENV) "3.0.0" else null },
      )
    assertEquals("4.0.0", pin?.version)
    assertEquals(VersionPinSource.FLAG, pin?.source)
  }

  @Test
  fun `flag and env still resolve with no project root`() {
    val pin = resolveVersionPin(null, args = listOf("--plugin-version=4.0.0"), env = { null })
    assertEquals("4.0.0", pin?.version)
  }

  @Test
  fun `an unreadable catalog degrades to no pin rather than throwing`() {
    val root = projectWith(catalog = "this is not { valid TOML [[[")
    assertNull(resolveVersionPin(root, env = { null }))
  }

  // --- writing ------------------------------------------------------------

  @Test
  fun `writing a pin creates gradle properties with a comment`() {
    val root = tempDir()
    val file = writeGradlePropertiesPin(root, "1.2.3")
    val text = file.readText()
    assertTrue(text.contains("composePreview.version=1.2.3"), text)
    assertTrue(text.contains("# compose-preview version pin"), text)
    assertEquals("1.2.3", resolveVersionPin(root, env = { null })?.version)
  }

  @Test
  fun `writing a pin preserves existing properties and comments`() {
    val root =
      projectWith(
        gradleProperties =
          """
          # my project settings
          org.gradle.jvmargs=-Xmx4g
          android.useAndroidX=true
          """
            .trimIndent() + "\n"
      )
    writeGradlePropertiesPin(root, "1.2.3")
    val text = File(root, "gradle.properties").readText()
    assertTrue(text.contains("# my project settings"), text)
    assertTrue(text.contains("org.gradle.jvmargs=-Xmx4g"), text)
    assertTrue(text.contains("android.useAndroidX=true"), text)
    assertTrue(text.contains("composePreview.version=1.2.3"), text)
  }

  @Test
  fun `re-pinning rewrites the existing line in place`() {
    val root = projectWith(gradleProperties = "a=1\ncomposePreview.version=1.0.0\nb=2\n")
    writeGradlePropertiesPin(root, "2.0.0")
    val lines = File(root, "gradle.properties").readLines()
    assertEquals(
      listOf("a=1", "composePreview.version=2.0.0", "b=2"),
      lines.filter { it.isNotEmpty() },
    )
    // Exactly one pin line — a rewrite must not append a second.
    assertEquals(1, lines.count { it.startsWith("composePreview.version") })
  }

  @Test
  fun `set then remove round-trips back to the original file`() {
    val original = "org.gradle.caching=true\n"
    val root = projectWith(gradleProperties = original)
    writeGradlePropertiesPin(root, "1.2.3")
    assertTrue(removeGradlePropertiesPin(root))
    assertEquals(original, File(root, "gradle.properties").readText())
    assertNull(resolveVersionPin(root, env = { null }))
  }

  @Test
  fun `removing when there is no pin reports false and leaves the file alone`() {
    val root = projectWith(gradleProperties = "a=1\n")
    assertFalse(removeGradlePropertiesPin(root))
    assertEquals("a=1\n", File(root, "gradle.properties").readText())
  }

  // --- CLI skew reporting -------------------------------------------------

  @Test
  fun `skew warning fires when the pin differs from the CLI`() {
    val out = mutableListOf<String>()
    warnOnCliSkew(
      ResolvedVersionPin("1.0.5", VersionPinSource.GRADLE_PROPERTIES),
      cliVersion = "1.1.0",
      stderr = { out += it },
      once = AtomicBoolean(false),
    )
    assertEquals(1, out.size)
    assertTrue(out[0].contains("1.0.5"), out[0])
    assertTrue(out[0].contains("1.1.0"), out[0])
  }

  @Test
  fun `a cross-major pin is reported as a warning, not a note`() {
    val out = mutableListOf<String>()
    warnOnCliSkew(
      ResolvedVersionPin("2.0.0", VersionPinSource.GRADLE_PROPERTIES),
      cliVersion = "1.1.0",
      stderr = { out += it },
      once = AtomicBoolean(false),
    )
    assertTrue(out.single().contains("warning"), out.single())
    assertTrue(out.single().contains("different major versions"), out.single())
  }

  @Test
  fun `no skew warning when the pin matches, is absent, or either side is a snapshot`() {
    val out = mutableListOf<String>()
    val sink: (String) -> Unit = { out += it }
    warnOnCliSkew(null, "1.1.0", sink, AtomicBoolean(false))
    warnOnCliSkew(
      ResolvedVersionPin("1.1.0", VersionPinSource.ENV),
      "1.1.0",
      sink,
      AtomicBoolean(false),
    )
    warnOnCliSkew(
      ResolvedVersionPin("1.0.5", VersionPinSource.ENV),
      "1.1.1-SNAPSHOT",
      sink,
      AtomicBoolean(false),
    )
    warnOnCliSkew(
      ResolvedVersionPin("1.2.0-SNAPSHOT", VersionPinSource.ENV),
      "1.1.0",
      sink,
      AtomicBoolean(false),
    )
    assertTrue(out.isEmpty(), "expected silence, got $out")
  }

  @Test
  fun `the skew warning is emitted once per latch`() {
    val out = mutableListOf<String>()
    val once = AtomicBoolean(false)
    val pin = ResolvedVersionPin("1.0.5", VersionPinSource.GRADLE_PROPERTIES)
    repeat(3) { warnOnCliSkew(pin, "1.1.0", { out += it }, once) }
    assertEquals(1, out.size)
  }

  // --- what the entrypoints actually inject --------------------------------

  @Test
  fun `resolvePluginVersion returns the pin, falling back to the bundled version`() {
    val pinned = projectWith(gradleProperties = "composePreview.version=1.0.5\n")
    assertEquals(
      "1.0.5",
      resolvePluginVersion(pinned, env = { null }, fallback = "1.1.0", stderr = {}),
    )
    assertEquals(
      "1.1.0",
      resolvePluginVersion(projectWith(), env = { null }, fallback = "1.1.0", stderr = {}),
    )
  }

  @Test
  fun `an explicit --plugin-version does not nag about skew`() {
    val out = mutableListOf<String>()
    val version =
      resolvePluginVersion(
        projectWith(),
        args = listOf("--plugin-version", "0.9.0"),
        env = { null },
        fallback = "1.1.0",
        stderr = { out += it },
      )
    assertEquals("0.9.0", version)
    assertTrue(out.isEmpty(), "expected no skew note for an explicit per-run override, got $out")
  }

  @Test
  fun `auto-inject bakes the project pin into the init script`() {
    val root = projectWith(gradleProperties = "composePreview.version=0.9.9\n")
    val storage = tempDir()
    val args =
      autoInjectInitScriptArgs(
        args = emptyList(),
        storageDir = storage,
        env = { null },
        projectRoot = root,
        stderr = {},
      )
    assertEquals("--init-script", args[0])
    assertTrue(File(args[1]).readText().contains("val pluginVersion = \"0.9.9\""))
  }
}
