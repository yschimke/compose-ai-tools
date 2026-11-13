package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.Test;
import org.robolectric.interceptors.AndroidInterceptors;
import org.robolectric.internal.bytecode.ClassDetails;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.Interceptors;

/** Locks the optional Wear SDK seam used by the live APK simulator, not the daemon renderer. */
public class SimulatorSandboxWearSdkTest {

  @Test
  public void instrumentsAppBundledWearSdkSignatureClasses() throws Exception {
    InstrumentationConfiguration configuration = instrumentationConfiguration();
    assertTrue(configuration.shouldInstrument(new ClassDetails(wearHapticSignatureBytes())));
    org.junit.Assert.assertFalse(
        configuration.shouldInstrument(
            new ClassDetails(
                emptyClassBytes("com/google/wear/watchface/runtime/RuntimeControlService"))));
    org.junit.Assert.assertFalse(
        configuration.shouldInstrument(
            new ClassDetails(
                emptyClassBytes("android/support/wearable/complications/ComplicationData"))));
  }

  @Test
  public void mapsWearScrollHapticsToFrameworkFeedback() {
    assertEquals(
        android.view.HapticFeedbackConstants.KEYBOARD_TAP,
        ShadowWearHapticFeedbackConstants.getScrollItemFocus());
    assertEquals(
        android.view.HapticFeedbackConstants.REJECT,
        ShadowWearHapticFeedbackConstants.getScrollLimit());
    assertEquals(
        android.view.HapticFeedbackConstants.CLOCK_TICK,
        ShadowWearHapticFeedbackConstants.getScrollTick());
  }

  private static InstrumentationConfiguration instrumentationConfiguration() throws Exception {
    Method method =
        SimulatorSandbox.class.getDeclaredMethod("instrumentationConfiguration", Interceptors.class);
    method.setAccessible(true);
    return (InstrumentationConfiguration)
        method.invoke(null, new Interceptors(AndroidInterceptors.all()));
  }

  private static byte[] wearHapticSignatureBytes() throws IOException {
    try (var input =
        SimulatorSandboxWearSdkTest.class.getResourceAsStream(
            "/com/google/wear/input/WearHapticFeedbackConstants.class")) {
      if (input == null) throw new AssertionError("missing test Wear SDK signature class");
      return input.readAllBytes();
    }
  }

  private static byte[] emptyClassBytes(String internalName) {
    org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
    cw.visit(
        org.objectweb.asm.Opcodes.V1_8,
        org.objectweb.asm.Opcodes.ACC_PUBLIC,
        internalName,
        null,
        "java/lang/Object",
        null);
    cw.visitEnd();
    return cw.toByteArray();
  }
}
