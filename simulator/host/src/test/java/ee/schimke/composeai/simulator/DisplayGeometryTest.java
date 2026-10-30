package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DisplayGeometryTest {

  @Test
  public void phoneIsNormalLongPortrait() {
    assertEquals(
        "sw411dp-w411dp-h891dp-normal-long-port", DisplayGeometry.sizeQualifiers(411, 891));
  }

  @Test
  public void openFoldableIsLargeNotLong() {
    assertEquals(
        "sw673dp-w673dp-h841dp-large-notlong-port", DisplayGeometry.sizeQualifiers(673, 841));
    assertEquals(
        "sw673dp-w841dp-h673dp-large-notlong-land", DisplayGeometry.sizeQualifiers(841, 673));
  }

  @Test
  public void tabletAndDesktopAreXlarge() {
    assertEquals(
        "sw800dp-w1280dp-h800dp-xlarge-notlong-land", DisplayGeometry.sizeQualifiers(1280, 800));
    assertEquals(
        "sw1080dp-w1920dp-h1080dp-xlarge-long-land",
        DisplayGeometry.sizeQualifiers(1920, 1080));
  }

  @Test
  public void smallWatchSizedDisplay() {
    assertEquals(
        "sw240dp-w240dp-h320dp-small-notlong-port", DisplayGeometry.sizeQualifiers(240, 320));
  }

  @Test
  public void verticalFoldIsAZeroWidthLineDownTheMiddle() {
    assertArrayEquals(
        new int[] {504, 0, 504, 1261}, DisplayGeometry.foldBounds(0, 0, 1009, 1261, 0, true));
  }

  @Test
  public void horizontalHingeIsABandAcrossTheMiddle() {
    assertArrayEquals(
        new int[] {0, 474, 1261, 534}, DisplayGeometry.foldBounds(0, 0, 1261, 1009, 60, false));
  }

  /** LaunchCommand.DEVICES in :cli lists the same names for --device; keep them in step. */
  @Test
  public void presetNamesMatchTheCli() {
    StringBuilder names = new StringBuilder();
    for (DisplayGeometry.Preset p : DisplayGeometry.PRESETS) names.append(p.name).append(' ');
    assertEquals("phone foldable foldable-folded tablet desktop ", names.toString());
  }

  @Test
  public void launchQualifiersKeepAPresetsOwnDensity() {
    assertEquals(
        "sw673dp-w673dp-h841dp-large-notlong-port-hdpi",
        DisplayGeometry.launchQualifiers(DisplayGeometry.preset("Foldable"), "hdpi"));
    assertEquals(
        "sw1080dp-w1920dp-h1080dp-xlarge-long-land-mdpi",
        DisplayGeometry.launchQualifiers(DisplayGeometry.preset("desktop"), "hdpi"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void anUnknownPresetIsAnError() {
    DisplayGeometry.preset("watch");
  }

  @Test
  public void densityQualifiersUseTheNamedBuckets() {
    assertEquals("hdpi", DisplayGeometry.densityQualifier(240));
    assertEquals("420dpi", DisplayGeometry.densityQualifier(420));
  }

  @Test
  public void screenLayoutFollowsTheQualifierBuckets() {
    // SCREENLAYOUT_SIZE_NORMAL | SCREENLAYOUT_LONG_YES
    assertEquals(0x22, DisplayGeometry.screenLayout(411, 891));
    // SCREENLAYOUT_SIZE_LARGE | SCREENLAYOUT_LONG_NO
    assertEquals(0x13, DisplayGeometry.screenLayout(673, 841));
    // SCREENLAYOUT_SIZE_XLARGE | SCREENLAYOUT_LONG_YES: a 1080p external display at mdpi
    assertEquals(0x24, DisplayGeometry.screenLayout(1920, 1080));
  }

  @Test
  public void aSplitScreenFollowsTheFoldElseTheLongerSide() {
    // An open book-style foldable, portrait, folds top to bottom: side by side, the odd dp second.
    assertArrayEquals(
        new int[] {336, 841, 337, 841}, DisplayGeometry.splitHalves(673, 841, true));
    // The same display with no fold: one above the other.
    assertArrayEquals(
        new int[] {673, 420, 673, 421}, DisplayGeometry.splitHalves(673, 841, null));
    // A tabletop fold runs side to side: one above the other.
    assertArrayEquals(
        new int[] {841, 336, 841, 337}, DisplayGeometry.splitHalves(841, 673, false));
    // A landscape tablet: side by side.
    assertArrayEquals(
        new int[] {640, 800, 640, 800}, DisplayGeometry.splitHalves(1280, 800, null));
  }
}
