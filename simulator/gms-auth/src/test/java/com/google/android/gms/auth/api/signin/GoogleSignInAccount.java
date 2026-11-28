package com.google.android.gms.auth.api.signin;

public final class GoogleSignInAccount {
  private final String id;
  private final String email;
  private final String displayName;
  private final String idToken;

  public GoogleSignInAccount(String id, String email, String displayName, String idToken) {
    this.id = id;
    this.email = email;
    this.displayName = displayName;
    this.idToken = idToken;
  }

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getIdToken() {
    return idToken;
  }
}
