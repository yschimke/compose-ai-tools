package com.google.firebase;

/** Test-only stand-in for FirebaseApp used by FirebaseEmulatorWiringTest. */
public class FirebaseApp {
  private static final FirebaseApp INSTANCE = new FirebaseApp();
  private final FirebaseOptions options = new FirebaseOptions();

  public static FirebaseApp getInstance() {
    return INSTANCE;
  }

  public FirebaseOptions getOptions() {
    return options;
  }

  public static class FirebaseOptions {
    public String getProjectId() {
      return "test-firebase-project";
    }

    public String getApplicationId() {
      return "1:123:android:abc";
    }

    public String getDatabaseUrl() {
      return "https://test-firebase-project-default-rtdb.firebaseio.com";
    }

    public String getStorageBucket() {
      return "test-firebase-project.appspot.com";
    }

    public String getGcmSenderId() {
      return "123456789";
    }
  }
}
