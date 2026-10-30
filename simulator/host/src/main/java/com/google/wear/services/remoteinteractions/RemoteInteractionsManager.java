package com.google.wear.services.remoteinteractions;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.ResultReceiver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The Wear SDK's remote interactions service as the simulator's watch has it. androidx.wear's
 * {@code RemoteActivityHelper} (wear-remote-interactions 1.2) builds a {@code
 * RemoteInteractionsManagerCompat} that, on a watch at {@code WEAR_TIRAMISU_4} or later (API 34+),
 * asks {@code Sdk.getWearManager} for this class: without it, constructing the helper failed with
 * {@code NoClassDefFoundError}, which is how Horologist's {@code installOnNode} and {@code
 * startRemoteOwnApp} crashed on the watch.
 *
 * <p>Availability is {@code STATUS_AVAILABLE} while the watch is paired ({@code launch --pair}) and
 * {@code STATUS_UNAVAILABLE} otherwise. {@code startRemoteActivity} (used from {@code
 * WEAR_BAKLAVA_0}, API 36) goes the way the pre-SDK path does: the Wear OS app's {@code
 * REMOTE_INTENT} broadcast, which the simulator's data layer takes to the phone, and its result
 * answers the caller's {@link OutcomeReceiver}. Apps compile against the real library and never
 * package it, so this copy on the simulator's class path is the only one.
 */
public class RemoteInteractionsManager {

  /** {@code RemoteActivityHelper.STATUS_UNAVAILABLE} / {@code STATUS_AVAILABLE}. */
  static final int STATUS_UNAVAILABLE = 1;

  static final int STATUS_AVAILABLE = 3;

  private static final String HUB_PROPERTY = "composeai.simulator.datalayer.hub";
  private static final String COMPANION_PACKAGE = "com.google.android.wearable.app";
  private static final String ACTION_REMOTE_INTENT =
      "com.google.android.wearable.intent.action.REMOTE_INTENT";
  private static final String EXTRA_INTENT = "com.google.android.wearable.intent.extra.INTENT";
  private static final String EXTRA_RESULT_RECEIVER =
      "com.google.android.wearable.intent.extra.RESULT_RECEIVER";
  private static final int RESULT_OK = 0;

  private final Context context;
  private final Map<Consumer<Integer>, Executor> listeners = new ConcurrentHashMap<>();

  public RemoteInteractionsManager(Context context) {
    this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
  }

  /** The availability {@code RemoteActivityHelper.availabilityStatus} reports. */
  static int status() {
    return System.getProperty(HUB_PROPERTY) != null ? STATUS_AVAILABLE : STATUS_UNAVAILABLE;
  }

  public void registerRemoteActivityHelperStatusListener(
      Executor executor, Consumer<Integer> listener) {
    listeners.put(listener, executor);
    int status = status();
    executor.execute(() -> listener.accept(status));
  }

  public void unregisterRemoteActivityHelperStatusListener(Consumer<Integer> listener) {
    listeners.remove(listener);
  }

  public void startRemoteActivity(
      Uri dataUri,
      List<String> additionalCategories,
      Executor executor,
      OutcomeReceiver<Void, Throwable> outcomeReceiver) {
    Intent view =
        new Intent(Intent.ACTION_VIEW, dataUri).addCategory(Intent.CATEGORY_BROWSABLE);
    for (String category : additionalCategories) view.addCategory(category);
    ResultReceiver result =
        new ResultReceiver(new Handler(Looper.getMainLooper())) {
          @Override
          protected void onReceiveResult(int resultCode, Bundle resultData) {
            executor.execute(
                () -> {
                  if (resultCode == RESULT_OK) {
                    outcomeReceiver.onResult(null);
                  } else {
                    outcomeReceiver.onError(
                        new IllegalStateException("remote activity failed: " + resultCode));
                  }
                });
          }
        };
    context.sendBroadcast(
        new Intent(ACTION_REMOTE_INTENT)
            .setPackage(COMPANION_PACKAGE)
            .putExtra(EXTRA_INTENT, view)
            .putExtra(EXTRA_RESULT_RECEIVER, result));
  }
}
