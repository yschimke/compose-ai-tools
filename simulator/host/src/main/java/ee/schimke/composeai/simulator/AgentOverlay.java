package ee.schimke.composeai.simulator;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.robolectric.simulator.SimulatorPanelRegistry;

/**
 * Shows the person watching the simulator window what the agent on the control channel is doing:
 * each tap as a fading ring, a long press as a filled one, a swipe as a trail, and the command in a
 * status strip along the bottom and in the window title.
 *
 * <p>Drawn on the Swing window's glass pane, never in the Android display, so screenshots the agent
 * takes stay free of it; the glass pane has no mouse listeners, so the person's own clicks still
 * reach the app. Does nothing headless, or until Robolectric has shown its first frame (the panel
 * registers then). Off with {@code composeai.simulator.showAgent=false}.
 */
final class AgentOverlay {

  static final String PROPERTY = "composeai.simulator.showAgent";

  private static final long MARK_MS = 900;
  private static final long STATUS_MS = 3000;
  private static final boolean HEADLESS = GraphicsEnvironment.isHeadless();
  private static final Color AGENT = new Color(0xE9, 0x1E, 0x63);

  private final boolean enabled = !"false".equals(System.getProperty(PROPERTY));
  private Glass glass;
  private String baseTitle;
  /** The round state the window shows; null until it has been applied once. */
  private volatile Boolean round;
  private volatile boolean roundPending;

  /**
   * Draws the window's display round, or square again: everything outside the inscribed circle is
   * painted black, as a round watch's bezel would hide it. Called every loop turn and cheap when
   * nothing changed; a round display masks the window even with the agent overlay off. Until the
   * window exists the change is retried on later turns.
   */
  void round(boolean value) {
    if (HEADLESS || roundPending || (round != null && round == value)) return;
    roundPending = true;
    SwingUtilities.invokeLater(
        () -> {
          roundPending = false;
          Glass pane = glass();
          if (pane == null) return;
          pane.round = value;
          pane.repaint();
          round = value;
        });
  }

  /** A ring at a device-pixel point; filled for a long press. */
  void touch(float x, float y, boolean held) {
    mark(new Mark(new float[] {x, y}, held));
  }

  /** A trail from one device-pixel point to another. */
  void swipe(float x1, float y1, float x2, float y2) {
    mark(new Mark(new float[] {x1, y1, x2, y2}, false));
  }

  /** The command the agent just sent, in a strip at the bottom and in the title. */
  void status(String text) {
    if (!enabled) return;
    SwingUtilities.invokeLater(
        () -> {
          Glass pane = glass();
          if (pane == null) return;
          pane.status = text;
          pane.statusAt = System.currentTimeMillis();
          JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(pane);
          if (baseTitle == null) baseTitle = frame.getTitle();
          frame.setTitle(baseTitle + " — agent: " + text);
          pane.animate();
        });
  }

  private void mark(Mark mark) {
    if (!enabled) return;
    SwingUtilities.invokeLater(
        () -> {
          Glass pane = glass();
          if (pane == null) return;
          pane.marks.add(mark);
          pane.animate();
        });
  }

  /** The glass pane, installed on first use; null headless or before the first frame. */
  private Glass glass() {
    if (glass != null) return glass;
    JPanel panel = (JPanel) SimulatorPanelRegistry.getActivePanel();
    if (panel == null) return null;
    Window window = SwingUtilities.getWindowAncestor(panel);
    if (!(window instanceof JFrame) || !window.isShowing()) return null;
    glass = new Glass(panel);
    ((JFrame) window).setGlassPane(glass);
    glass.setVisible(true);
    return glass;
  }

  private static final class Mark {
    final float[] points;
    final boolean held;
    final long at = System.currentTimeMillis();

    Mark(float[] points, boolean held) {
      this.points = points;
      this.held = held;
    }
  }

  private static final class Glass extends JComponent {
    final JPanel panel;
    final List<Mark> marks = new ArrayList<>();
    String status;
    long statusAt;
    boolean round;
    private final Timer timer = new Timer(30, e -> tick());

    Glass(JPanel panel) {
      this.panel = panel;
      setOpaque(false);
    }

    void animate() {
      if (!timer.isRunning()) timer.start();
      repaint();
    }

    private void tick() {
      long now = System.currentTimeMillis();
      marks.removeIf(m -> now - m.at > MARK_MS);
      if (status != null && now - statusAt > STATUS_MS) status = null;
      if (marks.isEmpty() && status == null) timer.stop();
      repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (round) {
          Point origin = SwingUtilities.convertPoint(panel, 0, 0, this);
          java.awt.Dimension shown = WindowZoom.shownSize(panel);
          int w = shown.width;
          int h = shown.height;
          int d = Math.min(w, h);
          Area bezel = new Area(new Rectangle(origin.x, origin.y, w, h));
          float left = origin.x + (w - d) / 2f;
          float top = origin.y + (h - d) / 2f;
          bezel.subtract(new Area(new Ellipse2D.Float(left, top, d, d)));
          g.setColor(Color.BLACK);
          g.fill(bezel);
        }
        long now = System.currentTimeMillis();
        for (Mark mark : marks) {
          float life = 1f - Math.min(1f, (now - mark.at) / (float) MARK_MS);
          g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, life));
          g.setColor(AGENT);
          g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          Point start = toGlass(mark.points[0], mark.points[1]);
          if (mark.points.length == 4) {
            Point end = toGlass(mark.points[2], mark.points[3]);
            g.drawLine(start.x, start.y, end.x, end.y);
            g.fillOval(end.x - 8, end.y - 8, 16, 16);
          } else {
            int radius = 18 + Math.round((1f - life) * 14);
            if (mark.held) g.fillOval(start.x - 12, start.y - 12, 24, 24);
            g.drawOval(start.x - radius, start.y - radius, radius * 2, radius * 2);
          }
        }
        if (status != null) {
          g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
          g.setFont(getFont() == null ? new Font(Font.SANS_SERIF, Font.BOLD, 13) : getFont());
          FontMetrics metrics = g.getFontMetrics();
          int height = metrics.getHeight() + 10;
          java.awt.Dimension shown = WindowZoom.shownSize(panel);
          Point origin = SwingUtilities.convertPoint(panel, 0, shown.height, this);
          g.setColor(Color.BLACK);
          g.fillRect(origin.x, origin.y - height, shown.width, height);
          g.setColor(Color.WHITE);
          g.drawString(
              "agent: " + status, origin.x + 8, origin.y - height + 5 + metrics.getAscent());
        }
      } finally {
        g.dispose();
      }
    }

    /** A device-pixel point in the glass pane, through the window's zoom. */
    private Point toGlass(float x, float y) {
      double scale = WindowZoom.scaleOf(panel);
      return SwingUtilities.convertPoint(
          panel, (int) Math.round(x * scale), (int) Math.round(y * scale), this);
    }
  }
}
