package ee.schimke.composeai.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two assumptions `:bom` derives its constraints from, pinned against the real build files.
 *
 * The BOM has to name every coordinate this repository publishes. It gets the main build's from
 * `settings.gradle.kts`, which hands over the project paths and lets the BOM flatten each into an
 * artifact id — and it carries the four in the `gradle-plugin` included build as a hand-written
 * list, because an `includeBuild`'s projects are not `subprojects` and the settings walk cannot see
 * them.
 *
 * Both are the kind of assumption that stops being true silently. A module whose artifact id stops
 * matching its project path, or a new published module in the included build, would simply go
 * missing from the BOM — and a BOM that omits a coordinate is worse than no BOM, because a consumer
 * that trusts it gets no version for that module and a resolution failure with an empty version.
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
    // Why `publishedVersion()` returns the tag outright for an included build instead of looking
    // the module up. This is not a style preference: v2.18.0's release job died during Gradle
    // configuration on `:` -> "" — the included build's ROOT project flattens to the empty string,
    // which is in neither the publish set nor the manifest, so the plugin's own `error(...)` fired
    // while it was being applied. `:gradle-plugin-config` -> "gradle-plugin-config" is the same
    // trap one project along, and would have fired the moment the first was fixed by hand.
    //
    // Pinned as a fact rather than as a rule, so that anyone tempted to "unify" the two paths sees
    // what the unification costs. The included build is safe to stamp with the tag for an
    // independent reason: `maven-publish-plan.sh` marks all four of its ids dirty unconditionally,
    // so they publish at every release.
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
