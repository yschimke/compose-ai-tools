package ee.schimke.composeai.simulator;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import java.util.Collection;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * Robolectric's {@code ContentResolver} shadow, whose {@code notifyChange} also tells the device's
 * other apps of a change to one of this app's providers ({@link DeviceProviders#notified}), so their
 * observers hear of it, as the system's content service tells every process. Added to the shadow
 * map by {@link SimulatorSandbox}.
 */
@Implements(ContentResolver.class)
public class SimulatorShadowContentResolver extends ShadowContentResolver {

  /**
   * Set on a thread inside a notifyChange, whose overloads call one another: each change goes out
   * once, and another thread's change is its own.
   */
  private static final ThreadLocal<Boolean> notifying = ThreadLocal.withInitial(() -> false);

  private void notifyAround(Collection<Uri> uris, int flags, Runnable delivery) {
    boolean outer = !notifying.get();
    if (outer) DeviceProviders.notified(uris, flags);
    notifying.set(true);
    try {
      delivery.run();
    } finally {
      if (outer) notifying.set(false);
    }
  }

  @Implementation
  @Override
  protected void notifyChange(Uri uri, ContentObserver observer) {
    notifyAround(List.of(uri), 0, () -> super.notifyChange(uri, observer));
  }

  @Implementation
  @Override
  protected void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
    notifyAround(List.of(uri), 0, () -> super.notifyChange(uri, observer, syncToNetwork));
  }

  @Implementation
  @Override
  protected void notifyChange(Uri uri, ContentObserver observer, int flags) {
    notifyAround(List.of(uri), flags, () -> super.notifyChange(uri, observer, flags));
  }

  @Implementation
  @Override
  protected void notifyChange(Collection<Uri> uris, ContentObserver observer, int flags) {
    notifyAround(uris, flags, () -> super.notifyChange(uris, observer, flags));
  }
}
