package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

public class PlayServicesTest {

  @After
  public void reset() {
    PlayServices.set(PlayServices.State.INSTALLED);
  }

  @Test
  public void installedByDefaultAnswersSuccessForAnyCurrentClient() {
    assertEquals(PlayServices.State.INSTALLED, PlayServices.state());
    // GOOGLE_PLAY_SERVICES_VERSION_CODE of play-services-basement 18.x.
    assertEquals(PlayServices.SUCCESS, PlayServices.status(12_451_000));
    // androidx.credentials' minimum for its Play services provider.
    assertEquals(PlayServices.SUCCESS, PlayServices.status(230_815_045));
    assertEquals(PlayServices.SUCCESS, PlayServices.status(0));
    assertEquals(PlayServices.VERSION_CODE, PlayServices.apkVersion());
    assertTrue(PlayServices.installed());
    assertTrue(PlayServices.enabled());
  }

  @Test
  public void aNewerMinimumThanInstalledNeedsAnUpdate() {
    assertEquals(PlayServices.SERVICE_VERSION_UPDATE_REQUIRED, PlayServices.status(999_999_999));
    // The build variant (last three digits) is not compared, as Play services does not.
    assertEquals(PlayServices.SUCCESS, PlayServices.status(PlayServices.VERSION_CODE + 900));
  }

  @Test
  public void eachStateReportsItsConnectionResult() {
    PlayServices.set(PlayServices.State.NONE);
    assertEquals(PlayServices.SERVICE_MISSING, PlayServices.status(0));
    assertEquals(0, PlayServices.apkVersion());
    assertNull(PlayServices.versionName());
    assertFalse(PlayServices.installed());

    PlayServices.set(PlayServices.State.OUTDATED);
    assertEquals(PlayServices.SERVICE_VERSION_UPDATE_REQUIRED, PlayServices.status(0));
    assertTrue(PlayServices.apkVersion() / 1000 < 12_451_000 / 1000);

    PlayServices.set(PlayServices.State.DISABLED);
    assertEquals(PlayServices.SERVICE_DISABLED, PlayServices.status(0));
    assertTrue(PlayServices.installed());
    assertFalse(PlayServices.enabled());

    PlayServices.set(PlayServices.State.INVALID);
    assertEquals(PlayServices.SERVICE_INVALID, PlayServices.status(0));
  }

  @Test
  public void anApiIsAvailableOnlyWhenSimulatedAndPlayServicesWorks() {
    assertEquals(PlayServices.SUCCESS, PlayServices.apiStatus(true));
    assertEquals(PlayServices.API_UNAVAILABLE, PlayServices.apiStatus(false));
    PlayServices.set(PlayServices.State.NONE);
    assertEquals(PlayServices.SERVICE_MISSING, PlayServices.apiStatus(true));
    assertEquals(PlayServices.SERVICE_MISSING, PlayServices.apiStatus(false));
  }

  @Test
  public void parsesTheLaunchOptionsStates() {
    assertEquals(PlayServices.State.NONE, PlayServices.State.parse("none"));
    assertEquals(PlayServices.State.NONE, PlayServices.State.parse(" Missing "));
    assertEquals(PlayServices.State.OUTDATED, PlayServices.State.parse("OUTDATED"));
    assertEquals("installed, none, outdated, disabled, invalid", PlayServices.State.ids());
    try {
      PlayServices.State.parse("sometimes");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("installed, none"));
    }
  }
}
