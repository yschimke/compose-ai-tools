package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.wear.Sdk;
import com.google.wear.WearApiVersion;
import org.junit.Test;

public class WearStatusBarTest {

  @Test
  public void versionCodesLookupCoversWearReleases() {
    WearApiVersion cb2 = Sdk.VERSION_CODES.lookup("WEAR_CINNAMON_BUN_2");
    assertEquals(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_2, cb2);
    assertEquals(37, cb2.getPlatformApiLevel());
    assertEquals(2, cb2.getIncrementalApiLevel());

    WearApiVersion cb1 = Sdk.VERSION_CODES.lookup("WEAR_CINNAMON_BUN_1");
    assertEquals(Sdk.VERSION_CODES.WEAR_CINNAMON_BUN_1, cb1);
    assertTrue(cb2.compareTo(cb1) > 0);
    assertTrue(cb1.compareTo(Sdk.VERSION_CODES.WEAR_BAKLAVA_1) > 0);

    assertEquals(Sdk.VERSION_CODES.WEAR_BAKLAVA_1, Sdk.VERSION_CODES.lookup("WEAR_BAKLAVA_1"));
    assertNull(Sdk.VERSION_CODES.lookup("WEAR_UNKNOWN_9"));
    assertNull(Sdk.VERSION_CODES.lookup(null));
  }
}
