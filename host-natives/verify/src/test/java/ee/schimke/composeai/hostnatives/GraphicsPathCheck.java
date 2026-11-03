package ee.schimke.composeai.hostnatives;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import androidx.graphics.path.PathIterator;
import androidx.graphics.path.PathSegment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Iterates a path holding every segment type through androidx.graphics:graphics-path and compares
 * the segments with `expected/<ConicEvaluation>.txt`: the framework `PathIterator`'s own answer
 * under Robolectric's native graphics. Converting conics to quadratics is the one native call
 * graphics-path still makes on API 34+.
 */
final class GraphicsPathCheck {

  static void run() throws IOException {
    String expect = System.getProperty("hostNatives.expect");
    System.out.println(
        "API "
            + Build.VERSION.SDK_INT
            + ", graphics-path "
            + System.getProperty("hostNatives.graphicsPath")
            + ", expecting "
            + expect);
    if (expect.equals("missing-library")) {
      UnsatisfiedLinkError error =
          assertThrows(UnsatisfiedLinkError.class, () -> iterate(PathIterator.ConicEvaluation.AsConic));
      assertTrue(error.getMessage(), error.getMessage().contains("androidx.graphics.path"));
      return;
    }
    for (PathIterator.ConicEvaluation mode : PathIterator.ConicEvaluation.values()) {
      assertEquals(mode.name(), expected(mode), iterate(mode));
    }
  }

  private static Path path() {
    Path path = new Path();
    path.moveTo(0f, 0f);
    path.lineTo(10f, 0f);
    path.quadTo(20f, 0f, 20f, 10f);
    path.cubicTo(20f, 20f, 10f, 30f, 0f, 30f);
    path.close();
    path.addOval(new RectF(40f, 0f, 140f, 50f), Path.Direction.CW);
    path.addRoundRect(new RectF(0f, 60f, 80f, 100f), 12f, 12f, Path.Direction.CCW);
    return path;
  }

  private static List<String> iterate(PathIterator.ConicEvaluation mode) {
    List<String> segments = new ArrayList<>();
    PathIterator iterator = new PathIterator(path(), mode, 0.25f);
    while (iterator.hasNext()) {
      PathSegment segment = iterator.next();
      StringBuilder line = new StringBuilder(segment.getType().name());
      for (PointF point : segment.getPoints()) {
        line.append(String.format(Locale.ROOT, " (%.3f,%.3f)", point.x, point.y));
      }
      if (segment.getType() == PathSegment.Type.Conic) {
        line.append(String.format(Locale.ROOT, " w=%.4f", segment.getWeight()));
      }
      segments.add(line.toString());
    }
    return segments;
  }

  private static List<String> expected(PathIterator.ConicEvaluation mode) throws IOException {
    try (InputStream in =
        GraphicsPathCheck.class.getResourceAsStream("/expected/" + mode.name() + ".txt")) {
      return Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip().split("\n"));
    }
  }

  private GraphicsPathCheck() {}
}
