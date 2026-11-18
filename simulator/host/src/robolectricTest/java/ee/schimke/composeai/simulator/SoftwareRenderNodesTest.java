package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * {@link SoftwareRenderNodes#draw} in a real sandbox: a render node reaches a software (bitmap)
 * canvas, where {@code Canvas.drawRenderNode} itself throws, at its position and under the
 * canvas's transform.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SoftwareRenderNodesTest {

  /** A 20x10 node at (30, 40) filled red, as the runtime's cached layer is. */
  private static RenderNode redLayer() {
    RenderNode node = new RenderNode("layer");
    node.setPosition(30, 40, 50, 50);
    RecordingCanvas recording = node.beginRecording();
    recording.drawColor(Color.RED);
    node.endRecording();
    return node;
  }

  @Test
  public void theFrameworkRefusesASoftwareCanvas() {
    Canvas canvas = new Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888));
    assertThrows(IllegalArgumentException.class, () -> canvas.drawRenderNode(redLayer()));
  }

  @Test
  public void drawsTheNodeOntoASoftwareCanvasAtItsPosition() {
    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.BLUE);

    SoftwareRenderNodes.draw(canvas, redLayer());

    assertEquals(Color.RED, bitmap.getPixel(35, 45));
    assertEquals(Color.RED, bitmap.getPixel(49, 49));
    // Outside the node the earlier content is kept, not cleared.
    assertEquals(Color.BLUE, bitmap.getPixel(10, 10));
    assertEquals(Color.BLUE, bitmap.getPixel(55, 45));
  }

  @Test
  public void appliesTheCanvasTransform() {
    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.BLUE);
    canvas.translate(20, -20);

    SoftwareRenderNodes.draw(canvas, redLayer());

    assertEquals(Color.RED, bitmap.getPixel(55, 25));
    assertEquals(Color.BLUE, bitmap.getPixel(35, 45));
    // The canvas keeps its own matrix afterwards.
    canvas.drawColor(Color.BLUE);
    assertEquals(Color.BLUE, bitmap.getPixel(55, 25));
  }
}
