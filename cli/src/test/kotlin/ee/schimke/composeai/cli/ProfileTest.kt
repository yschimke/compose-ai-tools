package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Pure unit coverage for [Profile] JSON parsing + [ProfileCommand]'s `synthesiseArgs` translation.
 * The full Gradle drive path (modules → render → report) is exercised end-to-end by
 * `compose-preview a11y` in `AccessibilityFunctionalTest`; here we just lock the wire shape and the
 * argv translation so a profile is provably equivalent to "the flags a user would have typed."
 */
class ProfileTest {

  private val json = Json { ignoreUnknownKeys = true }

  // ---------- parsing ----------

  @Test
  fun `minimal profile uses defaults for filter and failOn`() {
    val profile =
      json.decodeFromString(
        Profile.serializer(),
        """
        {"schema": "compose-preview-profile/v1", "extensions": ["a11y"]}
        """
          .trimIndent(),
      )

    assertEquals(PROFILE_SCHEMA_V1, profile.schema)
    assertEquals(listOf("a11y"), profile.extensions)
    assertEquals(ProfileFilter(), profile.filter)
    assertEquals(emptyMap(), profile.failOn)
    assertEquals(null, profile.report)
  }

  @Test
  fun `profile parses filter and failOn`() {
    val profile =
      json.decodeFromString(
        Profile.serializer(),
        """
        {
          "extensions": ["a11y"],
          "filter": {
            "module": ":samples:android",
            "idSubstring": "Home",
            "changedOnly": true
          },
          "failOn": {"a11y": "errors"},
          "report": "a11y"
        }
        """
          .trimIndent(),
      )

    assertEquals(":samples:android", profile.filter.module)
    assertEquals("Home", profile.filter.idSubstring)
    assertTrue(profile.filter.changedOnly)
    assertEquals(mapOf("a11y" to "errors"), profile.failOn)
    assertEquals("a11y", profile.report)
  }

  @Test
  fun `schema defaults to v1 when omitted`() {
    val profile = json.decodeFromString(Profile.serializer(), """{"extensions": ["a11y"]}""")
    assertEquals(PROFILE_SCHEMA_V1, profile.schema)
  }

  // ---------- resolveReportExtension ----------

  @Test
  fun `resolveReportExtension prefers explicit report field`() {
    val profile = Profile(extensions = listOf("a11y"), report = "a11y")
    val cmd = ProfileCommand(emptyList())
    assertEquals("a11y", cmd.resolveReportExtension(profile))
  }

  @Test
  fun `resolveReportExtension falls back to first extension`() {
    val profile = Profile(extensions = listOf("a11y"))
    val cmd = ProfileCommand(emptyList())
    assertEquals("a11y", cmd.resolveReportExtension(profile))
  }

  // ---------- synthesiseArgs ----------

  @Test
  fun `synthesiseArgs omits the report extension from --with-extension`() {
    // The report extension is added implicitly by ReportCommand.implicitExtensions(); emitting it
    // here too would still work (extensionGradleArgs deduplicates) but bloats the synthesised
    // argv that diagnostics print. Keep the argv clean.
    val profile = Profile(extensions = listOf("a11y", "theme"), report = "a11y")
    val cmd = ProfileCommand(emptyList())
    val argv = cmd.synthesiseArgs(profile, reportExtensionId = "a11y", extraArgs = emptyList())
    assertEquals(listOf("--with-extension", "theme"), argv)
  }

  @Test
  fun `synthesiseArgs translates filter axes into the existing global flags`() {
    val profile =
      Profile(
        extensions = listOf("a11y"),
        filter =
          ProfileFilter(
            module = ":samples:android",
            idSubstring = "Home",
            id = "HomePreview",
            changedOnly = true,
          ),
        failOn = mapOf("a11y" to "errors"),
      )
    val cmd = ProfileCommand(emptyList())
    val argv = cmd.synthesiseArgs(profile, reportExtensionId = "a11y", extraArgs = emptyList())

    assertEquals(
      listOf(
        "--module",
        ":samples:android",
        "--filter",
        "Home",
        "--id",
        "HomePreview",
        "--changed-only",
        "--fail-on",
        "errors",
      ),
      argv,
    )
  }

  @Test
  fun `synthesiseArgs only emits --fail-on for the chosen report extension`() {
    // Profile may declare per-extension thresholds, but today's CLI only honours one (the chosen
    // renderer's). Other entries are dropped on the floor — multi-extension fail-on is on the
    // v2 roadmap, tracked alongside the scripting follow-up.
    val profile =
      Profile(
        extensions = listOf("a11y", "theme"),
        failOn = mapOf("theme" to "warnings", "a11y" to "errors"),
        report = "a11y",
      )
    val cmd = ProfileCommand(emptyList())
    val argv = cmd.synthesiseArgs(profile, reportExtensionId = "a11y", extraArgs = emptyList())
    val failOnIndex = argv.indexOf("--fail-on")
    assertTrue(failOnIndex >= 0, "expected --fail-on for the chosen report extension")
    assertEquals("errors", argv[failOnIndex + 1])
    // The other extension's threshold isn't forwarded — we explicitly don't double-fail-on.
    assertEquals(1, argv.count { it == "--fail-on" })
  }

  @Test
  fun `synthesiseArgs preserves trailing user flags after profile fields`() {
    // Last-write-wins is the CLI's general rule; profiles preserve it so `compose-preview profile
    // foo.json --json` overrides the profile's silence and `compose-preview profile foo.json
    // --module :other` re-targets the run without editing the file.
    val profile =
      Profile(extensions = listOf("a11y"), filter = ProfileFilter(module = ":samples:android"))
    val cmd = ProfileCommand(emptyList())
    val argv =
      cmd.synthesiseArgs(
        profile,
        reportExtensionId = "a11y",
        extraArgs = listOf("--json", "--module", ":other"),
      )

    assertEquals(listOf("--module", ":samples:android", "--json", "--module", ":other"), argv)
  }

  @Test
  fun `synthesiseArgs emits no extension flags when only the report extension is listed`() {
    val profile = Profile(extensions = listOf("a11y"))
    val cmd = ProfileCommand(emptyList())
    val argv = cmd.synthesiseArgs(profile, reportExtensionId = "a11y", extraArgs = emptyList())
    assertEquals(emptyList(), argv)
  }
}
