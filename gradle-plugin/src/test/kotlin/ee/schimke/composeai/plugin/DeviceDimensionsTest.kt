package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceDimensionsTest {

  @Test
  fun `explicit dimensions override device spec`() {
    val spec = DeviceDimensions.resolve("id:pixel_6", widthDp = 200, heightDp = 300)
    assertThat(spec.widthDp).isEqualTo(200)
    assertThat(spec.heightDp).isEqualTo(300)
  }

  @Test
  fun `known device ID resolves correctly`() {
    // Pixel 6 = 1080x2400 px @ 420dpi → 411x914 dp
    // (sergio-sastre/ComposablePreviewScanner Phone.PIXEL_6, exposed via the
    // takahirom/roborazzi compose-preview-scanner-support pipeline.)
    val spec = DeviceDimensions.resolve("id:pixel_6")
    assertThat(spec.widthDp).isEqualTo(411)
    assertThat(spec.heightDp).isEqualTo(914)
  }

  @Test
  fun `pixel 6 pro has different dimensions from pixel 6`() {
    // Regression guard against the historical copy-paste that gave pixel_6 the
    // pixel_6_pro values. Pro is 1440x3120 @ 560dpi → 411x891 dp.
    val pro = DeviceDimensions.resolve("id:pixel_6_pro")
    assertThat(pro.widthDp).isEqualTo(411)
    assertThat(pro.heightDp).isEqualTo(891)
    assertThat(pro).isNotEqualTo(DeviceDimensions.resolve("id:pixel_6"))
  }

  @Test
  fun `pixel 9 has updated geometry from pixel 6 family`() {
    // Pixel 9/9a = 1080x2424 px @ 420dpi → 411x923 dp (one extra row vs Pixel 6/7/8).
    val spec = DeviceDimensions.resolve("id:pixel_9")
    assertThat(spec.widthDp).isEqualTo(411)
    assertThat(spec.heightDp).isEqualTo(923)
  }

  @Test
  fun `generic device IDs resolve`() {
    // The "Medium Phone" / "Small Phone" / "Medium Tablet" entries in the @Preview
    // device picker (Identifier.MEDIUM_PHONE etc. in ComposablePreviewScanner).
    assertThat(DeviceDimensions.resolve("id:medium_phone").widthDp).isEqualTo(411)
    assertThat(DeviceDimensions.resolve("id:medium_phone").heightDp).isEqualTo(914)
    assertThat(DeviceDimensions.resolve("id:small_phone").widthDp).isEqualTo(360)
    assertThat(DeviceDimensions.resolve("id:small_phone").heightDp).isEqualTo(640)
    assertThat(DeviceDimensions.resolve("id:medium_tablet").widthDp).isEqualTo(1280)
    assertThat(DeviceDimensions.resolve("id:medium_tablet").heightDp).isEqualTo(800)
  }

  @Test
  fun `pixel fold is landscape-natural`() {
    val spec = DeviceDimensions.resolve("id:pixel_fold")
    assertThat(spec.widthDp).isGreaterThan(spec.heightDp)
  }

  @Test
  fun `spec string is parsed`() {
    val spec = DeviceDimensions.resolve("spec:width=320dp,height=480dp")
    assertThat(spec.widthDp).isEqualTo(320)
    assertThat(spec.heightDp).isEqualTo(480)
  }

  @Test
  fun `wear device returns wear defaults`() {
    val spec = DeviceDimensions.resolve("id:wearos_large_round")
    assertThat(spec.widthDp).isEqualTo(227)
    assertThat(spec.heightDp).isEqualTo(227)
  }

  @Test
  fun `desktop tier IDs resolve`() {
    // Identifier.SMALL_DESKTOP / MEDIUM_DESKTOP / LARGE_DESKTOP in
    // ComposablePreviewScanner.
    assertThat(DeviceDimensions.resolve("id:desktop_small").widthDp).isEqualTo(1366)
    assertThat(DeviceDimensions.resolve("id:desktop_medium").widthDp).isEqualTo(1920)
    assertThat(DeviceDimensions.resolve("id:desktop_large").widthDp).isEqualTo(1920)
  }

  @Test
  fun `television tier IDs resolve`() {
    // 4K renders at the same dp as 1080p (4×4K density vs 2×1080p density),
    // matching Identifier.TV_4K / TV_1080p in ComposablePreviewScanner.
    assertThat(DeviceDimensions.resolve("id:tv_720p").widthDp).isEqualTo(931)
    assertThat(DeviceDimensions.resolve("id:tv_1080p").widthDp).isEqualTo(960)
    assertThat(DeviceDimensions.resolve("id:tv_4k").widthDp).isEqualTo(960)
  }

  @Test
  fun `automotive landscape IDs resolve and are wider than tall`() {
    val landscape = DeviceDimensions.resolve("id:automotive_1408p_landscape_with_google_apis")
    assertThat(landscape.widthDp).isEqualTo(1408)
    assertThat(landscape.heightDp).isEqualTo(792)
    assertThat(landscape.widthDp).isGreaterThan(landscape.heightDp)
  }

  @Test
  fun `automotive portrait orientation is preserved`() {
    // Portrait variants invert the usual landscape geometry.
    val portrait = DeviceDimensions.resolve("id:automotive_portrait")
    assertThat(portrait.heightDp).isGreaterThan(portrait.widthDp)
  }

  @Test
  fun `xr_device alias matches xr_headset_device`() {
    // xr_device is the deprecated identifier replaced by xr_headset_device in
    // newer Android Studio versions; both refer to the same device.
    assertThat(DeviceDimensions.resolve("id:xr_device"))
      .isEqualTo(DeviceDimensions.resolve("id:xr_headset_device"))
  }

  @Test
  fun `wearos_rectangular alias matches wearos_rect`() {
    // wearos_rectangular is the deprecated identifier replaced by wearos_rect in
    // newer Android Studio versions; both refer to the same device.
    assertThat(DeviceDimensions.resolve("id:wearos_rectangular"))
      .isEqualTo(DeviceDimensions.resolve("id:wearos_rect"))
  }

  @Test
  fun `unknown wear device returns wear defaults`() {
    val spec = DeviceDimensions.resolve("id:some_wear_device")
    assertThat(spec.widthDp).isEqualTo(227)
    assertThat(spec.heightDp).isEqualTo(227)
  }

  @Test
  fun `null device returns phone defaults`() {
    val spec = DeviceDimensions.resolve(null)
    assertThat(spec.widthDp).isEqualTo(400)
    assertThat(spec.heightDp).isEqualTo(800)
  }

  // -- Density coverage --
  //
  // density = densityDpi / 160. We carry this through PreviewParams so renderers
  // can size bitmaps the same way Android Studio's `@Preview` does (Studio's
  // default phone-class preview is 420dpi → 2.625x, not Robolectric's mdpi 1.0x
  // / xhdpi 2.0x default).

  @Test
  fun `pixel 6 density matches its 420dpi panel`() {
    // 1080x2400 px @ 420dpi → density 2.625 (xxhdpi-ish).
    assertThat(DeviceDimensions.resolve("id:pixel_6").density).isEqualTo(2.625f)
  }

  @Test
  fun `pixel 6 pro is denser than pixel 6`() {
    // 560dpi vs 420dpi.
    assertThat(DeviceDimensions.resolve("id:pixel_6_pro").density).isEqualTo(3.5f)
    assertThat(DeviceDimensions.resolve("id:pixel_6_pro").density)
      .isGreaterThan(DeviceDimensions.resolve("id:pixel_6").density)
  }

  @Test
  fun `tv 4k renders at 4x density`() {
    // Same dp surface as 1080p but rendered at 640dpi vs 320dpi — the dp/density
    // pair is what makes "4K" 4K.
    assertThat(DeviceDimensions.resolve("id:tv_4k").density).isEqualTo(4.0f)
    assertThat(DeviceDimensions.resolve("id:tv_1080p").density).isEqualTo(2.0f)
  }

  @Test
  fun `automotive sub-mdpi densities are preserved`() {
    // Several automotive panels run below mdpi — 120dpi is 0.75x density. Don't
    // round these up to 1.0.
    assertThat(DeviceDimensions.resolve("id:automotive_portrait").density).isEqualTo(0.75f)
  }

  @Test
  fun `unknown device falls back to AS default density`() {
    // 2.625x matches Android Studio's default phone preview rather than the
    // historical 2.0x (Robolectric xhdpi).
    assertThat(DeviceDimensions.resolve(null).density).isEqualTo(DeviceDimensions.DEFAULT_DENSITY)
    assertThat(DeviceDimensions.resolve("id:something_unknown").density)
      .isEqualTo(DeviceDimensions.DEFAULT_DENSITY)
  }

  @Test
  fun `spec string honors dpi parameter`() {
    // Studio's spec: grammar accepts a `dpi=` clause; we honour it so consumers
    // can pin a specific density without using a known device id.
    val spec = DeviceDimensions.resolve("spec:width=411dp,height=914dp,dpi=560")
    assertThat(spec.widthDp).isEqualTo(411)
    assertThat(spec.heightDp).isEqualTo(914)
    assertThat(spec.density).isEqualTo(3.5f)
  }

  @Test
  fun `spec string without dpi falls back to AS default`() {
    val spec = DeviceDimensions.resolve("spec:width=300dp,height=500dp")
    assertThat(spec.density).isEqualTo(DeviceDimensions.DEFAULT_DENSITY)
  }

  @Test
  fun `wear devices share xhdpi density`() {
    // All wear devices upstream are 320dpi → 2.0x.
    assertThat(DeviceDimensions.resolve("id:wearos_large_round").density).isEqualTo(2.0f)
    assertThat(DeviceDimensions.resolve("id:wearos_square").density).isEqualTo(2.0f)
    assertThat(DeviceDimensions.resolve("id:wearos_xl_round").density).isEqualTo(2.0f)
  }

  @Test
  fun `wearos_xl_round resolves`() {
    // 480x480 px @ xhdpi → 240x240 dp. Source: AOSP sdklib/devices/wear.xml.
    // Larger than wearos_large_round (227dp) — sits at the top of the
    // 192–240 dp wear range Material 2.5 calls out.
    val spec = DeviceDimensions.resolve("id:wearos_xl_round")
    assertThat(spec.widthDp).isEqualTo(240)
    assertThat(spec.heightDp).isEqualTo(240)
    assertThat(spec.widthDp)
      .isGreaterThan(DeviceDimensions.resolve("id:wearos_large_round").widthDp)
  }

  // -- resolveForRender (AS-parity sizing) --

  @Test
  fun `resolveForRender wraps both axes when no hints are given`() {
    val spec =
      DeviceDimensions.resolveForRender(
        device = null,
        widthDp = null,
        heightDp = null,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isTrue()
    assertThat(spec.wrapHeight).isTrue()
    // Wrap-axes get the phone-shaped 400×800 dp sandbox so fillMaxSize
    // composables render into a reasonable viewport before the PNG crop.
    assertThat(spec.widthDp).isEqualTo(DeviceDimensions.SANDBOX_WIDTH_DP)
    assertThat(spec.heightDp).isEqualTo(DeviceDimensions.SANDBOX_HEIGHT_DP)
  }

  @Test
  fun `resolveForRender keeps device frame when device is set`() {
    val spec =
      DeviceDimensions.resolveForRender(
        device = "id:pixel_6",
        widthDp = null,
        heightDp = null,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    // Pixel 6 = 411×914 dp after the upstream per-device density refresh.
    assertThat(spec.widthDp).isEqualTo(411)
    assertThat(spec.heightDp).isEqualTo(914)
  }

  @Test
  fun `resolveForRender keeps full frame when showSystemUi is true`() {
    val spec =
      DeviceDimensions.resolveForRender(
        device = null,
        widthDp = null,
        heightDp = null,
        showSystemUi = true,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    assertThat(spec.widthDp).isEqualTo(400)
    assertThat(spec.heightDp).isEqualTo(800)
  }

  @Test
  fun `resolveForRender wraps only the axis the user left unset`() {
    val spec =
      DeviceDimensions.resolveForRender(
        device = null,
        widthDp = 240,
        heightDp = null,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isTrue()
    assertThat(spec.widthDp).isEqualTo(240)
    assertThat(spec.heightDp).isEqualTo(DeviceDimensions.SANDBOX_HEIGHT_DP)
  }

  @Test
  fun `resolveForRender explicit dims override device frame`() {
    val spec =
      DeviceDimensions.resolveForRender(
        device = "id:pixel_6",
        widthDp = 200,
        heightDp = 300,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    assertThat(spec.widthDp).isEqualTo(200)
    assertThat(spec.heightDp).isEqualTo(300)
  }
}
