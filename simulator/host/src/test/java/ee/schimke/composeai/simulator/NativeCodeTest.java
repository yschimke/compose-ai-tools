package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeCodeTest {
  @Test
  public void findsAWrappedLinkErrorAndExplainsIt() {
    UnsatisfiedLinkError link = new UnsatisfiedLinkError("no androidx.graphics.path");
    RuntimeException wrapped = new RuntimeException("activity start", link);
    assertSame(link, NativeCode.find(wrapped));
    String message = NativeCode.explain(link);
    assertTrue(message.contains("androidx.graphics.path"));
    assertTrue(message.contains("bionic"));
    assertNull(NativeCode.find(new RuntimeException("other")));
  }
}
