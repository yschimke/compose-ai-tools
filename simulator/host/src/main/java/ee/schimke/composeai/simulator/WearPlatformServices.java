package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;

/**
 * Installs Wear OS platform services as real bindable Android system services via {@link
 * ShadowApplication#setComponentNameAndServiceForBindServiceForIntent}, scoped to each service's
 * own bind intent so unrelated binds keep Robolectric's default (null) binder.
 */
final class WearPlatformServices {

  private static final String WEATHER_PROVIDER_PACKAGE = "com.google.android.wearable.dwf.weather";
  private static final String WEATHER_PROVIDER_CLASS =
      "com.google.android.wearable.dwf.weather.service.WeatherProviderService";
  private static final String WEATHER_SERVICE_DESCRIPTOR =
      "com.google.android.wearable.libraries.weather.provider.IWeatherProviderService";
  private static final String WEATHER_GET_CALLBACK_DESCRIPTOR =
      "com.google.android.wearable.libraries.weather.provider.IGetWeatherCallback";
  private static final String WEATHER_REGISTER_CALLBACK_DESCRIPTOR =
      "com.google.android.wearable.libraries.weather.provider.IRegisterForUpdatesCallback";
  private static final String WEATHER_UNREGISTER_CALLBACK_DESCRIPTOR =
      "com.google.android.wearable.libraries.weather.provider.IUnregisterFromUpdatesCallback";

  private static final int TRANSACTION_REGISTER_FOR_UPDATES = IBinder.FIRST_CALL_TRANSACTION + 2;
  private static final int TRANSACTION_GET_WEATHER_DATA = IBinder.FIRST_CALL_TRANSACTION + 3;
  private static final int TRANSACTION_UNREGISTER_FROM_UPDATES = IBinder.FIRST_CALL_TRANSACTION + 4;

  private static final int CALLBACK_ON_SUCCESS = IBinder.FIRST_CALL_TRANSACTION + 1;
  private static final int CALLBACK_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2;

  private static final int TEMPERATURE_UNIT_CELSIUS = 1;
  private static final int TEMPERATURE_UNIT_FAHRENHEIT = 2;

  private static final String HEALTH_SERVICES_PACKAGE = "com.google.android.wearable.healthservices";
  private static final String HEALTH_PASSIVE_ACTION = "hs.passiveclient.BIND";
  private static final String HEALTH_PASSIVE_CLASS =
      "com.google.android.wearable.healthservices.PassiveMonitoringService";
  private static final String HEALTH_PASSIVE_DESCRIPTOR =
      "androidx.health.services.client.impl.IPassiveMonitoringApiService";
  private static final String HEALTH_PASSIVE_CALLBACK_DESCRIPTOR =
      "androidx.health.services.client.impl.IPassiveListenerCallback";
  private static final String HEALTH_LISTENER_SERVICE_DESCRIPTOR =
      "androidx.health.services.client.impl.IPassiveListenerService";
  private static final String HEALTH_STATUS_CALLBACK_DESCRIPTOR =
      "androidx.health.services.client.impl.internal.IStatusCallback";

  private static final int HS_TRANSACTION_GET_API_VERSION = 1;
  private static final int HS_TRANSACTION_GET_CAPABILITIES = 6;
  private static final int HS_TRANSACTION_FLUSH = 7;
  private static final int HS_TRANSACTION_REGISTER_LISTENER_SERVICE = 11;
  private static final int HS_TRANSACTION_REGISTER_LISTENER_CALLBACK = 12;
  private static final int HS_TRANSACTION_UNREGISTER_LISTENER_SERVICE = 13;
  private static final int HS_TRANSACTION_UNREGISTER_LISTENER_CALLBACK = 14;

  /** {@code IPassiveListenerCallback.onPassiveListenerEvent}. */
  private static final int LISTENER_CALLBACK_ON_EVENT = 1;

  /** {@code IPassiveListenerService.onPassiveListenerEvent}. */
  private static final int LISTENER_SERVICE_ON_EVENT = 2;

  private WearPlatformServices() {}

