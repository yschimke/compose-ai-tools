package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

/** {@link WearPlatformServices}' Health Services passive monitoring binder over its AIDL wire. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WearPlatformServicesWireTest {

  private static final String PASSIVE_DESCRIPTOR =
      "androidx.health.services.client.impl.IPassiveMonitoringApiService";
  private static final String STATUS_DESCRIPTOR =
      "androidx.health.services.client.impl.internal.IStatusCallback";
  private static final String LISTENER_SERVICE_DESCRIPTOR =
      "androidx.health.services.client.impl.IPassiveListenerService";

  @Test
  public void advertisesApiVersionFour() throws Exception {
    IBinder health = WearPlatformServices.newHealthPassiveBinder(app());
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(PASSIVE_DESCRIPTOR);
      health.transact(1, data, reply, 0);
      reply.readException();
      assertEquals(4, reply.readInt());
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  @Test
  public void bindsRegisteredListenerServiceAndUnbindsOnUnregister() throws Exception {
    Application app = app();
    ComponentName component =
        new ComponentName(app.getPackageName(), "com.example.HealthListenerService");
    RecordingBinder listenerService = new RecordingBinder();
    ShadowApplication shadowApp = Shadows.shadowOf(app);
    shadowApp.setComponentNameAndServiceForBindServiceForIntent(
        new Intent().setComponent(component), component, listenerService);

    IBinder health = WearPlatformServices.newHealthPassiveBinder(app);
    RecordingBinder status = new RecordingBinder();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(PASSIVE_DESCRIPTOR);
      data.writeInt(1);
      data.writeByteArray(serviceRequest(app.getPackageName(), component.getClassName()));
      data.writeStrongBinder(status);
      health.transact(11, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertEquals(List.of(1), status.codes);
    Intent bound = shadowApp.peekNextStartedService();
    assertNotNull(bound);
    assertEquals(component, bound.getComponent());
    assertEquals(1, shadowApp.getBoundServiceConnections().size());

    status.codes.clear();
    data = Parcel.obtain();
    reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(PASSIVE_DESCRIPTOR);
      data.writeString(app.getPackageName());
      data.writeStrongBinder(status);
      health.transact(13, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(1), status.codes);
    assertTrue(shadowApp.getBoundServiceConnections().isEmpty());
    assertEquals(1, shadowApp.getUnboundServiceConnections().size());
  }

  @Test
  public void rejectsServiceRegistrationWithoutListenerClass() throws Exception {
    Application app = app();
    IBinder health = WearPlatformServices.newHealthPassiveBinder(app);
    RecordingBinder status = new RecordingBinder();
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(PASSIVE_DESCRIPTOR);
      data.writeInt(1);
      data.writeByteArray(serviceRequest(app.getPackageName(), null));
      data.writeStrongBinder(status);
      health.transact(11, data, reply, 0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
    assertEquals(List.of(2), status.codes);
    assertTrue(Shadows.shadowOf(app).getBoundServiceConnections().isEmpty());
  }

  private static Application app() {
    return (Application) RuntimeEnvironment.getApplication();
  }

  /** A {@code PassiveListenerServiceRegistrationRequest} proto: package (1), service class (2). */
  private static byte[] serviceRequest(String packageName, String serviceClass) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeString(out, 1, packageName);
    if (serviceClass != null) writeString(out, 2, serviceClass);
    return out.toByteArray();
  }

  private static void writeString(ByteArrayOutputStream out, int field, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.write((field << 3) | 2);
    out.write(bytes.length);
    out.write(bytes, 0, bytes.length);
  }

  /** Records the transaction codes it receives. */
  private static final class RecordingBinder extends Binder {
    final List<Integer> codes = new ArrayList<>();

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
      codes.add(code);
      return true;
    }
  }
}
