package com.google.android.gms.maps.model;

public final class CameraPosition {
  public final LatLng target;
  public final float zoom;
  public final float tilt;
  public final float bearing;

  public CameraPosition(LatLng target, float zoom, float tilt, float bearing) {
    this.target = target;
    this.zoom = zoom;
    this.tilt = tilt;
    this.bearing = bearing;
  }
}