  static void install(Application app) {
    ShadowApplication shadowApp = Shadows.shadowOf(app);
    ComponentName weather = new ComponentName(WEATHER_PROVIDER_PACKAGE, WEATHER_PROVIDER_CLASS);
    Intent weatherIntent = new Intent().setComponent(weather);
    shadowApp.setComponentNameAndServiceForBindServiceForIntent(
        weatherIntent, weather, new WeatherProviderBinder(app));
    Navigator.registerForeignBind(weatherIntent);

    Intent healthIntent = new Intent(HEALTH_PASSIVE_ACTION).setPackage(HEALTH_SERVICES_PACKAGE);
    shadowApp.setComponentNameAndServiceForBindServiceForIntent(
        healthIntent,
        new ComponentName(HEALTH_SERVICES_PACKAGE, HEALTH_PASSIVE_CLASS),
        new HealthPassiveBinder(app));
    Navigator.registerForeignBind(healthIntent);
  }

  /**
   * The intent Robolectric matches a bind against: any bind addressed to the weather provider's
   * package, whatever action or extras it carries, resolves to its one registered component.
   */
  static Intent canonicalBindIntent(Intent intent) {
    ComponentName component = intent.getComponent();
    String pkg = component != null ? component.getPackageName() : intent.getPackage();
    if (WEATHER_PROVIDER_PACKAGE.equals(pkg)) {
      return new Intent()
          .setComponent(new ComponentName(WEATHER_PROVIDER_PACKAGE, WEATHER_PROVIDER_CLASS));
    }
    return intent;
  }

  /** The Health Services passive monitoring binder, for wire-level tests. */
  static IBinder newHealthPassiveBinder(Application app) {
    return new HealthPassiveBinder(app);
  }

  static String conditionDisplayName(int condition) {
    switch (condition) {
      case 1:
        return "Clear";
      case 2:
        return "Cloudy";
      case 3:
        return "Fog";
      case 4:
        return "Heavy rain";
      case 5:
        return "Heavy snow";
      case 6:
        return "Rain";
      case 7:
        return "Snow";
      case 8:
        return "Sunny";
      case 9:
        return "Thunderstorm";
      case 10:
        return "Sleet";
      case 11:
        return "Light snow";
      case 12:
        return "Light rain";
      case 13:
        return "Mist";
      case 14:
        return "Partly cloudy";
      case 15:
        return "Windy";
      default:
        return "Unknown";
    }
  }

  private static final class WeatherProviderBinder extends Binder {
    private final Application app;
    private final Set<ComponentName> registeredReceivers =
        Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile boolean listening;

    WeatherProviderBinder(Application app) {
      this.app = app;
      attachInterface(null, WEATHER_SERVICE_DESCRIPTOR);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(WEATHER_SERVICE_DESCRIPTOR);
        return true;
      }
      switch (code) {
        case TRANSACTION_REGISTER_FOR_UPDATES:
          {
            data.enforceInterface(WEATHER_SERVICE_DESCRIPTOR);
            if (data.readInt() != 0) {
              android.os.Bundle bundle = data.readBundle(app.getClassLoader());
              if (bundle != null) {
                ComponentName receiver = bundle.getParcelable("clientComponentName");
                if (receiver != null) {
                  registeredReceivers.add(receiver);
                  ensureWatchStateListener();
                }
              }
            }
            IBinder callback = data.readStrongBinder();
            sendUnitSuccess(callback, WEATHER_REGISTER_CALLBACK_DESCRIPTOR);
            return true;
          }
        case TRANSACTION_GET_WEATHER_DATA:
          {
            data.enforceInterface(WEATHER_SERVICE_DESCRIPTOR);
            if (data.readInt() != 0) {
              data.readBundle(app.getClassLoader());
            }
            IBinder callback = data.readStrongBinder();
            respondWithWeather(callback);
            return true;
          }
        case TRANSACTION_UNREGISTER_FROM_UPDATES:
          {
            data.enforceInterface(WEATHER_SERVICE_DESCRIPTOR);
            if (data.readInt() != 0) {
              android.os.Bundle bundle = data.readBundle(app.getClassLoader());
              if (bundle != null) {
                ComponentName receiver = bundle.getParcelable("clientComponentName");
                if (receiver != null) {
                  registeredReceivers.remove(receiver);
                }
              }
            }
            IBinder callback = data.readStrongBinder();
            sendUnitSuccess(callback, WEATHER_UNREGISTER_CALLBACK_DESCRIPTOR);
            return true;
          }
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }

    private void ensureWatchStateListener() {
      if (listening) return;
      WatchState state = WatchState.current();
      if (state == null) return;
      listening = true;
      state.onChange(
          () -> {
            List<ComponentName> targets;
            synchronized (registeredReceivers) {
              targets = new ArrayList<>(registeredReceivers);
            }
            for (ComponentName target : targets) {
              app.sendBroadcast(new Intent().setComponent(target));
            }
          });
    }

