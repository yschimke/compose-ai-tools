package com.google.android.gms.auth.api.identity;

import android.app.PendingIntent;
import android.content.Intent;
import com.google.android.gms.tasks.Task;

public final class TestIdentityApi {
  private TestIdentityApi() {}
}

interface SignInClient {
  Task<BeginSignInResult> beginSignIn(Object request);

  Task<PendingIntent> getSignInIntent(Object request);

  SignInCredential getSignInCredentialFromIntent(Intent data);

  Task<Void> signOut();
}

interface AuthorizationClient {
  Task<AuthorizationResult> authorize(Object request);

  AuthorizationResult getAuthorizationResultFromIntent(Intent data);
}

interface CredentialSavingClient {
  Task<SavePasswordResult> savePassword(Object request);

  Task<SaveAccountLinkingTokenResult> saveAccountLinkingToken(Object request);

  com.google.android.gms.common.api.Status getStatusFromIntent(Intent data);
}

final class SavePasswordResult {
  public final PendingIntent pendingIntent;

  public SavePasswordResult(PendingIntent pendingIntent) {
    this.pendingIntent = pendingIntent;
  }
}

final class SaveAccountLinkingTokenResult {
  public final PendingIntent pendingIntent;

  public SaveAccountLinkingTokenResult(PendingIntent pendingIntent) {
    this.pendingIntent = pendingIntent;
  }
}

final class BeginSignInResult {
  public final PendingIntent pendingIntent;

  public BeginSignInResult(PendingIntent pendingIntent) {
    this.pendingIntent = pendingIntent;
  }
}

final class SignInCredential {
  public final String id;
  public final String displayName;
  public final String googleIdToken;
  public final String password;

  public SignInCredential(String id, String displayName, String googleIdToken, String password) {
    this.id = id;
    this.displayName = displayName;
    this.googleIdToken = googleIdToken;
    this.password = password;
  }

  public String getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getGoogleIdToken() {
    return googleIdToken;
  }
}

final class AuthorizationResult {
  public final String serverAuthCode;
  public final String accessToken;
  public final String idToken;

  public AuthorizationResult(String serverAuthCode, String accessToken, String idToken) {
    this.serverAuthCode = serverAuthCode;
    this.accessToken = accessToken;
    this.idToken = idToken;
  }

  public String getServerAuthCode() {
    return serverAuthCode;
  }

  public String getAccessToken() {
    return accessToken;
  }
}
