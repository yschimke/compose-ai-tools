package com.google.wear.services.ambient;

import android.app.Activity;
import android.content.Context;
import ee.schimke.composeai.simulator.AmbientClients;

/**
 * The Wear SDK's ambient service ({@code wear-sdk}), which Wear Compose's {@code
 * rememberAmbientModeManager()} asks for through {@code Sdk.getWearManager} and uses with no
 * fallback: without it the composable fails as soon as it is remembered. The simulator's copy
 * registers each controller with {@link AmbientClients}, so the screen's ambient transitions reach
 * the app as they do through the shared library's {@code WearableActivityController}.
 *
 * <p>Only the surface Wear Compose uses is here. APKs carry a stub of this package (with no {@code
 * createController}); it is dropped when the APK is converted, as the device's library would come
 * first.
 */
public final class AmbientManager {

  /** The one instance {@code Sdk.getWearManager} hands out. */
  public static final AmbientManager INSTANCE = new AmbientManager();

  private AmbientManager() {}

  /** Starts ambient support for the activity in [options]; destroy it to stop. */
  public Controller createController(Context context, AmbientOptions options) {
    Controller controller = new Controller(options);
    AmbientClients.register(controller.client);
    return controller;
  }

  /** What the component hears. */
  public interface AmbientComponentListener {
    void onEnterAmbient(ConfigurationDetails details, boolean isAmbientOffloadEnabled);

    void onExitAmbient();

    void onUpdateAmbient();
  }

  /** Transitions into and out of ambient, which the simulator does not animate. */
  public interface AmbientTransitionListener {}

  /** The display's ambient constraints. */
  public interface ConfigurationDetails {
    boolean isBurnInProtectionEnabled();

    boolean isLowBitDepthEnabled();
  }

  /** One component's registration. */
  public static final class Controller {
    private final AmbientOptions options;
    private boolean ambient;
    final AmbientClients.Client client =
        new AmbientClients.Client() {
          @Override
          public Activity activity() {
            return options.activity;
          }

          @Override
          public boolean resumed() {
            return options.registry == null || options.registry.resumed;
          }

          @Override
          public String api() {
            return "AmbientManager";
          }

          @Override
          public boolean ambient() {
            return ambient;
          }

          @Override
          public void enter(boolean burnIn, boolean lowBit) {
            ambient = true;
            if (options.listener == null) return;
            options.listener.onEnterAmbient(
                new ConfigurationDetails() {
                  @Override
                  public boolean isBurnInProtectionEnabled() {
                    return burnIn;
                  }

                  @Override
                  public boolean isLowBitDepthEnabled() {
                    return lowBit;
                  }
                },
                false);
          }

          @Override
          public void update() {
            if (options.listener != null) options.listener.onUpdateAmbient();
          }

          @Override
          public void exit() {
            ambient = false;
            if (options.listener != null) options.listener.onExitAmbient();
          }
        };

    Controller(AmbientOptions options) {
      this.options = options;
    }

    public void destroy() {
      AmbientClients.unregister(client);
    }
  }
}
