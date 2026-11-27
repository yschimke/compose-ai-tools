// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.common.data;

public interface Freezable<T> {
  T freeze();

  boolean isDataValid();
}
