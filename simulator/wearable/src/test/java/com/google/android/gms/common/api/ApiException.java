package com.google.android.gms.common.api;

/** Test stand-in for Play services' {@code ApiException}. */
public class ApiException extends Exception {
  private final Status status;

  public ApiException(Status status) {
    super(String.valueOf(status.getStatusCode()));
    this.status = status;
  }

  public int getStatusCode() {
    return status.getStatusCode();
  }
}
