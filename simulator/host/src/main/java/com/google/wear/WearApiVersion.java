package com.google.wear;

import java.util.Objects;

/**
 * Wear OS incremental API version ({@code wear-sdk}), returned by {@link
 * Sdk.VERSION_CODES#lookup(String)} and passed to {@link Sdk#isApiVersionAtLeast(WearApiVersion)}.
 *
 * <p>{@code androidx.wear.utils.WearApiVersionHelper} uses this class on API 36+ to check
 * incremental Wear OS releases such as {@code WEAR_CINNAMON_BUN_2} (API 37.2, required for the
 * Wear OS global status bar).
 */
public final class WearApiVersion implements Comparable<WearApiVersion> {

  private final String name;
  private final int platformApiLevel;
  private final int incrementalApiLevel;

  WearApiVersion(String name, int platformApiLevel, int incrementalApiLevel) {
    this.name = Objects.requireNonNull(name, "name");
    this.platformApiLevel = platformApiLevel;
    this.incrementalApiLevel = incrementalApiLevel;
  }

  public String getName() {
    return name;
  }

  public int getPlatformApiLevel() {
    return platformApiLevel;
  }

  public int getIncrementalApiLevel() {
    return incrementalApiLevel;
  }

  @Override
  public int compareTo(WearApiVersion other) {
    int major = Integer.compare(this.platformApiLevel, other.platformApiLevel);
    return major != 0 ? major : Integer.compare(this.incrementalApiLevel, other.incrementalApiLevel);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof WearApiVersion)) return false;
    WearApiVersion other = (WearApiVersion) obj;
    return platformApiLevel == other.platformApiLevel
        && incrementalApiLevel == other.incrementalApiLevel;
  }

  @Override
  public int hashCode() {
    return Objects.hash(platformApiLevel, incrementalApiLevel);
  }

  @Override
  public String toString() {
    return name + " (" + platformApiLevel + "." + incrementalApiLevel + ")";
  }
}
