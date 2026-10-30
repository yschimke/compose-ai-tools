package ee.schimke.composeai.simulator;

import androidx.appsearch.localstorage.LocalStorage;
import com.google.common.util.concurrent.ListenableFuture;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * androidx.appsearch's {@code LocalStorage}, whose Icing engine is a native library the simulator
 * cannot load: its sessions are {@link AppSearchSessions}' in-memory ones. Like {@link
 * ShadowGooglePlayServicesUtilLight} it shadows a class of the app's, instrumented by {@link
 * SimulatorSandbox}; an app without androidx.appsearch never loads it.
 */
@Implements(className = ShadowLocalStorage.LOCAL_STORAGE, isInAndroidSdk = false)
public class ShadowLocalStorage {

  static final String LOCAL_STORAGE = "androidx.appsearch.localstorage.LocalStorage";

  @Implementation
  protected static ListenableFuture<Object> createSearchSessionAsync(
      LocalStorage.SearchContext context) {
    return AppSearchSessions.open("local", context);
  }
}
