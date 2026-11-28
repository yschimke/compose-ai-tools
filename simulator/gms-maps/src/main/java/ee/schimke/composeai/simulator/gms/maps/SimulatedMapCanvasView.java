package ee.schimke.composeai.simulator.gms.maps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight Canvas-rendered map surface attached inside a shadowed {@code MapView}.
 *
 * <p>Draws a Web-Mercator coordinate grid, polylines, circles, map pins with titles, and a camera
 * coordinate/zoom badge so Compose and View screens containing {@code GoogleMap} / {@code MapView}
 * produce legible visual output in screenshots and interactive simulator sessions.
 */
final class SimulatedMapCanvasView extends View {

  private final GmsMaps engine;
  private final Runnable invalidator = this::invalidate;
  private final Paint bgPaint = new Paint();
  private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint circleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint circleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pinInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  SimulatedMapCanvasView(Context context, GmsMaps engine) {
    super(context);
    this.engine = engine;
    gridPaint.setStrokeWidth(1.5f);
    roadPaint.setStrokeWidth(3f);
    linePaint.setStyle(Paint.Style.STROKE);
    linePaint.setStrokeCap(Paint.Cap.ROUND);
    linePaint.setStrokeJoin(Paint.Join.ROUND);
    circleFillPaint.setStyle(Paint.Style.FILL);
    circleStrokePaint.setStyle(Paint.Style.STROKE);
    circleStrokePaint.setStrokeWidth(2f);
    pinPaint.setColor(0xFFEA4335);
    pinInnerPaint.setColor(Color.WHITE);
    labelBgPaint.setColor(0xCC1F2937);
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(24f);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    engine.addInvalidateListener(invalidator);
  }

  @Override
  protected void onDetachedFromWindow() {
    engine.removeInvalidateListener(invalidator);
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    // 1. Background palette by mapType.
    int bg;
    int gridColor;
    switch (engine.mapType()) {
      case GmsMaps.MAP_TYPE_SATELLITE:
      case GmsMaps.MAP_TYPE_HYBRID:
        bg = 0xFF1E3240;
        gridColor = 0x33FFFFFF;
        break;
      case GmsMaps.MAP_TYPE_TERRAIN:
        bg = 0xFFE5EFE0;
        gridColor = 0x33476347;
        break;
      case GmsMaps.MAP_TYPE_NONE:
        bg = 0xFFF3F4F6;
        gridColor = 0x22000000;
        break;
      default:
        bg = 0xFFEAECE8;
        gridColor = 0x2664748B;
        break;
    }
    bgPaint.setColor(bg);
    canvas.drawRect(0, 0, w, h, bgPaint);

    // 2. Subtle grid lines.
    gridPaint.setColor(gridColor);
    int step = 80;
    for (int x = step; x < w; x += step) canvas.drawLine(x, 0, x, h, gridPaint);
    for (int y = step; y < h; y += step) canvas.drawLine(0, y, w, y, gridPaint);

    // 3. Circles.
    for (GmsMaps.CircleRecord c : engine.circlesSnapshot()) {
      if (!c.visible) continue;
      float[] pt = engine.latLngToScreen(c.lat, c.lng, w, h);
      double metersPerPx =
          156543.03392 * Math.cos(Math.toRadians(c.lat)) / Math.pow(2.0, engine.cameraZoom());
      float rPx = (float) Math.max(4.0, c.radiusMeters / Math.max(0.001, metersPerPx));
      if (c.fillColor != 0) {
        circleFillPaint.setColor(c.fillColor);
        canvas.drawCircle(pt[0], pt[1], rPx, circleFillPaint);
      }
      circleStrokePaint.setColor(c.strokeColor != 0 ? c.strokeColor : 0xFF1A73E8);
      canvas.drawCircle(pt[0], pt[1], rPx, circleStrokePaint);
    }

    // 4. Polylines.
    for (GmsMaps.PolylineRecord p : engine.polylinesSnapshot()) {
      if (!p.visible || p.points.size() < 2) continue;
      linePaint.setColor(p.color != 0 ? p.color : 0xFF1A73E8);
      linePaint.setStrokeWidth(p.width > 0 ? p.width : 6f);
      Path path = new Path();
      for (int i = 0; i < p.points.size(); i++) {
        double[] ll = p.points.get(i);
        float[] pt = engine.latLngToScreen(ll[0], ll[1], w, h);
        if (i == 0) path.moveTo(pt[0], pt[1]);
        else path.lineTo(pt[0], pt[1]);
      }
      canvas.drawPath(path, linePaint);
    }

    // 5. Markers.
    for (GmsMaps.MarkerRecord m : engine.markersSnapshot()) {
      if (!m.visible) continue;
      float[] pt = engine.latLngToScreen(m.lat, m.lng, w, h);
      canvas.drawCircle(pt[0], pt[1] - 14f, 12f, pinPaint);
      canvas.drawCircle(pt[0], pt[1] - 14f, 4.5f, pinInnerPaint);
      if (m.title != null && !m.title.isEmpty()) {
        float tw = textPaint.measureText(m.title);
        RectF pill = new RectF(pt[0] - tw / 2f - 10f, pt[1] - 58f, pt[0] + tw / 2f + 10f, pt[1] - 28f);
        canvas.drawRoundRect(pill, 8f, 8f, labelBgPaint);
        canvas.drawText(m.title, pt[0] - tw / 2f, pt[1] - 36f, textPaint);
      }
    }

    // 6. Camera coordinate + zoom HUD pill.
    String hud =
        String.format(
            Locale.ROOT,
            "Map %.4f, %.4f · z%.1f",
            engine.cameraLat(),
            engine.cameraLng(),
            engine.cameraZoom());
    float hudW = textPaint.measureText(hud);
    RectF hudRect = new RectF(12f, h - 46f, 12f + hudW + 20f, h - 12f);
    canvas.drawRoundRect(hudRect, 10f, 10f, labelBgPaint);
    canvas.drawText(hud, 22f, h - 22f, textPaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_UP && getWidth() > 0 && getHeight() > 0) {
      float x = event.getX();
      float y = event.getY();
      // Check marker hit first (within 24px).
      List<GmsMaps.MarkerRecord> markers = engine.markersSnapshot();
      for (int i = markers.size() - 1; i >= 0; i--) {
        GmsMaps.MarkerRecord m = markers.get(i);
        if (!m.visible) continue;
        float[] pt = engine.latLngToScreen(m.lat, m.lng, getWidth(), getHeight());
        if (Math.hypot(x - pt[0], y - (pt[1] - 14f)) <= 24.0) {
          engine.dispatchMarkerClick(m.id);
          return true;
        }
      }
      double[] ll = engine.screenToLatLng(x, y, getWidth(), getHeight());
      engine.dispatchMapClick(ll[0], ll[1]);
      return true;
    }
    return true;
  }
}