    private void respondWithWeather(IBinder callback) throws RemoteException {
      if (callback == null) {
        return;
      }
      WatchState state = WatchState.current();
      try {
        ClassLoader cl = app.getClassLoader();
        if (state == null || !state.weatherAvailable()) {
          Parcelable error = buildWeatherError(cl, 1, "Weather unavailable");
          sendParcelableCallback(
              callback, WEATHER_GET_CALLBACK_DESCRIPTOR, CALLBACK_ON_ERROR, error);
          return;
        }
        Parcelable weatherData = buildWeatherData(cl, state);
        sendParcelableCallback(
            callback, WEATHER_GET_CALLBACK_DESCRIPTOR, CALLBACK_ON_SUCCESS, weatherData);
      } catch (ReflectiveOperationException e) {
        throw new RemoteException("Failed to build weather payload: " + e.getMessage());
      }
    }

    private static void sendUnitSuccess(IBinder callback, String descriptor)
        throws RemoteException {
      if (callback == null) {
        return;
      }
      Parcel out = Parcel.obtain();
      try {
        out.writeInterfaceToken(descriptor);
        callback.transact(CALLBACK_ON_SUCCESS, out, null, IBinder.FLAG_ONEWAY);
      } finally {
        out.recycle();
      }
    }

    private static void sendParcelableCallback(
        IBinder callback, String descriptor, int code, Parcelable payload) throws RemoteException {
      Parcel out = Parcel.obtain();
      try {
        out.writeInterfaceToken(descriptor);
        out.writeInt(1);
        payload.writeToParcel(out, 0);
        callback.transact(code, out, null, IBinder.FLAG_ONEWAY);
      } finally {
        out.recycle();
      }
    }

    private static Parcelable buildWeatherError(ClassLoader cl, int errorCode, String message)
        throws ReflectiveOperationException {
      Class<?> errorClass =
          cl.loadClass("com.google.android.wearable.libraries.weather.provider.WeatherOperationError");
      Constructor<?> ctor =
          errorClass.getConstructor(int.class, PendingIntent.class, String.class);
      return (Parcelable) ctor.newInstance(errorCode, null, message);
    }

    private static Parcelable buildWeatherData(ClassLoader cl, WatchState state)
        throws ReflectiveOperationException {
      Class<?> stateClass =
          cl.loadClass("com.google.android.wearable.libraries.weather.provider.WeatherState");
      Class<?> dayClass =
          cl.loadClass("com.google.android.wearable.libraries.weather.provider.WeatherDayState");
      Class<?> attributionClass =
          cl.loadClass("com.google.android.wearable.libraries.weather.provider.WeatherAttribution");
      Class<?> dataClass =
          cl.loadClass("com.google.android.wearable.libraries.weather.provider.WeatherData");

      Constructor<?> stateCtor =
          stateClass.getConstructor(
              Integer.class,
              String.class,
              Boolean.class,
              Integer.class,
              Integer.class,
              Integer.class,
              Integer.class,
              ZonedDateTime.class,
              ZonedDateTime.class);
      Constructor<?> dayCtor =
          dayClass.getConstructor(
              ZonedDateTime.class,
              Integer.class,
              String.class,
              Integer.class,
              String.class,
              Integer.class,
              Integer.class,
              Integer.class,
              Integer.class,
              Integer.class);
      Constructor<?> attributionCtor = attributionClass.getConstructor(String.class, String.class);
      Constructor<?> dataCtor =
          dataClass.getConstructor(List.class, List.class, attributionClass, int.class);

      ZoneId zone = state.zoneId();
      ZonedDateTime now =
          ZonedDateTime.ofInstant(Instant.ofEpochMilli(state.nowMillis()), zone);

      List<Object> timeline = new ArrayList<>();
      timeline.add(
          stateCtor.newInstance(
              state.weatherCondition(),
              state.weatherConditionName(),
              state.weatherIsDay(),
              Math.round(state.weatherTemperature()),
              state.weatherPrecipitation(),
              0,
              state.weatherUvIndex(),
              now,
              now));

      for (java.util.Map.Entry<Integer, WatchState.HourlyWeather> entry :
          state.hourlyWeather().entrySet()) {
        int hourOffset = entry.getKey();
        WatchState.HourlyWeather h = entry.getValue();
        ZonedDateTime hourTime = now.plusHours(hourOffset);
        timeline.add(
            stateCtor.newInstance(
                h.condition,
                conditionDisplayName(h.condition),
                h.isDay,
                Math.round(h.temperature),
                state.weatherPrecipitation(),
                0,
                h.uvIndex,
                hourTime,
                now));
      }

      List<Object> dayForecasts = new ArrayList<>();
      if (state.dailyWeather().isEmpty()) {
        dayForecasts.add(
            dayCtor.newInstance(
                now,
                state.weatherCondition(),
                state.weatherConditionName(),
                state.weatherCondition(),
                state.weatherConditionName(),
                Math.round(state.weatherTemperatureHigh()),
                Math.round(state.weatherTemperatureLow()),
                state.weatherPrecipitation(),
                state.weatherPrecipitation(),
                state.weatherUvIndex()));
      } else {
        for (java.util.Map.Entry<Integer, WatchState.DailyWeather> entry :
            state.dailyWeather().entrySet()) {
          int dayOffset = entry.getKey();
          WatchState.DailyWeather d = entry.getValue();
          ZonedDateTime dayTime = now.plusDays(dayOffset);
          String condName = conditionDisplayName(d.conditionDay);
          String nightName = conditionDisplayName(d.conditionNight);
          dayForecasts.add(
              dayCtor.newInstance(
                  dayTime,
                  d.conditionDay,
                  condName,
                  d.conditionNight,
                  nightName,
                  Math.round(d.temperatureHigh),
                  Math.round(d.temperatureLow),
                  d.chanceOfPrecipitation,
                  d.chanceOfPrecipitationNight,
                  d.uvIndex));
        }
      }

      Object attribution = attributionCtor.newInstance("Simulator", "https://example.invalid");
      int unit = state.weatherCelsius() ? TEMPERATURE_UNIT_CELSIUS : TEMPERATURE_UNIT_FAHRENHEIT;
      return (Parcelable) dataCtor.newInstance(timeline, dayForecasts, attribution, unit);
    }
  }

