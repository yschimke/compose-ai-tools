package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MaterialIconRef] parsing, and — the test that actually earns the feature — the **exhaustive**
 * check that the identifier rule maps every icon `material-icons-extended` ships onto a real
 * fonts.google.com name.
 *
 * Two vendored lists drive it, so the guarantee is offline and CI-checked rather than a claim:
 * - `androidx-material-icon-identifiers.txt` — every `Icons.Filled.*` identifier in
 *   `androidx.compose.material:material-icons-extended` (the identifier set is the same for all
 *   five styles), read out of the artifact's class names.
 * - `material-icon-names.txt` — the canonical icon names, from `MaterialIcons-Regular.codepoints`
 *   in `google/material-design-icons`.
 *
 * Refresh both when bumping the icons artifact; a new icon whose identifier the rule can't map
 * fails here rather than silently shipping a wrong `data-material-icon-url`.
 */
class MaterialIconRefTest {

  private fun resource(name: String): List<String> =
    checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource $name" }
      .bufferedReader()
      .readLines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  @Test
  fun parsesStyleAndName() {
    val ref = checkNotNull(MaterialIconRef.parse("Filled.AccountCircle"))
    assertEquals("account_circle", ref.name)
    assertEquals(MaterialIconRef.Style.FILLED, ref.style)
    assertEquals(false, ref.autoMirrored)
    assertEquals("https://fonts.gstatic.com/s/i/materialicons/account_circle/v1/24px.svg", ref.url)
  }

  @Test
  fun everyStyleMapsToItsOwnCdnFamily() {
    assertEquals(
      "materialicons",
      checkNotNull(MaterialIconRef.parse("Filled.Menu")).style.cdnFamily,
    )
    assertEquals(
      "materialiconsoutlined",
      checkNotNull(MaterialIconRef.parse("Outlined.Menu")).style.cdnFamily,
    )
    assertEquals(
      "materialiconsround",
      checkNotNull(MaterialIconRef.parse("Rounded.Menu")).style.cdnFamily,
    )
    assertEquals(
      "materialiconssharp",
      checkNotNull(MaterialIconRef.parse("Sharp.Menu")).style.cdnFamily,
    )
    assertEquals(
      "materialiconstwotone",
      checkNotNull(MaterialIconRef.parse("TwoTone.Menu")).style.cdnFamily,
    )
  }

  @Test
  fun autoMirroredCarriesTheFlagAndTheUnmirroredName() {
    val ref = checkNotNull(MaterialIconRef.parse("AutoMirrored.Filled.ArrowBack"))
    assertEquals("arrow_back", ref.name)
    assertEquals(MaterialIconRef.Style.FILLED, ref.style)
    assertTrue(ref.autoMirrored)
  }

  @Test
  fun nonMaterialVectorsDoNotParse() {
    // An app's own ImageVector, Compose's defaults, and anything not generator-shaped: no
    // annotation rather than a guess.
    assertNull(MaterialIconRef.parse(null))
    assertNull(MaterialIconRef.parse(""))
    assertNull(MaterialIconRef.parse("   "))
    assertNull(MaterialIconRef.parse("VectorRootGroup"))
    assertNull(MaterialIconRef.parse("BrandLogo"))
    assertNull(MaterialIconRef.parse("Custom.Logo"))
    assertNull(MaterialIconRef.parse("Filled."))
    assertNull(MaterialIconRef.parse("Some.Deeply.Nested.Name"))
  }

  @Test
  fun digitLeadingAndDigitBoundaryIdentifiers() {
    // The generator prefixes a digit-leading icon with `_`, and those keep their digits welded to
    // the following letters — while an ordinary name breaks at every digit boundary.
    assertEquals("3d_rotation", checkNotNull(MaterialIconRef.parse("Filled._3dRotation")).name)
    assertEquals("10k", checkNotNull(MaterialIconRef.parse("Filled._10k")).name)
    assertEquals("30fps_select", checkNotNull(MaterialIconRef.parse("Filled._30fpsSelect")).name)
    assertEquals("1k_plus", checkNotNull(MaterialIconRef.parse("Filled._1kPlus")).name)
    assertEquals(
      "rotate_90_degrees_ccw",
      checkNotNull(MaterialIconRef.parse("Filled.Rotate90DegreesCcw")).name,
    )
  }

  @Test
  fun acronymRunsBreakOnTheLastCapital() {
    assertEquals("sd_card", checkNotNull(MaterialIconRef.parse("Filled.SdCard")).name)
    assertEquals("g_mobiledata", checkNotNull(MaterialIconRef.parse("Filled.GMobiledata")).name)
    assertEquals("ac_unit", checkNotNull(MaterialIconRef.parse("Filled.AcUnit")).name)
    assertEquals("wifi", checkNotNull(MaterialIconRef.parse("Filled.Wifi")).name)
  }

  @Test
  fun exceptionsCoverWhatTheRuleCannotDerive() {
    assertEquals("crop_16_9", checkNotNull(MaterialIconRef.parse("Filled.Crop169")).name)
    assertEquals("grid_3x3", checkNotNull(MaterialIconRef.parse("Filled.Grid3x3")).name)
    assertEquals("co2", checkNotNull(MaterialIconRef.parse("Filled.Co2")).name)
    assertEquals("star_purple500", checkNotNull(MaterialIconRef.parse("Filled.StarPurple500")).name)
  }

  @Test
  fun everyExtendedIconMapsToACanonicalName() {
    val canonical = resource("material-icon-names.txt").toSet()
    // Brand icons live on the CDN but were dropped from the font, so they aren't in the codepoints
    // list. Verified reachable by hand (`materialicons/whatsapp/v1/24px.svg` → 200); allowed here
    // rather than weakening the check for everything else.
    val notInFont = setOf("whatsapp")
    val unmapped =
      resource("androidx-material-icon-identifiers.txt")
        .map {
          it to checkNotNull(MaterialIconRef.parse("Filled.$it")) { "did not parse: $it" }.name
        }
        .filter { (_, name) -> name !in canonical && name !in notInFont }
    assertEquals(
      "identifiers with no canonical Material icon name",
      emptyList<Pair<String, String>>(),
      unmapped,
    )
  }
}
