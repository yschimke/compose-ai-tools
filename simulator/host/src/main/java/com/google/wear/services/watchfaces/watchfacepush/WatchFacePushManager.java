package com.google.wear.services.watchfaces.watchfacepush;

import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import ee.schimke.composeai.simulator.WatchFacePushStore;
import java.util.concurrent.Executor;

/**
 * Simulator implementation of the Wear OS 6 {@code WatchFacePushManager} system service obtained
 * through {@link com.google.wear.Sdk#getWearManager(Context, Class)}.
 *
 * <p>All method and nested exception signatures match the {@code wear-sdk} bytecode contract called
 * by {@code androidx.wear.watchfacepush:watchfacepush}.
 */
public final class WatchFacePushManager {

  public static final class AddException extends Exception {
    public static final int ERROR_UNKNOWN = 1;
    public static final int ERROR_UNEXPECTED_CONTENT = 2;
    public static final int ERROR_INVALID_PACKAGE_NAME = 3;
    public static final int ERROR_MALFORMED_WATCHFACE_APK = 4;
    public static final int ERROR_SLOT_LIMIT_REACHED = 5;
    public static final int ERROR_INVALID_VALIDATION_TOKEN = 6;

    private final int errorCode;

    public AddException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  public static final class UpdateException extends Exception {
    public static final int ERROR_UNKNOWN = 1;
    public static final int ERROR_UNEXPECTED_CONTENT = 2;
    public static final int ERROR_INVALID_PACKAGE_NAME = 3;
    public static final int ERROR_MALFORMED_WATCHFACE_APK = 4;
    public static final int ERROR_INVALID_SLOT_ID = 5;
    public static final int ERROR_INVALID_VALIDATION_TOKEN = 6;

    private final int errorCode;

    public UpdateException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  public static final class RemoveException extends Exception {
    public static final int ERROR_UNKNOWN = 1;
    public static final int ERROR_INVALID_SLOT_ID = 2;

    private final int errorCode;

    public RemoveException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  public static final class ListException extends Exception {
    public static final int ERROR_UNKNOWN = 1;

    private final int errorCode;

    public ListException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  public static final class SetActiveException extends Exception {
    public static final int ERROR_UNKNOWN = 1;
    public static final int ERROR_MAXIMUM_ATTEMPTS_REACHED = 2;
    public static final int ERROR_INVALID_SLOT_ID = 3;

    private final int errorCode;

    public SetActiveException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  public static final class IsActiveException extends Exception {
    public static final int ERROR_UNKNOWN = 1;
    public static final int ERROR_INVALID_PACKAGE_NAME = 2;

    private final int errorCode;

    public IsActiveException(int errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  private final Context context;

  public WatchFacePushManager(Context context) {
    this.context =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
  }

  public void listWatchFaceSlots(
      Executor executor, OutcomeReceiver<ListWatchFaceSlotsResponse, ListException> receiver) {
    WatchFacePushStore.get().listWatchFaceSlots(context, executor, receiver);
  }

  public void addWatchFace(
      ParcelFileDescriptor apkFd,
      String validationToken,
      Executor executor,
      OutcomeReceiver<WatchFaceSlot, AddException> receiver) {
    WatchFacePushStore.get().addWatchFace(context, apkFd, validationToken, executor, receiver);
  }

  public void updateWatchFace(
      String slotId,
      ParcelFileDescriptor apkFd,
      String validationToken,
      Executor executor,
      OutcomeReceiver<WatchFaceSlot, UpdateException> receiver) {
    WatchFacePushStore.get()
        .updateWatchFace(context, slotId, apkFd, validationToken, executor, receiver);
  }

  public void removeWatchFace(
      String slotId, Executor executor, OutcomeReceiver<Void, RemoveException> receiver) {
    WatchFacePushStore.get().removeWatchFace(context, slotId, executor, receiver);
  }

  public void setWatchFaceAsActive(
      String slotId, Executor executor, OutcomeReceiver<Void, SetActiveException> receiver) {
    WatchFacePushStore.get().setWatchFaceAsActive(context, slotId, executor, receiver);
  }

  public void isWatchFaceActive(
      String packageName, Executor executor, OutcomeReceiver<Boolean, IsActiveException> receiver) {
    WatchFacePushStore.get().isWatchFaceActive(context, packageName, executor, receiver);
  }
}