  private static final class HealthPassiveBinder extends Binder {
    private final Application app;
    /** Passive listener callbacks keyed by the registering package, as AndroidX unregisters. */
    private final Map<String, IBinder> registeredCallbacks =
        Collections.synchronizedMap(new LinkedHashMap<>());

    /** The app's bound {@code PassiveListenerService}s, keyed by the registering package. */
    private final Map<String, ServiceListener> registeredServices =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile boolean listening;

    HealthPassiveBinder(Application app) {
      this.app = app;
      attachInterface(null, HEALTH_PASSIVE_DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(HEALTH_PASSIVE_DESCRIPTOR);
        return true;
      }
      switch (code) {
        case HS_TRANSACTION_GET_API_VERSION:
          {
            data.enforceInterface(HEALTH_PASSIVE_DESCRIPTOR);
            reply.writeNoException();
            // AndroidX gates set/clear of passive listener services and callbacks on >= 4.
            reply.writeInt(4);
            return true;
          }
        case HS_TRANSACTION_GET_CAPABILITIES:
          {
            data.enforceInterface(HEALTH_PASSIVE_DESCRIPTOR);
            if (data.readInt() != 0) {
              data.createByteArray();
            }
            Parcelable resp = buildCapabilitiesResponse();
            reply.writeNoException();
            if (resp != null) {
              reply.writeInt(1);
              resp.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              reply.writeInt(0);
            }
            return true;
          }
        case HS_TRANSACTION_REGISTER_LISTENER_CALLBACK:
          {
            data.enforceInterface(HEALTH_PASSIVE_DESCRIPTOR);
            String packageName = null;
            if (data.readInt() != 0) {
              packageName = HealthRequestProto.readPackageName(data.createByteArray());
            }
            if (packageName == null) packageName = app.getPackageName();
            IBinder listener = data.readStrongBinder();
            IBinder statusCallback = data.readStrongBinder();
            if (listener != null) {
              registeredCallbacks.put(packageName, listener);
              ensureWatchStateListener();
            }
            reply.writeNoException();
            sendStatusSuccess(statusCallback);
            if (listener != null) {
              pushUpdate(listener, HEALTH_PASSIVE_CALLBACK_DESCRIPTOR, LISTENER_CALLBACK_ON_EVENT);
            }
            return true;
          }
        case HS_TRANSACTION_REGISTER_LISTENER_SERVICE:
          {
            data.enforceInterface(HEALTH_PASSIVE_DESCRIPTOR);
            byte[] request = data.readInt() != 0 ? data.createByteArray() : null;
            IBinder statusCallback = data.readStrongBinder();
            String packageName = HealthRequestProto.readPackageName(request);
            if (packageName == null) packageName = app.getPackageName();
            String serviceClass = HealthRequestProto.readListenerServiceClass(request);
            reply.writeNoException();
            if (serviceClass == null) {
              sendStatusError(statusCallback, "Missing listener service class");
              return true;
            }
            registerService(packageName, new ComponentName(packageName, serviceClass));
            sendStatusSuccess(statusCallback);
            return true;
          }
        case HS_TRANSACTION_UNREGISTER_LISTENER_CALLBACK:
        case HS_TRANSACTION_UNREGISTER_LISTENER_SERVICE:
          {
            data.enforceInterface(HEALTH_PASSIVE_DESCRIPTOR);
            String packageName = data.readString();
            IBinder statusCallback = data.readStrongBinder();
            String key = packageName != null ? packageName : app.getPackageName();
            if (code == HS_TRANSACTION_UNREGISTER_LISTENER_CALLBACK) {
              registeredCallbacks.remove(key);
            } else {
              unregisterService(key);
            }
            reply.writeNoException();
            sendStatusSuccess(statusCallback);
            return true;
          }
        case HS_TRANSACTION_FLUSH:
          {
            data.enforceInterface(HEALTH_PASSIVE_DESCRIPTOR);
            if (data.readInt() != 0) {
              data.createByteArray();
            }
            IBinder statusCallback = data.readStrongBinder();
            reply.writeNoException();
            sendStatusSuccess(statusCallback);
            pushAllUpdates();
            return true;
          }
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }

    private void ensureWatchStateListener() {
      if (listening) return;
      WatchState state = WatchState.current();
      if (state == null) return;
      listening = true;
      state.onChange(this::pushAllUpdates);
    }

    private void pushAllUpdates() {
      List<IBinder> targets;
      synchronized (registeredCallbacks) {
        targets = new ArrayList<>(registeredCallbacks.values());
      }
      for (IBinder target : targets) {
        try {
          pushUpdate(target, HEALTH_PASSIVE_CALLBACK_DESCRIPTOR, LISTENER_CALLBACK_ON_EVENT);
        } catch (RemoteException ignored) {
          registeredCallbacks.values().remove(target);
        }
      }
      List<ServiceListener> services;
      synchronized (registeredServices) {
        services = new ArrayList<>(registeredServices.values());
      }
      for (ServiceListener service : services) {
        service.push();
      }
    }

    /**
     * Binds the app's {@code PassiveListenerService} the way Health Services does, replacing any
     * earlier registration from [packageName], and delivers the current metrics once connected.
     */
    private void registerService(String packageName, ComponentName component) {
      unregisterService(packageName);
      ServiceListener listener = new ServiceListener(component);
      registeredServices.put(packageName, listener);
      ensureWatchStateListener();
      boolean bound =
          app.bindService(
              new Intent().setComponent(component), listener, Context.BIND_AUTO_CREATE);
      if (!bound) {
        registeredServices.remove(packageName, listener);
      }
    }

    private void unregisterService(String packageName) {
      ServiceListener previous = registeredServices.remove(packageName);
      if (previous != null) {
        try {
          app.unbindService(previous);
        } catch (IllegalArgumentException ignored) {
          // Never connected.
        }
      }
    }

    /** A bound {@code PassiveListenerService}, which receives updates over its own AIDL. */
    private final class ServiceListener implements ServiceConnection {
      final ComponentName component;
      volatile IBinder binder;

      ServiceListener(ComponentName component) {
        this.component = component;
      }

      @Override
      public void onServiceConnected(ComponentName name, IBinder service) {
        binder = service;
        push();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        binder = null;
      }

      void push() {
        IBinder target = binder;
        if (target == null) return;
        try {
          pushUpdate(target, HEALTH_LISTENER_SERVICE_DESCRIPTOR, LISTENER_SERVICE_ON_EVENT);
        } catch (RemoteException e) {
          binder = null;
        }
      }
    }

    private void pushUpdate(IBinder listener, String descriptor, int transaction)
        throws RemoteException {
      WatchState state = WatchState.current();
      if (state == null) return;
      try {
        ClassLoader cl = app.getClassLoader();
        Class<?> dataPointsClass =
            cl.loadClass("androidx.health.services.client.data.DataPoints");
        Class<?> containerClass =
            cl.loadClass("androidx.health.services.client.data.DataPointContainer");
        Class<?> updateClass =
            cl.loadClass("androidx.health.services.client.data.PassiveMonitoringUpdate");
        Class<?> updateRespClass =
            cl.loadClass(
                "androidx.health.services.client.impl.response.PassiveMonitoringUpdateResponse");
        Class<?> eventClass =
            cl.loadClass("androidx.health.services.client.impl.event.PassiveListenerEvent");

        Duration zero = Duration.ZERO;
        Duration elapsed = Duration.ofHours(1);
        List<Object> points = new ArrayList<>();
        points.add(
            dataPointsClass
                .getMethod("heartRate", double.class, Duration.class)
                .invoke(null, (double) state.heartRate(), elapsed));
        points.add(
            dataPointsClass
                .getMethod("dailySteps", long.class, Duration.class, Duration.class)
                .invoke(null, (long) state.steps(), zero, elapsed));
        points.add(
            dataPointsClass
                .getMethod("dailyCalories", double.class, Duration.class, Duration.class)
                .invoke(null, (double) state.calories(), zero, elapsed));
        points.add(
            dataPointsClass
                .getMethod("dailyDistance", double.class, Duration.class, Duration.class)
                .invoke(null, (double) state.distanceMeters(), zero, elapsed));
        points.add(
            dataPointsClass
                .getMethod("dailyFloors", double.class, Duration.class, Duration.class)
                .invoke(null, (double) state.floors(), zero, elapsed));

        Object container = containerClass.getConstructor(List.class).newInstance(points);
        Object update =
            updateClass
                .getConstructor(containerClass, List.class)
                .newInstance(container, Collections.emptyList());
        Object updateResp = updateRespClass.getConstructor(updateClass).newInstance(update);
        Method createEvent =
            eventClass.getMethod("createPassiveUpdateResponse", updateRespClass);
        Parcelable event = (Parcelable) createEvent.invoke(null, updateResp);

        Parcel out = Parcel.obtain();
        try {
          out.writeInterfaceToken(descriptor);
          out.writeInt(1);
          event.writeToParcel(out, 0);
          listener.transact(transaction, out, null, IBinder.FLAG_ONEWAY);
        } finally {
          out.recycle();
        }
      } catch (ReflectiveOperationException e) {
        throw new RemoteException("Failed to build health update: " + e.getMessage());
      }
    }

    private Parcelable buildCapabilitiesResponse() throws RemoteException {
      try {
        ClassLoader cl = app.getClassLoader();
        Class<?> dataTypeClass = cl.loadClass("androidx.health.services.client.data.DataType");
        Class<?> capsClass =
            cl.loadClass(
                "androidx.health.services.client.data.PassiveMonitoringCapabilities");
        Class<?> respClass =
            cl.loadClass(
                "androidx.health.services.client.impl.response"
                    + ".PassiveMonitoringCapabilitiesResponse");

        Set<Object> supported = new LinkedHashSet<>();
        supported.add(dataTypeClass.getField("HEART_RATE_BPM").get(null));
        supported.add(dataTypeClass.getField("STEPS_DAILY").get(null));
        supported.add(dataTypeClass.getField("CALORIES_DAILY").get(null));
        supported.add(dataTypeClass.getField("DISTANCE_DAILY").get(null));
        supported.add(dataTypeClass.getField("FLOORS_DAILY").get(null));

        Object caps =
            capsClass
                .getConstructor(Set.class, Set.class, Set.class, Set.class)
                .newInstance(
                    supported,
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptySet());
        return (Parcelable) respClass.getConstructor(capsClass).newInstance(caps);
      } catch (ReflectiveOperationException e) {
        throw new RemoteException("Failed to build health capabilities: " + e.getMessage());
      }
    }

    /** {@code IStatusCallback.onFailure(String)}. */
    private static void sendStatusError(IBinder statusCallback, String message)
        throws RemoteException {
      if (statusCallback == null) return;
      Parcel out = Parcel.obtain();
      try {
        out.writeInterfaceToken(HEALTH_STATUS_CALLBACK_DESCRIPTOR);
        out.writeString(message);
        statusCallback.transact(2, out, null, IBinder.FLAG_ONEWAY);
      } finally {
        out.recycle();
      }
    }

    private static void sendStatusSuccess(IBinder statusCallback) throws RemoteException {
      if (statusCallback == null) return;
      Parcel out = Parcel.obtain();
      Parcel reply = Parcel.obtain();
      try {
        out.writeInterfaceToken(HEALTH_STATUS_CALLBACK_DESCRIPTOR);
        statusCallback.transact(1, out, reply, 0);
        reply.readException();
      } finally {
        reply.recycle();
        out.recycle();
      }
    }
  }
}
