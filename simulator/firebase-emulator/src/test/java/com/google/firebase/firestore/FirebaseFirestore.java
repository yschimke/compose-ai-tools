package com.google.firebase.firestore;

import com.google.firebase.FirebaseApp;

/** Test-only stand-in for FirebaseFirestore used by FirebaseEmulatorWiringTest. */
public class FirebaseFirestore {
  private static final FirebaseFirestore INSTANCE = new FirebaseFirestore();
  public static String wiredTo;

  public static void reset() {
    wiredTo = null;
  }

  public static FirebaseFirestore getInstance(FirebaseApp app) {
    return INSTANCE;
  }

  public void useEmulator(String host, int port) {
    if (wiredTo != null) {
      throw new IllegalStateException("Firestore has already been started");
    }
    wiredTo = host + ":" + port;
  }
}
