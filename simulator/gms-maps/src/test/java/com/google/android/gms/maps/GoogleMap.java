package com.google.android.gms.maps;

import com.google.android.gms.maps.internal.IGoogleMapDelegate;
import com.google.android.gms.maps.model.CameraPosition;

public final class GoogleMap {
  public final IGoogleMapDelegate delegate;

  public GoogleMap(IGoogleMapDelegate delegate) {
    this.delegate = delegate;
  }

  public CameraPosition getCameraPosition() {
    return delegate.getCameraPosition();
  }

  public int getMapType() {
    return delegate.getMapType();
  }

  public void setMapType(int type) {
    delegate.setMapType(type);
  }
}
