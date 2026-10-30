package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BackRouteTest {

  @Test
  public void anOptedInWindowHearsItsCallbackAndNoKey() {
    assertEquals(BackRoute.CALLBACK, BackRoute.of(false, true));
  }

  @Test
  public void aWindowThatDidNotOptInGetsTheKey() {
    assertEquals(BackRoute.KEY, BackRoute.of(false, false));
  }

  @Test
  public void theHomeActivityTakesBackItselfWhateverTheAppOptedInTo() {
    assertEquals(BackRoute.SYSTEM, BackRoute.of(true, true));
    assertEquals(BackRoute.SYSTEM, BackRoute.of(true, false));
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
