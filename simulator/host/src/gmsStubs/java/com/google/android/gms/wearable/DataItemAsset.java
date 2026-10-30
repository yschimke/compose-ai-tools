// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import com.google.android.gms.common.data.Freezable;

public interface DataItemAsset extends Freezable<DataItemAsset> {
  String getId();

  String getDataItemKey();
}
