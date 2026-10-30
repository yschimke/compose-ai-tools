package ee.schimke.composeai.simulator;

import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.bluetooth.le.ScanFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.UUID;
import java.util.regex.Pattern;
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
    // Robolectric's executor overload calls the Handler one, which here answers too: record the
    // request through the base shadow's Handler overload directly, and answer once.
    super.associate(request, callback, (Handler) null);
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
    List<CompanionDevices.Filter> filters = filters(request);
    BluetoothSim bluetooth = BluetoothSim.current();
    List<CompanionDevices.Device> devices =
        bluetooth == null ? new ArrayList<>() : bluetooth.inRange();
    CompanionDevices.associate(
        profile,
        filters,
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

  /**
   * The request's device filters (hidden {@code getDeviceFilters()}), read through their hidden
   * getters: {@code BluetoothDeviceFilter}'s address, name pattern and service UUIDs, {@code
   * BluetoothLeDeviceFilter}'s name pattern and {@code ScanFilter} (address, name, service UUID).
   */
  private static List<CompanionDevices.Filter> filters(AssociationRequest request) {
    List<CompanionDevices.Filter> result = new ArrayList<>();
    List<?> filters;
    try {
      filters = ReflectionHelpers.callInstanceMethod(request, "getDeviceFilters");
    } catch (RuntimeException e) {
      return result;
    }
    if (filters == null) return result;
    for (Object filter : filters) result.add(filter(filter));
    return result;
  }

  private static CompanionDevices.Filter filter(Object filter) {
    String type = filter.getClass().getSimpleName();
    if (type.equals("BluetoothDeviceFilter")) {
      List<UUID> uuids = new ArrayList<>();
      List<?> services = call(filter, "getServiceUuids");
      if (services != null) for (Object uuid : services) uuids.add(uuid(uuid));
      List<?> masks = call(filter, "getServiceUuidMasks");
      if (masks != null && !masks.isEmpty()) {
        // Masked classic UUIDs: rare, and not worth guessing at.
        for (Object mask : masks) if (mask != null) return CompanionDevices.Filter.NONE;
      }
      return CompanionDevices.Filter.classic(
          call(filter, "getAddress"), call(filter, "getNamePattern"), uuids);
    }
    if (type.equals("BluetoothLeDeviceFilter")) {
      byte[] raw = call(filter, "getRawDataFilter");
      if (raw != null && raw.length > 0) return CompanionDevices.Filter.NONE;
      ScanFilter scan = call(filter, "getScanFilter");
      Pattern name = call(filter, "getNamePattern");
      String address = null;
      UUID service = null;
      UUID mask = null;
      if (scan != null) {
        // Predicates over advertising payloads the simulator does not match on: unevaluable, so
        // they narrow the chooser to nothing rather than to everything.
        if (scan.getServiceDataUuid() != null
            || scan.getManufacturerId() >= 0
            || scan.getServiceSolicitationUuid() != null) {
          return CompanionDevices.Filter.NONE;
        }
        address = scan.getDeviceAddress();
        if (scan.getDeviceName() != null) {
          String exact = Pattern.quote(scan.getDeviceName());
          // Both the scan filter's exact name and the filter's own pattern, when it has one.
          name =
              Pattern.compile(
                  name == null
                      ? "^" + exact + "$"
                      : "^(?=[\\s\\S]*(?:" + name.pattern() + "))" + exact + "$");
        }
        if (scan.getServiceUuid() != null) {
          service = scan.getServiceUuid().getUuid();
          if (scan.getServiceUuidMask() != null) mask = scan.getServiceUuidMask().getUuid();
        }
      }
      return CompanionDevices.Filter.ble(address, name, service, mask);
    }
    return CompanionDevices.Filter.NONE;
  }

  private static UUID uuid(Object parcelUuid) {
    return UUID.fromString(String.valueOf(parcelUuid));
  }

  @SuppressWarnings("unchecked")
  private static <T> T call(Object target, String method) {
    try {
      return (T) ReflectionHelpers.callInstanceMethod(target, method);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
