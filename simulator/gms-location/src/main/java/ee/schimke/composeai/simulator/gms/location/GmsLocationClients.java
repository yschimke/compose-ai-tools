package ee.schimke.composeai.simulator.gms.location;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dynamic proxy factories for Play services Location's four client interfaces ({@code
 * FusedLocationProviderClient}, {@code GeofencingClient}, {@code SettingsClient}, and {@code
 * ActivityRecognitionClient}).
 *
 * <p>Uses {@link Proxy} against the app's own class loader so apps on any {@code
 * play-services-location} 21.x release run without compile-time GMS stubs.
 */
public final class GmsLocationClients {

  static final String FUSED_CLIENT = "com.google.android.gms.location.FusedLocationProviderClient";
  static final String GEOFENCING_CLIENT = "com.google.android.gms.location.GeofencingClient";
  static final String SETTINGS_CLIENT = "com.google.android.gms.location.SettingsClient";
  static final String ACTIVITY_CLIENT = "com.google.android.gms.location.ActivityRecognitionClient";

  private GmsLocationClients() {}

  /** Matches any client proxy created by this factory (for {@code GoogleApiAvailability}). */
  public static boolean isLocationClient(Object api) {
    if (api == null || !Proxy.isProxyClass(api.getClass())) return false;
    InvocationHandler handler = Proxy.getInvocationHandler(api);
    return handler instanceof LocationProxyHandler;
  }

  public static Object fusedClient(Context context, ClassLoader loader) {
    GmsLocation engine = GmsLocation.getOrCreate(context);
    return newProxy(loader, FUSED_CLIENT, new FusedHandler(engine, loader));
  }

  public static Object geofencingClient(Context context, ClassLoader loader) {
    GmsLocation engine = GmsLocation.getOrCreate(context);
    return newProxy(loader, GEOFENCING_CLIENT, new GeofencingHandler(engine, loader));
  }

  public static Object settingsClient(Context context, ClassLoader loader) {
    GmsLocation engine = GmsLocation.getOrCreate(context);
    return newProxy(loader, SETTINGS_CLIENT, new SettingsHandler(engine, loader));
  }

  public static Object activityRecognitionClient(Context context, ClassLoader loader) {
    GmsLocation engine = GmsLocation.getOrCreate(context);
    return newProxy(loader, ACTIVITY_CLIENT, new ActivityHandler(engine, loader));
  }

  private static Object newProxy(
      ClassLoader loader, String interfaceName, LocationProxyHandler handler) {
    try {
      Class<?> iface = Class.forName(interfaceName, false, loader);
      if (!iface.isInterface()) {
        throw new IllegalStateException(
            interfaceName + " is not an interface (requires play-services-location 21+)");
      }
      return Proxy.newProxyInstance(loader, new Class<?>[] {iface}, handler);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Play services Location class not found: " + interfaceName, e);
    }
  }

  private abstract static class LocationProxyHandler implements InvocationHandler {
    final GmsLocation engine;
    final ClassLoader loader;
    private final String label;

    LocationProxyHandler(GmsLocation engine, ClassLoader loader, String label) {
      this.engine = engine;
      this.loader = loader;
      this.label = label;
    }

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();
      if ("toString".equals(name) && method.getParameterCount() == 0) {
        return "Simulated" + label;
      }
      if ("hashCode".equals(name) && method.getParameterCount() == 0) {
        return System.identityHashCode(proxy);
      }
      if ("equals".equals(name) && method.getParameterCount() == 1) {
        return proxy == args[0];
      }
      if ("getApiKey".equals(name) && method.getParameterCount() == 0) {
        return null;
      }
      Exception gmsErr = GmsLocationTasks.playServicesError(loader);
      if (gmsErr != null && returnsTask(method)) {
        return GmsLocationTasks.deferredFailure(loader, engine.mainExecutor(), gmsErr);
      }
      return handle(name, method, args == null ? new Object[0] : args);
    }

    abstract Object handle(String name, Method method, Object[] args);

    Object ok(Object value) {
      return GmsLocationTasks.deferredResult(loader, engine.mainExecutor(), value);
    }

    Object fail(Exception error) {
      return GmsLocationTasks.deferredFailure(loader, engine.mainExecutor(), error);
    }

