package com.google.android.gms.maps.internal;

import com.google.android.gms.maps.model.CameraPosition;

public interface IGoogleMapDelegate {
  CameraPosition getCameraPosition();

  int getMapType();

  void setMapType(int type);

  void clear();
}
