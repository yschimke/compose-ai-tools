package ee.schimke.composeai.simulator.gms.location;

import android.app.PendingIntent;
import ee.schimke.composeai.simulator.PlayServices;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Reflection bridge for Play services' {@code Task}, {@code TaskCompletionSource}, {@code Status},
 * {@code ApiException}, and {@code ResolvableApiException} loaded from the app's class loader.
 *
 * <p>Avoids compile-time coupling to any specific {@code play-services-*} artifact version so apps
 * bundling any 18.x–21.x client library work unmodified.
 */
final class GmsLocationTasks {

  static final int STATUS_RESOLUTION_REQUIRED = 6;

  private GmsLocationTasks() {}

  /** Returns a {@code Task<T>} that succeeds with {@code value} on {@code main}. */
  static Object deferredResult(ClassLoader loader, Executor main, Object value) {
    try {
      Class<?> tcsClass =
          Class.forName("com.google.android.gms.tasks.TaskCompletionSource", false, loader);
      Object tcs = tcsClass.getDeclaredConstructor().newInstance();
      Method trySetResult = tcsClass.getMethod("trySetResult", Object.class);
      Method getTask = tcsClass.getMethod("getTask");
      Object task = getTask.invoke(tcs);
      main.execute(
          () -> {
            try {
              trySetResult.invoke(tcs, value);
            } catch (ReflectiveOperationException ignored) {
            }
          });
      return task;
    } catch (ReflectiveOperationException e) {
      return immediateResult(loader, value);
    }
  }

  /** Returns a {@code Task<T>} that fails with {@code error} on {@code main}. */
  static Object deferredFailure(ClassLoader loader, Executor main, Exception error) {
    try {
      Class<?> tcsClass =
          Class.forName("com.google.android.gms.tasks.TaskCompletionSource", false, loader);
      Object tcs = tcsClass.getDeclaredConstructor().newInstance();
      Method trySetException = tcsClass.getMethod("trySetException", Exception.class);
      Method getTask = tcsClass.getMethod("getTask");
      Object task = getTask.invoke(tcs);
      main.execute(
          () -> {
            try {
              trySetException.invoke(tcs, error);
            } catch (ReflectiveOperationException ignored) {
            }
          });
      return task;
    } catch (ReflectiveOperationException e) {
      return immediateFailure(loader, error);
    }
  }

  static Object immediateResult(ClassLoader loader, Object value) {
    try {
      return Class.forName("com.google.android.gms.tasks.Tasks", false, loader)
          .getMethod("forResult", Object.class)
          .invoke(null, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Play services Tasks.forResult failed", e);
    }
  }

  static Object immediateFailure(ClassLoader loader, Exception error) {
    try {
      return Class.forName("com.google.android.gms.tasks.Tasks", false, loader)
          .getMethod("forException", Exception.class)
          .invoke(null, error);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Play services Tasks.forException failed", e);
    }
  }

  /** Constructs {@code new ApiException(new Status(statusCode, message))}. */
  static Exception apiException(ClassLoader loader, int statusCode, String message) {
    try {
      Class<?> statusClass =
          Class.forName("com.google.android.gms.common.api.Status", false, loader);
      Object status = newStatus(statusClass, statusCode, message, null);
      Constructor<?> ctor =
          Class.forName("com.google.android.gms.common.api.ApiException", false, loader)
              .getDeclaredConstructor(statusClass);
      ctor.setAccessible(true);
      return (Exception) ctor.newInstance(status);
    } catch (ReflectiveOperationException e) {
      return new IllegalStateException("GMS ApiException(" + statusCode + "): " + message, e);
    }
  }

  /** Constructs {@code new ResolvableApiException(new Status(RESOLUTION_REQUIRED, msg, pi))}. */
  static Exception resolvableApiException(
      ClassLoader loader, int statusCode, String message, PendingIntent resolution) {
    try {
      Class<?> statusClass =
          Class.forName("com.google.android.gms.common.api.Status", false, loader);
      Object status = newStatus(statusClass, statusCode, message, resolution);
      Constructor<?> ctor =
          Class.forName("com.google.android.gms.common.api.ResolvableApiException", false, loader)
              .getDeclaredConstructor(statusClass);
      ctor.setAccessible(true);
      return (Exception) ctor.newInstance(status);
    } catch (ReflectiveOperationException e) {
      return apiException(loader, statusCode, message);
    }
  }

  /** Returns an {@code ApiException} if Play Services is missing/disabled/outdated, else null. */
  static Exception playServicesError(ClassLoader loader) {
    int status = PlayServices.status(0);
    if (status == PlayServices.SUCCESS) return null;
    return apiException(loader, status, "Google Play services unavailable: " + status);
  }

  private static Object newStatus(
      Class<?> statusClass, int statusCode, String message, PendingIntent resolution)
      throws ReflectiveOperationException {
    if (resolution != null) {
      try {
        Constructor<?> ctor =
            statusClass.getDeclaredConstructor(int.class, String.class, PendingIntent.class);
        ctor.setAccessible(true);
        return ctor.newInstance(statusCode, message, resolution);
      } catch (NoSuchMethodException ignored) {
      }
    }
    if (message != null) {
      try {
        Constructor<?> ctor = statusClass.getDeclaredConstructor(int.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(statusCode, message);
      } catch (NoSuchMethodException ignored) {
      }
    }
    Constructor<?> ctor = statusClass.getDeclaredConstructor(int.class);
    ctor.setAccessible(true);
    return ctor.newInstance(statusCode);
  }
}
