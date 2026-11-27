package com.google.android.gms.common.api;

/** Test stand-in for Play services' {@code Status}. */
public final class Status {
  private final int statusCode;

  public Status(int statusCode) {
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
