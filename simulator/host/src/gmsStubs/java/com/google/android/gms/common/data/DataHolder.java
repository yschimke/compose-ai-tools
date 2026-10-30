// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.common.data;

import android.content.ContentValues;

public final class DataHolder {
  public static Builder builder(String[] columns) {
    throw new UnsupportedOperationException();
  }

  public static class Builder {
    public Builder withRow(ContentValues values) {
      throw new UnsupportedOperationException();
    }

    public DataHolder build(int statusCode) {
      throw new UnsupportedOperationException();
    }
  }
}
