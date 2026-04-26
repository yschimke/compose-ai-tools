package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResourceQualifierParserTest {

  @Test
  fun `default-qualifier directory has null suffix`() {
    val parsed = ResourceQualifierParser.parse("drawable")
    assertThat(parsed.base).isEqualTo("drawable")
    assertThat(parsed.qualifierSuffix).isNull()
    assertThat(parsed.qualifierTokens).isEmpty()
  }

  @Test
  fun `single qualifier directory parses base and suffix`() {
    val parsed = ResourceQualifierParser.parse("drawable-night")
    assertThat(parsed.base).isEqualTo("drawable")
    assertThat(parsed.qualifierSuffix).isEqualTo("night")
    assertThat(parsed.qualifierTokens).containsExactly("night").inOrder()
  }

  @Test
  fun `multi-qualifier directory keeps source order`() {
    val parsed = ResourceQualifierParser.parse("drawable-night-xhdpi-v26")
    assertThat(parsed.base).isEqualTo("drawable")
    assertThat(parsed.qualifierSuffix).isEqualTo("night-xhdpi-v26")
    assertThat(parsed.qualifierTokens).containsExactly("night", "xhdpi", "v26").inOrder()
  }

  @Test
  fun `mipmap-anydpi-v26 round-trips`() {
    val parsed = ResourceQualifierParser.parse("mipmap-anydpi-v26")
    assertThat(parsed.base).isEqualTo("mipmap")
    assertThat(parsed.qualifierSuffix).isEqualTo("anydpi-v26")
    assertThat(parsed.qualifierTokens).containsExactly("anydpi", "v26").inOrder()
  }

  @Test
  fun `locale region pair preserved as separate tokens`() {
    val parsed = ResourceQualifierParser.parse("values-en-rGB-xhdpi")
    assertThat(parsed.qualifierTokens).containsExactly("en", "rGB", "xhdpi").inOrder()
  }

  @Test
  fun `density classification recognises every standard bucket`() {
    listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "tvdpi", "anydpi", "nodpi")
      .forEach { token -> assertThat(ResourceQualifierParser.isDensityQualifier(token)).isTrue() }
  }

  @Test
  fun `density classification rejects unrelated tokens`() {
    listOf("night", "ldrtl", "v26", "round", "port", "en").forEach { token ->
      assertThat(ResourceQualifierParser.isDensityQualifier(token)).isFalse()
    }
  }

  @Test
  fun `night classification accepts both flavours`() {
    assertThat(ResourceQualifierParser.isNightQualifier("night")).isTrue()
    assertThat(ResourceQualifierParser.isNightQualifier("notnight")).isTrue()
    assertThat(ResourceQualifierParser.isNightQualifier("xhdpi")).isFalse()
  }

  @Test
  fun `version qualifier recognises v + digits only`() {
    assertThat(ResourceQualifierParser.isVersionQualifier("v26")).isTrue()
    assertThat(ResourceQualifierParser.isVersionQualifier("v34")).isTrue()
    assertThat(ResourceQualifierParser.isVersionQualifier("v")).isFalse()
    assertThat(ResourceQualifierParser.isVersionQualifier("vXY")).isFalse()
    assertThat(ResourceQualifierParser.isVersionQualifier("xhdpi")).isFalse()
  }

  @Test
  fun `layout direction recognises both orientations`() {
    assertThat(ResourceQualifierParser.isLayoutDirectionQualifier("ldrtl")).isTrue()
    assertThat(ResourceQualifierParser.isLayoutDirectionQualifier("ldltr")).isTrue()
    assertThat(ResourceQualifierParser.isLayoutDirectionQualifier("ldbottom")).isFalse()
  }

  @Test
  fun `ui mode tokens are recognised`() {
    listOf("car", "desk", "television", "appliance", "watch", "vrheadset").forEach { token ->
      assertThat(ResourceQualifierParser.isUiModeQualifier(token)).isTrue()
    }
    assertThat(ResourceQualifierParser.isUiModeQualifier("phone")).isFalse()
  }

  @Test
  fun `locale language qualifier accepts 2 and 3 letter codes`() {
    assertThat(ResourceQualifierParser.isLocaleLanguageQualifier("en")).isTrue()
    assertThat(ResourceQualifierParser.isLocaleLanguageQualifier("de")).isTrue()
    assertThat(ResourceQualifierParser.isLocaleLanguageQualifier("eng")).isTrue()
    assertThat(ResourceQualifierParser.isLocaleLanguageQualifier("b+sr+Latn")).isTrue()
    assertThat(ResourceQualifierParser.isLocaleLanguageQualifier("EN")).isFalse()
    assertThat(ResourceQualifierParser.isLocaleLanguageQualifier("xhdpi")).isFalse()
  }

  @Test
  fun `locale region qualifier requires r prefix and uppercase letters`() {
    assertThat(ResourceQualifierParser.isLocaleRegionQualifier("rGB")).isTrue()
    assertThat(ResourceQualifierParser.isLocaleRegionQualifier("rUS")).isTrue()
    assertThat(ResourceQualifierParser.isLocaleRegionQualifier("rgb")).isFalse()
    assertThat(ResourceQualifierParser.isLocaleRegionQualifier("r12")).isFalse()
    assertThat(ResourceQualifierParser.isLocaleRegionQualifier("xhdpi")).isFalse()
  }

  @Test
  fun `screen size qualifier matches sw_w_h dp forms`() {
    assertThat(ResourceQualifierParser.isScreenSizeQualifier("sw320dp")).isTrue()
    assertThat(ResourceQualifierParser.isScreenSizeQualifier("w480dp")).isTrue()
    assertThat(ResourceQualifierParser.isScreenSizeQualifier("h720dp")).isTrue()
    assertThat(ResourceQualifierParser.isScreenSizeQualifier("swdp")).isFalse()
    assertThat(ResourceQualifierParser.isScreenSizeQualifier("xhdpi")).isFalse()
  }
}
