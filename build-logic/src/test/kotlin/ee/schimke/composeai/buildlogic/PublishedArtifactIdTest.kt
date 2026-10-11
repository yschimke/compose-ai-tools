package ee.schimke.composeai.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two assumptions `:bom` builds its constraints from against the real build files: main
 * build artifact ids are flattened project paths, and the `gradle-plugin` included build's
 * published modules match the BOM's hand-written list. Either drifting would silently drop a
 * coordinate from the BOM.
 */
class PublishedArtifactIdTest {

  private val repoRoot: File = findRepoRoot()

  @Test
  fun `every published main-build module's artifact id is its project path flattened`() {
    val settings = repoRoot.resolve("settings.gradle.kts").readText()
    val includes = Regex("""include\("(:[^"]+)"\)""").findAll(settings).map { it.groupValues[1] }
    val overrides =
      Regex("""project\("(:[^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")
        .findAll(settings)
        .associate { it.groupValues[1] to it.groupValues[2] }

    val mismatches = mutableListOf<String>()
    for (path in includes) {
      val dir = overrides[path] ?: path.removePrefix(":").replace(':', '/')
      val buildFile = repoRoot.resolve(dir).resolve("build.gradle.kts")
      if (!buildFile.isFile) continue
      val text = buildFile.readText()
      // Matched with its closing quote, exactly as `settings.gradle.kts` does: the platform plugin
      // id shares this one's first 26 characters.
      if (!text.contains("""composeai.maven-publishing")""")) continue
      val declared =
        Regex("""artifactId\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: continue
      val derived = path.removePrefix(":").replace(':', '-')
      if (declared != derived) mismatches += "$path declares $declared, BOM would name $derived"
    }

    assertEquals(emptyList(), mismatches, "artifact id no longer follows the project path")
  }

  @Test
  fun `the included build publishes exactly the four coordinates the BOM lists`() {
    // Kept in step with `bom/build.gradle.kts`'s `includedBuildArtifactIds`.
    val expected =
      listOf(
        "compose-preview-config",
        "compose-preview-plugin",
        "daemon-launch-builder",
        "preview-discovery",
      )

    val pluginDir = repoRoot.resolve("gradle-plugin")
    val actual =
      pluginDir
        .walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.name == "build.gradle.kts" }
        .map(File::readText)
        .filter { it.contains("""composeai.maven-publishing")""") }
        .mapNotNull { Regex("""artifactId\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        .sorted()
        .toList()

    assertEquals(
      expected,
      actual,
      "the gradle-plugin included build's published coordinates changed; update " +
        "`includedBuildArtifactIds` in bom/build.gradle.kts to match",
    )
  }

  @Test
  fun `the path convention cannot name the included build's coordinates`() {
    // `publishedVersion()` returns the tag for an included build instead of looking it up:
    // included-build paths flatten to ids in neither the publish set nor the manifest (the root
    // becomes ""). Safe because the publish plan selects all four of its coordinates or none.
    val derivedFromRootPath = ":".removePrefix(":").replace(':', '-')
    assertEquals("", derivedFromRootPath, "an included build's root project has no derivable id")

    val configDir = repoRoot.resolve("gradle-plugin/gradle-plugin-config")
    val declared =
      Regex("""artifactId\s*=\s*"([^"]+)"""")
        .find(configDir.resolve("build.gradle.kts").readText())!!
        .groupValues[1]
    assertEquals("compose-preview-config", declared)
    assertEquals(
      false,
      declared == "gradle-plugin-config",
      "if this ever matches the project path, re-read the comment above before simplifying",
    )
  }

  private fun findRepoRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
      .first { it.resolve("settings.gradle.kts").isFile && it.resolve("gradle-plugin").isDirectory }
}
