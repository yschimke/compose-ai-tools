// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import java.util.ArrayList;
import java.util.Set;

public class DataMap {
  public DataMap() {}

  public static DataMap fromByteArray(byte[] bytes) {
    return null;
  }

  public byte[] toByteArray() {
    return null;
  }

  public <T> T get(String key) {
    return null;
  }

  public Set<String> keySet() {
    return null;
  }

  public void putBoolean(String key, boolean value) {}

  public void putInt(String key, int value) {}

  public void putLong(String key, long value) {}

  public void putFloat(String key, float value) {}

  public void putDouble(String key, double value) {}

  public void putString(String key, String value) {}

  public void putDataMap(String key, DataMap value) {}

  public void putDataMapArrayList(String key, ArrayList<DataMap> value) {}

  public void putIntegerArrayList(String key, ArrayList<Integer> value) {}

  public void putStringArrayList(String key, ArrayList<String> value) {}

  public void putByteArray(String key, byte[] value) {}

  public void putLongArray(String key, long[] value) {}

  public void putFloatArray(String key, float[] value) {}

  public void putStringArray(String key, String[] value) {}
}
