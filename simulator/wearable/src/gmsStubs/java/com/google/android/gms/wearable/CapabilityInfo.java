// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import java.util.Set;

public interface CapabilityInfo {
  String getName();

  Set<Node> getNodes();
}
