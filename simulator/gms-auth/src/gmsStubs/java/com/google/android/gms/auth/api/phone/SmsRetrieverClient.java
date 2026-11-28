package com.google.android.gms.auth.api.phone;

import android.app.Activity;
import android.content.Context;
import com.google.android.gms.tasks.Task;

/** Compile-only stub for Play services' abstract {@code SmsRetrieverClient}. */
public abstract class SmsRetrieverClient {

  public SmsRetrieverClient(Context context) {}

  public SmsRetrieverClient(Activity activity) {}

  public abstract Task<Void> startSmsRetriever();

  public abstract Task<Void> startSmsUserConsent(String senderPhoneNumber);
}
