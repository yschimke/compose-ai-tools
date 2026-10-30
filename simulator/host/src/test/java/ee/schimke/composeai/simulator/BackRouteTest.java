package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BackRouteTest {

  @Test
  public void anOptedInWindowHearsItsCallbackAndNoKey() {
    assertEquals(BackRoute.CALLBACK, BackRoute.of(false, true, false));
  }

  @Test
  public void aWindowThatDidNotOptInGetsTheKey() {
    assertEquals(BackRoute.KEY, BackRoute.of(false, false, false));
  }

  @Test
  public void theHomeActivityTakesBackItselfWhateverTheAppOptedInTo() {
    assertEquals(BackRoute.SYSTEM, BackRoute.of(true, true, false));
    assertEquals(BackRoute.SYSTEM, BackRoute.of(true, false, false));
  }

  @Test
  public void nothingInTheAppWantingBackLeavesItToTheActivity() {
    // The activity's own default callback would ask the (absent) system server to finish it.
    assertEquals(BackRoute.ACTIVITY, BackRoute.of(false, true, true));
  }

  @Test
  public void anAnimatedCallbackHearsThePressStartThenTheRelease() {
    Recording callback = new Recording(true);
    BackRoute.deliver(callback, 34);
    assertEquals(List.of("started", "invoked"), callback.calls);
  }

  @Test
  public void beforeApi34OnlyTheReleaseIsDelivered() {
    Recording callback = new Recording(true);
    BackRoute.deliver(callback, 33);
    assertEquals(List.of("invoked"), callback.calls);
  }

  @Test
  public void aPlainCallbackIsInvokedOnce() {
    Recording callback = new Recording(false);
    BackRoute.deliver(callback, 36);
    assertEquals(List.of("invoked"), callback.calls);
  }

  private static final class Recording implements BackRoute.Steps {
    final List<String> calls = new ArrayList<>();
    private final boolean animated;

    Recording(boolean animated) {
      this.animated = animated;
    }

    @Override
    public boolean animated() {
      return animated;
    }

    @Override
    public void started() {
      calls.add("started");
    }

    @Override
    public void invoked() {
      calls.add("invoked");
    }
  }
}
