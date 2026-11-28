package com.google.android.gms.maps.model;

public final class MarkerOptions {
  private final LatLng position;
  private final String title;
  private final String snippet;

  public MarkerOptions(LatLng position, String title, String snippet) {
    this.position = position;
    this.title = title;
    this.snippet = snippet;
  }

  public LatLng getPosition() {
    return position;
  }

  public String getTitle() {
    return title;
  }

  public String getSnippet() {
    return snippet;
  }
}
