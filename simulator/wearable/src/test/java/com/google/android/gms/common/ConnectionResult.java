package com.google.android.gms.common;

/** Test stand-in for Play services' {@code ConnectionResult}. */
public final class ConnectionResult {
  private final int errorCode;

  public ConnectionResult(int errorCode) {
    this.errorCode = errorCode;
  }

  public int getErrorCode() {
    return errorCode;
  }
}
