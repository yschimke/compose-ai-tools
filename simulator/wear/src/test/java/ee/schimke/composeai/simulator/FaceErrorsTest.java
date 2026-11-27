package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public class FaceErrorsTest {
  @Test
  public void namesStageAndRootCause() {
    String s =
        FaceErrors.describe(
            "rendering the watch face",
            new IllegalArgumentException("Software rendering doesn't support drawRenderNode"));
    assertTrue(s, s.startsWith("rendering the watch face: IllegalArgumentException: Software"));
  }

  @Test
  public void unwrapsReflectionAndKeepsWrappers() {
    Throwable root = new IllegalStateException("bad element PartDraw#3");
    Throwable t = new InvocationTargetException(new RuntimeException("draw failed", root));
    String s = FaceErrors.describe(null, t);
    // The innermost cause leads; reflection wrappers are dropped.
    assertTrue(s, s.startsWith("RuntimeException: draw failed") || s.startsWith("IllegalStateException"));
    assertTrue(s, s.contains("bad element PartDraw#3") || s.contains("draw failed"));
  }

  @Test
  public void nullStageIsOmitted() {
    assertEquals(
        "IllegalStateException: x",
        FaceErrors.describe(null, new IllegalStateException("x")).replaceAll(" \\[at .*\\]$", ""));
  }
}
