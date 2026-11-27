package com.google.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class SdkWearApiVersionTest {

  @After
  public void clearProperties() {
    System.clearProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY);
    System.clearProperty(Sdk.WEAR_API_VERSION_PROPERTY);
  }

  @Test
  public void api37DefaultsToWearOs71StableAndUpgradesWithGlobalStatusBarFlag() {
    assertEquals(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_1, Sdk.currentApiVersion(37));
    assertTrue(Sdk.currentApiVersion(37).compareTo(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2) < 0);

    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    assertEquals(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2, Sdk.currentApiVersion(37));
    assertTrue(Sdk.currentApiVersion(37).compareTo(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2) >= 0);
  }

  @Test
  public void olderSdksNeverReachCinnamonBun2EvenWhenFlagIsSet() {
    System.setProperty(Sdk.GLOBAL_STATUS_BAR_PROPERTY, "true");
    assertEquals(Sdk.VERSION_CODES.WEAR_BAKLAVA_1, Sdk.currentApiVersion(36));
    assertEquals(Sdk.VERSION_CODES.WEAR_VIC_1, Sdk.currentApiVersion(35));
    assertEquals(Sdk.VERSION_CODES.WEAR_UDC_1, Sdk.currentApiVersion(34));
    assertEquals(Sdk.VERSION_CODES.WEAR_TIRAMISU_4, Sdk.currentApiVersion(33));
    assertFalse(Sdk.currentApiVersion(36).compareTo(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2) >= 0);
  }
}
