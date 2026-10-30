package ee.schimke.composeai.simulator;

import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.AssociationInfoBuilder;
import org.robolectric.shadows.ShadowCompanionDeviceManager;
import org.robolectric.util.ReflectionHelpers;

/**
 * Robolectric's {@code CompanionDeviceManager} shadow, seeded from {@link CompanionDevices}: the
 * paired peer is an association, and {@code associate()} is answered (Robolectric only records the
 * request, so an app waiting for its callback waited forever). Robolectric keeps associations per
 * instance, and an activity's context has its own; every read here first brings the instance in
 * line with the device's list, so an association made through one context shows through all.
 * Added to the shadow map by {@link SimulatorSandbox}.
 */
@Implements(value = CompanionDeviceManager.class, minSdk = Build.VERSION_CODES.O)
public class SimulatorShadowCompanionDeviceManager extends ShadowCompanionDeviceManager {

  /** The device's association ids this instance holds, by id → address. */
  private final Map<Integer, String> synced = new HashMap<>();

  /** Adds the device's associations this instance lacks, drops the ones the device lost. */
  private void sync() {
    List<CompanionDevices.Association> all = CompanionDevices.associations();
    Map<Integer, CompanionDevices.Association> byId = new HashMap<>();
    for (CompanionDevices.Association a : all) byId.put(a.id, a);
    for (Map.Entry<Integer, String> e : new ArrayList<>(synced.entrySet())) {
      if (byId.containsKey(e.getKey())) continue;
      synced.remove(e.getKey());
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          super.disassociate(e.getKey());
        } else {
          super.disassociate(e.getValue());
        }
      } catch (RuntimeException gone) {
        // Already removed through this instance.
      }
    }
    for (CompanionDevices.Association a : all) {
      if (synced.containsKey(a.id)) continue;
      synced.put(a.id, a.address);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        addAssociation(info(a));
      } else {
        addAssociation(a.address);
      }
    }
  }

  private static AssociationInfo info(CompanionDevices.Association a) {
    return AssociationInfoBuilder.newBuilder()
        .setId(a.id)
        .setUserId(0)
        .setPackageName(RuntimeEnvironment.getApplication().getPackageName())
        .setDeviceMacAddress(a.address)
        .setDisplayName(a.displayName)
        .setDeviceProfile(a.profile)
        .setApprovedMs(System.currentTimeMillis())
        .setLastTimeConnectedMs(System.currentTimeMillis())
        .build();
  }

  @Implementation
  @Override
  protected List<String> getAssociations() {
    sync();
    return super.getAssociations();
  }

  @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
  @Override
  protected List<AssociationInfo> getMyAssociations() {
    sync();
    return super.getMyAssociations();
  }

  @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
  @Override
  protected List<AssociationInfo> getAllAssociations() {
    sync();
    return super.getAllAssociations();
  }

  @Implementation
  @Override
  protected void disassociate(String deviceMacAddress) {
    sync();
    CompanionDevices.removeAddress(deviceMacAddress);
    sync();
  }

  @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
  @Override
  protected void disassociate(int associationId) {
    sync();
    if (!CompanionDevices.remove(associationId)) {
      throw new IllegalArgumentException("Association id not found");
    }
    sync();
  }

  @Implementation
  @Override
  protected void associate(
      AssociationRequest request, CompanionDeviceManager.Callback callback, Handler handler) {
    super.associate(request, callback, handler);
    Handler target = handler != null ? handler : new Handler(Looper.getMainLooper());
    answer(request, callback, target::post);
  }

  @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
  @Override
  protected void associate(
      AssociationRequest request, Executor executor, CompanionDeviceManager.Callback callback) {
    super.associate(request, executor, callback);
    answer(request, callback, executor);
  }

  /**
   * Answers [request] as the chooser and the system would: {@code onAssociationCreated} with the
   * new association (API 33+), or {@code onFailure}. Before API 33 a device hands the app the
   * chooser's {@code IntentSender} ({@code onDeviceFound}) and the pick comes back as an activity
   * result, which the simulator does not drive: those requests fail, saying so.
   */
  private void answer(
      AssociationRequest request, CompanionDeviceManager.Callback callback, Executor executor) {
    String profile = profile(request);
    boolean filtered = filtered(request);
    BluetoothSim bluetooth = BluetoothSim.current();
    List<String[]> devices = bluetooth == null ? new ArrayList<>() : bluetooth.inRange();
    CompanionDevices.associate(
        profile,
        filtered,
        devices,
        chosen ->
            executor.execute(
                () -> {
                  if (chosen == null) {
                    callback.onFailure("no device chosen");
                    return;
                  }
                  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    callback.onFailure(
                        "the simulator answers associate() on API 33+ (onAssociationCreated); "
                            + chosen.address
                            + " is associated");
                    return;
                  }
                  CompanionDevices.Association created = CompanionDevices.find(chosen.address);
                  if (created == null) {
                    callback.onFailure("association lost");
                    return;
                  }
                  sync();
                  callback.onAssociationCreated(info(created));
                }));
  }

  private static String profile(AssociationRequest request) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
    return request.getDeviceProfile();
  }

  /** Whether the request names device filters (hidden {@code getDeviceFilters()}). */
  private static boolean filtered(AssociationRequest request) {
    try {
      List<?> filters = ReflectionHelpers.callInstanceMethod(request, "getDeviceFilters");
      return filters != null && !filters.isEmpty();
    } catch (RuntimeException e) {
      return false;
    }
  }
}
