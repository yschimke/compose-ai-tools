package com.google.android.gms.maps.model;

public final class MarkerOptions {
  private final LatLng position;
  private final String title;
  private final String snippet;
  private final boolean visible;

  public MarkerOptions(LatLng position, String title, String snippet) {
    this(position, title, snippet, true);
  }

  public MarkerOptions(LatLng position, String title, String snippet, boolean visible) {
    this.position = position;
    this.title = title;
    this.snippet = snippet;
    this.visible = visible;
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

  public boolean isVisible() {
    return visible;
  }
}
