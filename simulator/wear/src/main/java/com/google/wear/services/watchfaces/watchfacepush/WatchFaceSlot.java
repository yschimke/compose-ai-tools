package com.google.wear.services.watchfaces.watchfacepush;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Simulator implementation of the Wear SDK {@code WatchFaceSlot} returned by {@link
 * WatchFacePushManager}. Signatures match the Wear OS 6 SDK bytecode contract consumed by {@code
 * androidx.wear.watchfacepush}.
 */
public final class WatchFaceSlot {

  private final String slotId;
  private final String packageName;
  private final long versionCode;
  private final Map<String, List<String>> metaData;

  public WatchFaceSlot(
      String slotId, String packageName, long versionCode, Map<String, List<String>> metaData) {
    this.slotId = slotId;
    this.packageName = packageName;
    this.versionCode = versionCode;
    this.metaData = metaData == null ? Collections.emptyMap() : metaData;
  }

  public String getSlotId() {
    return slotId;
  }

  public String getPackageName() {
    return packageName;
  }

  public long getVersionCode() {
    return versionCode;
  }

  public List<String> getMetaDataValues(String key) {
    if (key == null) return Collections.emptyList();
    List<String> values = metaData.get(key);
    return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
  }
}
