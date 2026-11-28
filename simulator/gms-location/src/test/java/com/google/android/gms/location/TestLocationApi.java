package com.google.android.gms.location;

import android.app.PendingIntent;
import android.location.Location;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.Task;
import java.util.List;
import java.util.concurrent.Executor;

/** Test-only mirrors of {@code play-services-location} 21.x interfaces and value classes. */
public final class TestLocationApi {
  private TestLocationApi() {}
}

interface FusedLocationProviderClient {
  Task<Location> getLastLocation();

  Task<Location> getCurrentLocation(int priority, Object token);

  Task<LocationAvailability> getLocationAvailability();

  Task<Void> requestLocationUpdates(Object request, LocationCallback callback, Object looper);

  Task<Void> requestLocationUpdates(Object request, Executor executor, LocationCallback callback);

  Task<Void> requestLocationUpdates(Object request, Executor executor, LocationListener listener);

  Task<Void> removeLocationUpdates(LocationCallback callback);

  Task<Void> removeLocationUpdates(LocationListener listener);

  Task<Void> flushLocations();

  Task<Void> setMockMode(boolean mockMode);

  Task<Void> setMockLocation(Location location);
}

interface GeofencingClient {
  Task<Void> addGeofences(GeofencingRequest request, PendingIntent pendingIntent);

  Task<Void> removeGeofences(List<String> requestIds);

  Task<Void> removeGeofences(PendingIntent pendingIntent);
}

interface SettingsClient {
  Task<LocationSettingsResponse> checkLocationSettings(Object request);
}

interface ActivityRecognitionClient {
  Task<Void> requestActivityUpdates(long intervalMillis, PendingIntent pendingIntent);

  Task<Void> removeActivityUpdates(PendingIntent pendingIntent);

  Task<Void> requestActivityTransitionUpdates(Object request, PendingIntent pendingIntent);

  Task<Void> removeActivityTransitionUpdates(PendingIntent pendingIntent);
}

abstract class LocationCallback {
  public void onLocationResult(LocationResult result) {}

  public void onLocationAvailability(LocationAvailability availability) {}
}

interface LocationListener {
  void onLocationChanged(Location location);
}

final class LocationResult {
  private final List<Location> locations;

  private LocationResult(List<Location> locations) {
    this.locations = locations;
  }

  public static LocationResult create(List<Location> locations) {
    return new LocationResult(locations);
  }

  public List<Location> getLocations() {
    return locations;
  }

  public Location getLastLocation() {
    return locations.isEmpty() ? null : locations.get(locations.size() - 1);
  }
}

final class LocationAvailability {
  private final boolean available;

  public LocationAvailability(boolean available) {
    this.available = available;
  }

  public boolean isLocationAvailable() {
    return available;
  }
}

final class LocationSettingsStates {
  public final boolean gpsUsable;
  public final boolean networkLocationUsable;
  public final boolean bleUsable;
  public final boolean gpsPresent;
  public final boolean networkLocationPresent;
  public final boolean blePresent;

  public LocationSettingsStates(
      boolean gpsUsable,
      boolean networkLocationUsable,
      boolean bleUsable,
      boolean gpsPresent,
      boolean networkLocationPresent,
      boolean blePresent) {
    this.gpsUsable = gpsUsable;
    this.networkLocationUsable = networkLocationUsable;
    this.bleUsable = bleUsable;
    this.gpsPresent = gpsPresent;
    this.networkLocationPresent = networkLocationPresent;
    this.blePresent = blePresent;
  }
}

final class LocationSettingsResult {
  public final Status status;
  public final LocationSettingsStates states;

  public LocationSettingsResult(Status status, LocationSettingsStates states) {
    this.status = status;
    this.states = states;
  }
}

final class LocationSettingsResponse {
  private final LocationSettingsResult result;

  public LocationSettingsResponse(LocationSettingsResult result) {
    this.result = result;
  }

  public LocationSettingsStates getLocationSettingsStates() {
    return result.states;
  }
}

final class Geofence {
  private final String requestId;
  private final double latitude;
  private final double longitude;
  private final float radius;
  private final int transitionTypes;

  public Geofence(
      String requestId, double latitude, double longitude, float radius, int transitionTypes) {
    this.requestId = requestId;
    this.latitude = latitude;
    this.longitude = longitude;
    this.radius = radius;
    this.transitionTypes = transitionTypes;
  }

  public String getRequestId() {
    return requestId;
  }

  public double getLatitude() {
    return latitude;
  }

  public double getLongitude() {
    return longitude;
  }

  public float getRadius() {
    return radius;
  }

  public int getTransitionTypes() {
    return transitionTypes;
  }
}

final class GeofencingRequest {
  private final List<Geofence> geofences;

  public GeofencingRequest(List<Geofence> geofences) {
    this.geofences = geofences;
  }

  public List<Geofence> getGeofences() {
    return geofences;
  }
}
