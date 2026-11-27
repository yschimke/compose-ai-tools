// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import android.net.Uri;
import com.google.android.gms.common.data.Freezable;
import java.util.Map;

public interface DataItem extends Freezable<DataItem> {
  Uri getUri();

  byte[] getData();

  DataItem setData(byte[] data);

  Map<String, DataItemAsset> getAssets();
}
