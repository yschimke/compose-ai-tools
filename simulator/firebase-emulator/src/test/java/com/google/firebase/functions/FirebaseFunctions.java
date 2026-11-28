package com.google.firebase.functions;

import com.google.firebase.FirebaseApp;
import java.util.HashMap;
import java.util.Map;

/** Test-only stand-in for FirebaseFunctions supporting regional instances. */
public class FirebaseFunctions {
  private static final Map<String, FirebaseFunctions> BY_REGION = new HashMap<>();

  public final String region;
  public String wiredTo;
  public int wireCalls;

  private FirebaseFunctions(String region) {
    this.region = region;
  }

  public static void reset() {
    BY_REGION.clear();
  }

  public static FirebaseFunctions getInstance(FirebaseApp app) {
    return getInstance(app, "us-central1");
  }

  public static FirebaseFunctions getInstance(FirebaseApp app, String region) {
    return BY_REGION.computeIfAbsent(region, FirebaseFunctions::new);
  }

  public void useEmulator(String host, int port) {
    wiredTo = host + ":" + port;
    wireCalls++;
  }
}
