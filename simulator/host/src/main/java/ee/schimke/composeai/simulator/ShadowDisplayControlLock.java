package ee.schimke.composeai.simulator;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Simulator-safe implementation of {@code com.google.android.clockwork.ambient.DisplayControlLock}.
 *
 * <p>Wear OS watch face runtimes compile against stubbed {@code clockwork.ambient} classes whose
 * constructors and methods throw {@code RuntimeException("Stub!")}. Under Robolectric there is no
 * display offload hardware, so track lock state in memory.
 */
@Implements(className = ShadowDisplayControlLock.TARGET, isInAndroidSdk = false)
public final class ShadowDisplayControlLock {

  static final String TARGET = "com.google.android.clockwork.ambient.DisplayControlLock";

  private boolean held;

  @Implementation
  protected void __constructor__() {}

  @Implementation
  protected boolean acquire() {
    held = true;
    return true;
  }

  @Implementation
  protected boolean isHeld() {
    return held;
  }

  @Implementation
  protected void release() {
    held = false;
  }
}
