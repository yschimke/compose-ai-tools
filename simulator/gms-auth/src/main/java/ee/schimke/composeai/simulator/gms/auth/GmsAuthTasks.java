package ee.schimke.composeai.simulator.gms.auth;

import ee.schimke.composeai.simulator.PlayServices;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/** Reflection bridge for Play services' {@code Task}, {@code Status}, and {@code ApiException}. */
final class GmsAuthTasks {

  private GmsAuthTasks() {}

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

  static Object status(ClassLoader loader, int code, String message)
      throws ReflectiveOperationException {
    Class<?> statusClass =
        Class.forName("com.google.android.gms.common.api.Status", false, loader);
    if (message != null) {
      try {
        Constructor<?> ctor = statusClass.getDeclaredConstructor(int.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(code, message);
      } catch (NoSuchMethodException ignored) {
      }
    }
    Constructor<?> ctor = statusClass.getDeclaredConstructor(int.class);
    ctor.setAccessible(true);
    return ctor.newInstance(code);
  }

  static Exception apiException(ClassLoader loader, int code, String message) {
    try {
      Object st = status(loader, code, message);
      Constructor<?> ctor =
          Class.forName("com.google.android.gms.common.api.ApiException", false, loader)
              .getDeclaredConstructor(st.getClass());
      ctor.setAccessible(true);
      return (Exception) ctor.newInstance(st);
    } catch (ReflectiveOperationException e) {
      return new IllegalStateException("GMS Auth ApiException(" + code + "): " + message, e);
    }
  }

  static Exception playServicesError(ClassLoader loader) {
    int status = PlayServices.status(0);
    if (status == PlayServices.SUCCESS) return null;
    return apiException(loader, status, "Google Play services unavailable: " + status);
  }
}
