package ee.schimke.composeai.simulator;

import androidx.appsearch.platformstorage.PlatformStorage;
import com.google.common.util.concurrent.ListenableFuture;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * androidx.appsearch's {@code PlatformStorage}, whose framework {@code AppSearchManager}
 * Robolectric does not provide (the app saw a {@code NullPointerException}): its sessions are
 * {@link AppSearchSessions}' in-memory ones, kept apart from {@link ShadowLocalStorage}'s as the
 * two storages are on a device.
 */
@Implements(className = ShadowPlatformStorage.PLATFORM_STORAGE, isInAndroidSdk = false)
public class ShadowPlatformStorage {

  static final String PLATFORM_STORAGE = "androidx.appsearch.platformstorage.PlatformStorage";

  @Implementation
  protected static ListenableFuture<Object> createSearchSessionAsync(
      PlatformStorage.SearchContext context) {
    return AppSearchSessions.open("platform", context);
  }
}
