// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

public interface MessageEvent {
  int getRequestId();

  String getPath();

  byte[] getData();

  String getSourceNodeId();
}
