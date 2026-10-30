package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Tasks;
import ee.schimke.composeai.simulator.fakegms.ApiKeyed;
import ee.schimke.composeai.simulator.fakegms.HiddenSimpleMapException;
import ee.schimke.composeai.simulator.fakegms.NoMapException;
import ee.schimke.composeai.simulator.fakegms.PublicMapException;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * {@link ShadowGoogleApiAvailability}'s failed {@code Task}, against stand-ins for Play services'
 * classes: whatever shape {@code AvailabilityException}'s constructor has, the answer is a failed
 * task, never a synchronous throw.
 */
public class AvailabilityAnswerTest {
  private final ClassLoader loader = getClass().getClassLoader();
  private final List<Object> apis = Arrays.asList(new ApiKeyed("wearable"), new ApiKeyed("other"));
  private final int[] statuses = {PlayServices.API_UNAVAILABLE, PlayServices.SERVICE_MISSING};

  @After
  public void reset() {
    PlayServices.set(PlayServices.State.INSTALLED);
  }

  private static Exception failure(Object task) {
    Tasks.Done done = (Tasks.Done) task;
    assertNull(done.result);
    return done.exception;
  }

  @Test
  public void aPublicMapConstructorGetsEveryApisConnectionResult() {
    Exception failure =
        failure(
            ShadowGoogleApiAvailability.failedTask(
                loader, apis, statuses, PublicMapException.class.getName()));
    PublicMapException exception = (PublicMapException) failure;
    assertEquals(16, ((ConnectionResult) exception.results.get("wearable")).getErrorCode());
    assertEquals(1, ((ConnectionResult) exception.results.get("other")).getErrorCode());
  }

  @Test
  public void aHiddenConstructorTakingANonMapMapIsUsed() {
    Exception failure =
        failure(
            ShadowGoogleApiAvailability.failedTask(
                loader, apis, statuses, HiddenSimpleMapException.class.getName()));
    HiddenSimpleMapException exception = (HiddenSimpleMapException) failure;
    assertEquals(16, ((ConnectionResult) exception.results.entries.get("wearable")).getErrorCode());
    assertEquals(1, ((ConnectionResult) exception.results.entries.get("other")).getErrorCode());
  }

  @Test
  public void noUsableConstructorFallsBackToAnApiException() {
    Exception failure =
        failure(
            ShadowGoogleApiAvailability.failedTask(
                loader, apis, statuses, NoMapException.class.getName()));
    assertEquals(16, ((ApiException) failure).getStatusCode());
  }

  @Test
  public void aMissingAvailabilityExceptionStillAnswersAFailedTask() {
    Exception failure = failure(ShadowGoogleApiAvailability.answer(new ApiKeyed("x"), null));
    assertTrue(failure instanceof ApiException);
    assertEquals(PlayServices.API_UNAVAILABLE, ((ApiException) failure).getStatusCode());
  }
}
