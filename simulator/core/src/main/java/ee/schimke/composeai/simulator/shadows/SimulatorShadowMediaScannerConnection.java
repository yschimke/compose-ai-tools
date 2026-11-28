package ee.schimke.composeai.simulator.shadows;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import ee.schimke.composeai.simulator.MediaStoreProvider;
import java.io.File;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Shadows {@link MediaScannerConnection#scanFile} so scanned files are indexed in {@link
 * MediaStoreProvider} and the completion callback receives a real {@code content://media/...} URI
 * instead of hanging forever waiting on a system MediaScanner service.
 */
@Implements(MediaScannerConnection.class)
public class SimulatorShadowMediaScannerConnection {

  @Implementation
  protected static void scanFile(
      Context context,
      String[] paths,
      String[] mimeTypes,
      MediaScannerConnection.OnScanCompletedListener callback) {
    if (paths == null) return;
    for (int i = 0; i < paths.length; i++) {
      String path = paths[i];
      String mime = mimeTypes != null && i < mimeTypes.length ? mimeTypes[i] : null;
      Uri uri = path != null ? MediaStoreProvider.registerScannedFile(new File(path), mime) : null;
      if (callback != null) {
        callback.onScanCompleted(path, uri);
      }
    }
  }
}