    private static boolean returnsTask(Method method) {
      return "com.google.android.gms.tasks.Task".equals(method.getReturnType().getName());
    }
  }

  private static final class FusedHandler extends LocationProxyHandler {
    FusedHandler(GmsLocation engine, ClassLoader loader) {
      super(engine, loader, "FusedLocationProviderClient");
    }

    @Override
    Object handle(String name, Method method, Object[] args) {
      switch (name) {
        case "getLastLocation":
          return ok(engine.effectiveLastLocation());
        case "getCurrentLocation":
          return handleGetCurrentLocation(args);
        case "getLocationAvailability":
          return ok(GmsLocation.createLocationAvailability(loader, engine.isLocationAvailable()));
        case "requestLocationUpdates":
          return handleRequestUpdates(args);
        case "removeLocationUpdates":
          return handleRemoveUpdates(args);
        case "flushLocations":
          engine.flushLocations();
          return ok(null);
        case "setMockMode":
          if (args.length > 0 && args[0] instanceof Boolean) {
            engine.setMockMode((Boolean) args[0]);
          }
          return ok(null);
        case "setMockLocation":
          if (args.length > 0 && args[0] instanceof Location) {
            engine.setMockLocation((Location) args[0]);
          }
          return ok(null);
        default:
          return ok(null);
      }
    }

    private Object handleGetCurrentLocation(Object[] args) {
      Location cached = engine.effectiveLastLocation();
      if (cached != null && isFreshEnough(cached, args.length > 0 ? args[0] : null)) {
        return ok(cached);
      }
      try {
        Class<?> tcsClass =
            Class.forName("com.google.android.gms.tasks.TaskCompletionSource", false, loader);
        Object token = args.length >= 2 ? args[1] : null;
        Object tcs = newTaskCompletionSource(tcsClass, token, loader);
        Method trySetResult = tcsClass.getMethod("trySetResult", Object.class);
        Object task = tcsClass.getMethod("getTask").invoke(tcs);
        java.util.function.Consumer<Location> waiter =
            fix ->
                engine
                    .mainExecutor()
                    .execute(
                        () -> {
                          try {
                            trySetResult.invoke(tcs, fix);
                          } catch (ReflectiveOperationException ignored) {
                          }
                        });
        engine.awaitNextLocation(waiter);
        if (token != null) {
          try {
            Class<?> listenerIface =
                Class.forName("com.google.android.gms.tasks.OnTokenCanceledListener", false, loader);
            Object cancelListener =
                Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {listenerIface},
                    (p, m, a) -> {
                      if ("onCanceled".equals(m.getName())) {
                        engine.cancelPendingLocation(waiter);
                      }
                      return null;
                    });
            token
                .getClass()
                .getMethod("onCanceledRequested", listenerIface)
                .invoke(token, cancelListener);
          } catch (ReflectiveOperationException ignored) {
          }
        }
        return task;
      } catch (ReflectiveOperationException e) {
        return ok(null);
      }
    }

    private static boolean isFreshEnough(Location cached, Object request) {
      if (request == null || request instanceof Integer) return true;
      try {
        Method m = request.getClass().getMethod("getMaxUpdateAgeMillis");
        m.setAccessible(true);
        Object val = m.invoke(request);
        if (val instanceof Number) {
          long maxAgeMs = ((Number) val).longValue();
          if (maxAgeMs <= 0L) return false;
          long fixElapsedNs = cached.getElapsedRealtimeNanos();
          long nowElapsedNs = android.os.SystemClock.elapsedRealtimeNanos();
          if (fixElapsedNs > 0L && nowElapsedNs >= fixElapsedNs) {
            return (nowElapsedNs - fixElapsedNs) / 1_000_000L <= maxAgeMs;
          }
          long ageMs = System.currentTimeMillis() - cached.getTime();
          return ageMs <= maxAgeMs;
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      return true;
    }

    private static Object newTaskCompletionSource(
        Class<?> tcsClass, Object token, ClassLoader loader) throws ReflectiveOperationException {
      if (token != null) {
        try {
          Class<?> tokenCls =
              Class.forName("com.google.android.gms.tasks.CancellationToken", false, loader);
          return tcsClass.getDeclaredConstructor(tokenCls).newInstance(token);
        } catch (ReflectiveOperationException ignored) {
        }
      }
      return tcsClass.getDeclaredConstructor().newInstance();
    }

    private Object handleRequestUpdates(Object[] args) {
      Executor exec = engine.mainExecutor();
      for (Object arg : args) {
        if (arg instanceof Executor) {
          exec = (Executor) arg;
        } else if (arg instanceof Looper) {
          Handler h = new Handler((Looper) arg);
          exec = h::post;
        }
      }
      for (Object arg : args) {
        if (arg == null) continue;
        if (arg instanceof PendingIntent) {
          engine.registerLocationPendingIntent((PendingIntent) arg);
          return ok(null);
        }
        if (isLocationCallback(arg)) {
          engine.registerLocationCallback(arg, exec);
          return ok(null);
        }
        if (isLocationListener(arg)) {
          engine.registerLocationListener(arg, exec);
          return ok(null);
        }
      }
      return ok(null);
    }

    private Object handleRemoveUpdates(Object[] args) {
      for (Object arg : args) {
        if (arg == null) continue;
        if (arg instanceof PendingIntent) {
          engine.unregisterLocationPendingIntent((PendingIntent) arg);
        } else if (isLocationCallback(arg)) {
          engine.unregisterLocationCallback(arg);
        } else if (isLocationListener(arg)) {
          engine.unregisterLocationListener(arg);
        }
      }
      return ok(null);
    }

    private static boolean isLocationCallback(Object obj) {
      for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
        if ("com.google.android.gms.location.LocationCallback".equals(c.getName())) return true;
      }
      return false;
    }

    private static boolean isLocationListener(Object obj) {
      for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
        for (Class<?> iface : c.getInterfaces()) {
          if ("com.google.android.gms.location.LocationListener".equals(iface.getName())) {
            return true;
          }
        }
      }
      return false;
    }
  }

  private static final class GeofencingHandler extends LocationProxyHandler {
    GeofencingHandler(GmsLocation engine, ClassLoader loader) {
      super(engine, loader, "GeofencingClient");
    }

    @Override
    @SuppressWarnings("unchecked")
    Object handle(String name, Method method, Object[] args) {
      switch (name) {
        case "addGeofences":
          if (args.length >= 2 && args[1] instanceof PendingIntent) {
            engine.addGeofences(args[0], (PendingIntent) args[1]);
          }
          return ok(null);
        case "removeGeofences":
          if (args.length >= 1) {
            if (args[0] instanceof PendingIntent) {
              engine.removeGeofencesByPendingIntent((PendingIntent) args[0]);
            } else if (args[0] instanceof List<?>) {
              engine.removeGeofencesByIds((List<String>) args[0]);
            }
          }
          return ok(null);
        default:
          return ok(null);
      }
    }
  }

  private static final class SettingsHandler extends LocationProxyHandler {
    SettingsHandler(GmsLocation engine, ClassLoader loader) {
      super(engine, loader, "SettingsClient");
    }

    @Override
    Object handle(String name, Method method, Object[] args) {
      if ("checkLocationSettings".equals(name)) {
        if (!engine.isSettingsEnabled()) {
          PendingIntent resolution = null;
          if (engine.context() != null) {
            resolution =
                PendingIntent.getActivity(
                    engine.context(),
                    0,
                    new Intent("android.settings.LOCATION_SOURCE_SETTINGS"),
                    PendingIntent.FLAG_IMMUTABLE);
          }
          return fail(
              GmsLocationTasks.resolvableApiException(
                  loader,
                  GmsLocationTasks.STATUS_RESOLUTION_REQUIRED,
                  "Location settings are not satisfied",
                  resolution));
        }
        return ok(GmsLocation.createLocationSettingsResponse(loader, true));
      }
      return ok(null);
    }
  }

  private static final class ActivityHandler extends LocationProxyHandler {
    ActivityHandler(GmsLocation engine, ClassLoader loader) {
      super(engine, loader, "ActivityRecognitionClient");
    }

    @Override
    Object handle(String name, Method method, Object[] args) {
      PendingIntent pi = findPendingIntent(args);
      switch (name) {
        case "requestActivityUpdates":
          engine.registerActivityUpdates(pi);
          return ok(null);
        case "removeActivityUpdates":
          engine.unregisterActivityUpdates(pi);
          return ok(null);
        case "requestActivityTransitionUpdates":
          engine.registerActivityTransitions(args.length > 0 ? args[0] : null, pi);
          return ok(null);
        case "removeActivityTransitionUpdates":
          engine.unregisterActivityTransitions(pi);
          return ok(null);
        default:
          return ok(null);
      }
    }

    private static PendingIntent findPendingIntent(Object[] args) {
      for (Object arg : args) {
        if (arg instanceof PendingIntent) return (PendingIntent) arg;
      }
      return null;
    }
  }
}
