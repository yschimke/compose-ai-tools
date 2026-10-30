package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class DataMapTypesTest {

  @Test
  public void bareJsonValuesAreStringsBooleansAndInts() {
    assertEquals(DataMapTypes.STRING, DataMapTypes.tagOf("x"));
    assertEquals(DataMapTypes.BOOLEAN, DataMapTypes.tagOf(true));
    assertEquals(DataMapTypes.INT, DataMapTypes.tagOf(3));
  }

  @Test
  public void everythingElseIsTagged() {
    assertEquals(DataMapTypes.LONG, DataMapTypes.tagOf(3L));
    assertEquals(DataMapTypes.FLOAT, DataMapTypes.tagOf(1.5f));
    assertEquals(DataMapTypes.DOUBLE, DataMapTypes.tagOf(1.5d));
    assertEquals(DataMapTypes.BYTES, DataMapTypes.tagOf(new byte[] {1}));
    assertEquals(DataMapTypes.STRING_ARRAY, DataMapTypes.tagOf(new String[] {"a"}));
    assertEquals(DataMapTypes.LONG_ARRAY, DataMapTypes.tagOf(new long[] {1}));
    assertEquals(DataMapTypes.FLOAT_ARRAY, DataMapTypes.tagOf(new float[] {1}));
    assertEquals(DataMapTypes.LIST, DataMapTypes.tagOf(Arrays.asList("a")));
    assertEquals(DataMapTypes.OTHER, DataMapTypes.tagOf(new Object()));
  }

  @Test
  public void integersBeyondAnIntAreLongs() {
    assertTrue(DataMapTypes.fitsInt(Integer.MAX_VALUE));
    assertTrue(DataMapTypes.fitsInt(Integer.MIN_VALUE));
    assertFalse(DataMapTypes.fitsInt(Integer.MAX_VALUE + 1L));
    assertFalse(DataMapTypes.fitsInt(1_700_000_000_000L));
  }
}
