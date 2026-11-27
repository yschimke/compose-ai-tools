package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StartupErrorLineTest {
  @Test
  public void buildsTheEventWithoutOrgJson() {
    assertEquals(
        "{\"event\":\"error\",\"kind\":\"native\",\"message\":\"no filament-jni in"
            + " java.library.path\"}",
        Protocol.startupErrorLine("native", "no filament-jni in java.library.path"));
  }

  @Test
  public void escapesWhatJsonRequires() {
    assertEquals(
        "\"a\\\"b\\\\c\\nd\\te\\u0001\"", Protocol.quote("a\"b\\c\nd\te\u0001"));
    assertEquals("null", Protocol.quote(null));
  }
}
