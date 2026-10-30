package ee.schimke.composeai.simulator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import java.util.Calendar;
import java.util.Locale;

/**
 * The demo watch face: a digital time and date on the simulator's clock (so {@code wait} moves
 * it), a dot while there are notifications, and ambient mode's dimmed outline. A backdrop for the
 * system surfaces, not a watch-face renderer: the app's own Watch Face Format or {@code
 * WatchFaceService} faces are not drawn.
 */
final class WatchFaceView extends View {

  private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint background = new Paint();
  private boolean ambient;
  private int notifications;
  private String shown = "";

  WatchFaceView(Context context) {
    super(context);
    float density = context.getResources().getDisplayMetrics().density;
    time.setTextAlign(Paint.Align.CENTER);
    time.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    time.setTextSize(56 * density);
    date.setTextAlign(Paint.Align.CENTER);
    date.setTextSize(15 * density);
    dot.setColor(Color.WHITE);
    ticks.setStrokeWidth(2 * density);
    ticks.setStrokeCap(Paint.Cap.ROUND);
    setContentDescription("watch face");
  }

  void ambient(boolean value) {
    if (ambient == value) return;
    ambient = value;
    invalidate();
  }

  void notifications(int count) {
    if (notifications == count) return;
    notifications = count;
    invalidate();
  }

  /** Redraws when the minute shown has changed; called every cycle. */
  void tick() {
    if (!shown.equals(timeText())) invalidate();
  }

  private static String timeText() {
    Calendar now = Calendar.getInstance();
    return String.format(
        Locale.ROOT, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float w = getWidth();
    float h = getHeight();
    float cx = w / 2f;
    float cy = h / 2f;
    float density = getResources().getDisplayMetrics().density;
    if (ambient) {
      canvas.drawColor(Color.BLACK);
    } else {
      background.setShader(
          new RadialGradient(
              cx, cy, Math.max(1f, Math.min(w, h) / 2f), 0xFF1B2A4A, 0xFF05070D,
              Shader.TileMode.CLAMP));
      canvas.drawRect(0, 0, w, h, background);
      // Twelve hour marks around the edge.
      ticks.setColor(0x66FFFFFF);
      float outer = Math.min(w, h) / 2f - 6 * density;
      float inner = outer - 8 * density;
      for (int i = 0; i < 12; i++) {
        double a = Math.toRadians(i * 30);
        canvas.drawLine(
            cx + (float) (Math.sin(a) * inner),
            cy - (float) (Math.cos(a) * inner),
            cx + (float) (Math.sin(a) * outer),
            cy - (float) (Math.cos(a) * outer),
            ticks);
      }
    }
    shown = timeText();
    Calendar now = Calendar.getInstance();
    String day =
        String.format(
            Locale.ROOT,
            "%s %d",
            now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()),
            now.get(Calendar.DAY_OF_MONTH));
    if (ambient) {
      time.setStyle(Paint.Style.STROKE);
      time.setStrokeWidth(1.5f * density);
      time.setColor(0xFFB0B0B0);
      date.setColor(0xFF808080);
    } else {
      time.setStyle(Paint.Style.FILL);
      time.setColor(Color.WHITE);
      date.setColor(0xFFA8C7FA);
    }
    canvas.drawText(day.toUpperCase(Locale.ROOT), cx, cy - 40 * density, date);
    canvas.drawText(shown, cx, cy + 20 * density, time);
    if (notifications > 0) {
      dot.setColor(ambient ? 0xFF808080 : Color.WHITE);
      canvas.drawCircle(cx, 24 * density, 4 * density, dot);
    }
  }
}
