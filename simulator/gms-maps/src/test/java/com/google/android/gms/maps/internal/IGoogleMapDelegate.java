package com.google.android.gms.maps.internal;

import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.MarkerOptions;

public interface IGoogleMapDelegate {
  CameraPosition getCameraPosition();

  void moveCamera(IObjectWrapper update);

  void animateCamera(IObjectWrapper update);

  int getMapType();

  void setMapType(int type);

  IMarkerDelegate addMarker(MarkerOptions options);

  void setOnMarkerClickListener(OnMarkerClickDelegate listener);

  void clear();

  interface IMarkerDelegate {
    String getId();

    String getTitle();
  }

  interface OnMarkerClickDelegate {
    boolean onMarkerClick(IMarkerDelegate marker);
  }
}
