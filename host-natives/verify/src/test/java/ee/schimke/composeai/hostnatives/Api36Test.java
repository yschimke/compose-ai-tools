package ee.schimke.composeai.hostnatives;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Api36Test {
  @Test
  public void iteratesPath() throws Exception {
    GraphicsPathCheck.run();
  }
}
