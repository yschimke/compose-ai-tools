package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import java.io.File
import org.junit.Test

/**
 * `guidelines/packs/general.guidelines.json`, the cross-catalog pack every catalog can include.
 * Every rule quotes official guidance and links it; its platforms keep the text-entry rules out of
 * Remote Compose and give Wear its own input and sign-in rules.
 */
class GeneralGuidelinesPackTest {
  private val file =
    File(System.getProperty("guidelines.packs.dir", "../packs"), "general.guidelines.json")
  private val text = file.readText()
  private val pack: CatalogGuidelinesV1 =
    CatalogGuidelinesLoader.parse(text).also { assertThat(it.problem).isNull() }.guidelines!!

  @Test
  fun `the pack is a flat guidelines file of unique general rules`() {
    assertThat(pack.schema).isEqualTo(CatalogGuidelinesV1.SCHEMA)
    assertThat(GuidelinesIncludes.declared(text)).isEmpty()
    val ids = pack.rules.map { it.id }
    assertThat(ids).containsNoDuplicates()
    ids.forEach { assertThat(it).startsWith("general.") }
    assertThat(text.toByteArray().size).isLessThan(GuidelinesIncludes.MAX_PACK_BYTES)
  }

  @Test
  fun `every rule quotes official guidance, links it and asks a question`() {
    pack.rules.forEach { rule ->
      assertThat(rule.guidance.trim()).isNotEmpty()
      assertThat(rule.source).matches("https://(developer\\.android\\.com|m3\\.material\\.io)/.+")
      assertThat(rule.check).contains("?")
      assertThat(rule.kind)
        .isIn(listOf(GuidelineRuleV1.KIND_STRUCTURE, GuidelineRuleV1.KIND_VISUAL))
      assertThat(rule.severity)
        .isIn(listOf(GuidelineRuleV1.SEVERITY_WARNING, GuidelineRuleV1.SEVERITY_INFO))
      assertThat(rule.scope).isEqualTo(GuidelineRuleV1.SCOPE_SUBJECT)
      assertThat(PLATFORMS).containsAtLeastElementsIn(rule.platforms)
      assertThat(SURFACES).containsAtLeastElementsIn(rule.surfaces)
      assertThat(PROFILES).containsAtLeastElementsIn(rule.profiles)
    }
  }

  @Test
  fun `remote compose and widgets get no text-entry rules`() {
    val remote = merged("remote-compose")
    val textEntry = listOf("general.credentials.password", "general.forms.", "general.wear.input")
    remote.rules.forEach { rule -> textEntry.forEach { assertThat(rule.id).doesNotContain(it) } }
    pack.rules
      .filter { rule -> textEntry.any { rule.id.startsWith(it) } }
      .forEach { assertThat(it.surfaces).doesNotContain(GuidelineRuleV1.SURFACE_WIDGET) }
  }

  @Test
  fun `wear takes its own input and sign-in rules instead of the phone forms`() {
    val wear = merged("wear").rules.map { it.id }
    assertThat(wear)
      .containsAtLeast(
        "general.wear.input.remote-input",
        "general.wear.sign-in.recommended-methods",
      )
    assertThat(wear.filter { it.startsWith("general.forms.") }).isEmpty()
    assertThat(wear.filter { it.startsWith("general.credentials.password") }).isEmpty()

    val mobile = merged("mobile").rules.map { it.id }
    assertThat(mobile).contains("general.forms.visible-label")
    assertThat(mobile.filter { it.startsWith("general.wear.") }).isEmpty()
  }

  private fun merged(platform: String): CatalogGuidelinesV1 =
    GuidelinesIncludes.merge(
      CatalogGuidelinesV1.Builder("c", platform, 1).build(),
      listOf(GuidelinesIncludes.Include("https://example.com/p.json", "0".repeat(64)) to pack),
    )

  private companion object {
    /** The platform words catalog policies declare. */
    val PLATFORMS = listOf("mobile", "foundation", "wear", "glasses", "remote-compose")
    val SURFACES =
      listOf(GuidelineSurfaces.SCREEN, GuidelineSurfaces.WIDGET, GuidelineSurfaces.COMPONENT)
    val PROFILES =
      listOf(
        "launcher-widgets-v6",
        "launcher-widgets-v7",
        "launcher-widgets-v7+experimental",
        "androidx",
      )
  }
}
