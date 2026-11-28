package com.google.firebase.auth;

import com.google.firebase.FirebaseApp;

/** Test-only stand-in for FirebaseAuth used by FirebaseEmulatorWiringTest. */
public class FirebaseAuth {
  private static final FirebaseAuth INSTANCE = new FirebaseAuth();
  public static String wiredTo;

  public static void reset() {
    wiredTo = null;
  }

  public static FirebaseAuth getInstance(FirebaseApp app) {
    return INSTANCE;
  }

  public void useEmulator(String host, int port) {
    wiredTo = host + ":" + port;
  }
}
