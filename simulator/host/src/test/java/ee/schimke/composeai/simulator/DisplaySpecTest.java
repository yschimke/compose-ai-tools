package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class DisplaySpecTest {

  @Test
  public void aSizeWithADensityBecomesQualifiers() {
    DisplaySpec spec = DisplaySpec.parse("1280x720@xhdpi,name=TV");
    assertEquals("TV", spec.name);
    assertEquals(DisplaySpec.Kind.EXTERNAL, spec.kind);
    assertEquals("sw720dp-w1280dp-h720dp-xlarge-long-land-xhdpi", spec.qualifiers);
  }

  @Test
  public void anEmptySpecIsADefaultExternalDisplay() {
    DisplaySpec spec = DisplaySpec.parse("");
    assertEquals("External display", spec.name);
    assertEquals(DisplaySpec.Kind.EXTERNAL, spec.kind);
    assertTrue(spec.qualifiers, spec.qualifiers.startsWith("sw540dp-w960dp-h540dp-"));
    assertTrue(spec.qualifiers, spec.qualifiers.endsWith("-mdpi"));
  }

  @Test
  public void aBareWordIsTheType() {
    DisplaySpec spec = DisplaySpec.parse("rear,411x891dp");
    assertEquals(DisplaySpec.Kind.REAR, spec.kind);
    assertEquals("Rear display", spec.name);
    assertTrue(spec.qualifiers, spec.qualifiers.startsWith("sw411dp-w411dp-h891dp-"));
  }

  @Test
  public void qualifiersAreTakenWhole() {
    DisplaySpec spec = DisplaySpec.parse("type=virtual,qualifiers=w320dp-h240dp-ldpi");
    assertEquals(DisplaySpec.Kind.VIRTUAL, spec.kind);
    assertEquals("w320dp-h240dp-ldpi", spec.qualifiers);
  }

  @Test
  public void theLaunchPropertySeparatesDisplaysBySemicolons() {
    List<DisplaySpec> specs = DisplaySpec.parseAll("960x540,name=One;rear");
    assertEquals(2, specs.size());
    assertEquals("One", specs.get(0).name);
    assertEquals(DisplaySpec.Kind.REAR, specs.get(1).kind);
    assertTrue(DisplaySpec.parseAll(null).isEmpty());
  }

  @Test
  public void mistakesAreNamed() {
    assertThrows(IllegalArgumentException.class, () -> DisplaySpec.parse("1280by720"));
    assertThrows(IllegalArgumentException.class, () -> DisplaySpec.parse("type=hologram"));
    assertThrows(IllegalArgumentException.class, () -> DisplaySpec.parse("colour=red"));
    assertThrows(IllegalArgumentException.class, () -> DisplaySpec.parse("0x10"));
  }
}
