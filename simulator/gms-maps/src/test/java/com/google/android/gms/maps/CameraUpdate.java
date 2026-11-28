package com.google.android.gms.maps;

import com.google.android.gms.dynamic.IObjectWrapper;

public final class CameraUpdate {
  public final IObjectWrapper remoteObject;

  public CameraUpdate(IObjectWrapper remoteObject) {
    this.remoteObject = remoteObject;
  }
}
