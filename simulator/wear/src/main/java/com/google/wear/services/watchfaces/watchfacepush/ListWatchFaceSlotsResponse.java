package com.google.wear.services.watchfaces.watchfacepush;

import java.util.Collections;
import java.util.List;

/**
 * Simulator implementation of the Wear SDK {@code ListWatchFaceSlotsResponse} returned by {@link
 * WatchFacePushManager#listWatchFaceSlots}.
 */
public final class ListWatchFaceSlotsResponse {

  private final List<WatchFaceSlot> installedWatchFaceSlots;
  private final int availableSlotCount;

  public ListWatchFaceSlotsResponse(
      List<WatchFaceSlot> installedWatchFaceSlots, int availableSlotCount) {
    this.installedWatchFaceSlots =
        installedWatchFaceSlots == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(installedWatchFaceSlots);
    this.availableSlotCount = Math.max(0, availableSlotCount);
  }

  public List<WatchFaceSlot> getInstalledWatchFaceSlots() {
    return installedWatchFaceSlots;
  }

  public int getAvailableSlotCount() {
    return availableSlotCount;
  }
}
