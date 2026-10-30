package ee.schimke.composeai.simulator;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayer;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.plaf.LayerUI;
import org.robolectric.simulator.SimulatorPanelRegistry;

/**
 * Zoom for a simulator window. The panel that draws the display at one device pixel per window
 * point (Robolectric's own for the default display, {@link Displays}' for the others) is wrapped in
 * a {@link JLayer} that paints it at a scale, and its mouse listeners are replaced by ones that map
 * each event back to device pixels, so what the app receives is unchanged. The window becomes
 * resizable: dragging its edge fits the display to it. ⌘ (Ctrl elsewhere) {@code =} / {@code -} /
 * {@code 0} zoom in, out and to actual size, as does the window's View menu. A display larger than
 * the screen starts zoomed out to fit it.
 *
 * <p>Swing only: screenshots, {@code hierarchy} bounds and the control channel's coordinates stay
 * in device pixels. Everything here runs on the event dispatch thread.
 */
final class WindowZoom {
  static final double MIN = 0.1;
  static final double MAX = 4;
  static final double STEP = 1.25;
  /** The share of the screen a display that does not fit starts at. */
  static final double SCREEN_SHARE = 0.9;

  private static final Map<JComponent, WindowZoom> ZOOMS = new WeakHashMap<>();
  private static boolean keysInstalled;
  private static volatile boolean mainInstalled;

  private final JFrame frame;
  private final JComponent view;
  private final JLayer<JComponent> layer;
  private int deviceWidth;
  private int deviceHeight;
  private double scale = 1;

  private WindowZoom(JFrame frame, JComponent view, int width, int height) {
    this.frame = frame;
    this.view = view;
    this.deviceWidth = width;
    this.deviceHeight = height;
    this.layer = new JLayer<>(view, new Scaled());
    mapMouse();
  }

  /** How big [view]'s display shows in its window: its own size when it has no zoom. */
  static Dimension shownSize(JComponent view) {
    WindowZoom zoom = ZOOMS.get(view);
    return zoom == null ? view.getSize() : zoom.layer.getSize();
  }

  /** The zoom of the window [view] is in; 1 when it has none. */
  static double scaleOf(JComponent view) {
    WindowZoom zoom = ZOOMS.get(view);
    return zoom == null ? 1 : zoom.scale;
  }

  /**
   * Robolectric's window for the default display, once its panel exists: zoomable, sized to
   * [width] x [height] device pixels. Cheap to call every loop turn; does nothing headless.
   */
  static void installMain(int width, int height) {
    if (mainInstalled || GraphicsEnvironment.isHeadless()) return;
    SwingUtilities.invokeLater(
        () -> {
          if (mainInstalled) return;
          JPanel panel = SimulatorPanelRegistry.getActivePanel();
          if (panel == null) return;
          Window window = SwingUtilities.getWindowAncestor(panel);
          if (!(window instanceof JFrame) || !window.isShowing()) return;
          install((JFrame) window, panel, width, height);
          mainInstalled = true;
        });
  }

  /** Makes [frame], which shows [view] at one device pixel per point, zoomable. */
  static WindowZoom install(JFrame frame, JComponent view, int width, int height) {
    WindowZoom existing = ZOOMS.get(view);
    if (existing != null) return existing;
    WindowZoom zoom = new WindowZoom(frame, view, width, height);
    ZOOMS.put(view, zoom);
    zoom.attach();
    return zoom;
  }

  /** The zoom of the window [view] is in, or null. */
  static WindowZoom of(JComponent view) {
    return ZOOMS.get(view);
  }

  /** The display changed size: the zoom stays, the window follows. */
  void deviceSize(int width, int height) {
    if (width == deviceWidth && height == deviceHeight) return;
    deviceWidth = width;
    deviceHeight = height;
    apply(scale, true);
  }

  /**
   * The control channel's {@code zoom}: [value] is {@code in}, {@code out}, {@code actual}, {@code
   * fit}, a percentage or a scale; null only reports. Returns the zoom and the window's size.
   */
  static org.json.JSONObject command(String value) throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      throw new IllegalStateException("there is no window to zoom: launched --headless");
    }
    java.util.concurrent.FutureTask<org.json.JSONObject> task =
        new java.util.concurrent.FutureTask<>(
            () -> {
              JPanel panel = SimulatorPanelRegistry.getActivePanel();
              WindowZoom zoom = panel == null ? null : ZOOMS.get(panel);
              if (zoom == null) throw new IllegalStateException("the window is not showing yet");
              if (value != null) zoom.set(value);
              return zoom.describe();
            });
    SwingUtilities.invokeLater(task);
    return task.get(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  private void set(String value) {
    switch (value) {
      case "in":
        zoomIn();
        return;
      case "out":
        zoomOut();
        return;
      case "actual":
        actualSize();
        return;
      case "fit":
        fitScreen();
        return;
      default:
        apply(parse(value), true);
    }
  }

  private org.json.JSONObject describe() {
    java.awt.Point origin = layer.isShowing() ? layer.getLocationOnScreen() : new java.awt.Point();
    return new Protocol.Json()
        .put("scale", Math.round(scale * 1000) / 1000.0)
        .put("percent", (int) Math.round(scale * 100))
        .put("deviceWidth", deviceWidth)
        .put("deviceHeight", deviceHeight)
        .put("shownWidth", layer.getWidth())
        .put("shownHeight", layer.getHeight())
        .put("screenX", origin.x)
        .put("screenY", origin.y)
        .put("windowWidth", frame.getWidth())
        .put("windowHeight", frame.getHeight())
        .object();
  }

  void zoomIn() {
    apply(stepped(scale, true), true);
  }

  void zoomOut() {
    apply(stepped(scale, false), true);
  }

  void actualSize() {
    apply(1, true);
  }

  void fitScreen() {
    apply(fit(deviceWidth, deviceHeight, usableScreen(), 1), true);
  }

  private void attach() {
    // The display letterboxed and centred in whatever size the window is dragged to.
    JPanel holder = new JPanel(new GridBagLayout());
    holder.setBackground(Color.BLACK);
    holder.add(layer);
    holder.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            double fitted =
                fit(deviceWidth, deviceHeight, holder.getWidth(), holder.getHeight(), MAX);
            // Our own pack lands here too, at the scale it was given: only a drag changes it.
            if (Math.abs(fitted - scale) > 0.01) apply(fitted, false);
          }
        });
    frame.getContentPane().remove(view);
    frame.getContentPane().add(holder);
    frame.setJMenuBar(menuBar());
    frame.setResizable(true);
    installKeys();
    Rectangle screen = usableScreen();
    apply(fit(deviceWidth, deviceHeight, screen, 1), true);
  }

  private void apply(double value, boolean pack) {
    scale = clamp(value);
    Dimension size = scaledSize(deviceWidth, deviceHeight, scale);
    layer.setPreferredSize(size);
    layer.revalidate();
    if (pack) frame.pack();
    frame.repaint();
  }

  private Rectangle usableScreen() {
    GraphicsConfiguration config = frame.getGraphicsConfiguration();
    if (config == null) return new Rectangle(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
    Rectangle bounds = config.getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
    Insets frameInsets = frame.getInsets();
    int menu = frame.getJMenuBar() == null ? 0 : frame.getJMenuBar().getPreferredSize().height;
    return new Rectangle(
        0,
        0,
        (int)
            ((bounds.width - insets.left - insets.right - frameInsets.left - frameInsets.right)
                * SCREEN_SHARE),
        (int)
            ((bounds.height
                    - insets.top
                    - insets.bottom
                    - frameInsets.top
                    - frameInsets.bottom
                    - menu)
                * SCREEN_SHARE));
  }

  private JMenuBar menuBar() {
    int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    JMenu menu = new JMenu("View");
    menu.add(item("Zoom In", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, shortcut), this::zoomIn));
    menu.add(item("Zoom Out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, shortcut), this::zoomOut));
    menu.add(
        item("Actual Size", KeyStroke.getKeyStroke(KeyEvent.VK_0, shortcut), this::actualSize));
    menu.add(item("Fit to Screen", null, this::fitScreen));
    JMenuBar bar = frame.getJMenuBar() == null ? new JMenuBar() : frame.getJMenuBar();
    bar.add(menu);
    return bar;
  }

  private static JMenuItem item(String label, KeyStroke key, Runnable action) {
    JMenuItem item = new JMenuItem(label);
    if (key != null) item.setAccelerator(key);
    item.addActionListener(e -> action.run());
    return item;
  }

  /**
   * The zoom shortcuts, taken before the focused panel's key listener would send them to the app
   * as key events.
   */
  private static void installKeys() {
    if (keysInstalled) return;
    keysInstalled = true;
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addKeyEventDispatcher(
            e -> {
              int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
              if ((e.getModifiersEx() & shortcut) == 0) return false;
              Action action = Action.of(e.getKeyCode(), e.getKeyChar());
              if (action == null) return false;
              WindowZoom zoom = forWindow(SwingUtilities.getWindowAncestor(e.getComponent()));
              if (zoom == null) return false;
              if (e.getID() == KeyEvent.KEY_PRESSED) action.run(zoom);
              e.consume();
              return true;
            });
  }

  private static WindowZoom forWindow(Window window) {
    if (window == null) return null;
    for (WindowZoom zoom : ZOOMS.values()) if (zoom.frame == window) return zoom;
    return null;
  }

  private enum Action {
    IN,
    OUT,
    ACTUAL;

    static Action of(int keyCode, char keyChar) {
      switch (keyCode) {
        case KeyEvent.VK_EQUALS:
        case KeyEvent.VK_PLUS:
        case KeyEvent.VK_ADD:
          return IN;
        case KeyEvent.VK_MINUS:
        case KeyEvent.VK_SUBTRACT:
          return OUT;
        case KeyEvent.VK_0:
        case KeyEvent.VK_NUMPAD0:
          return ACTUAL;
        default:
          // KEY_TYPED carries only the character.
          if (keyChar == '=' || keyChar == '+') return IN;
          if (keyChar == '-') return OUT;
          if (keyChar == '0') return ACTUAL;
          return null;
      }
    }

    void run(WindowZoom zoom) {
      if (this == IN) zoom.zoomIn();
      else if (this == OUT) zoom.zoomOut();
      else zoom.actualSize();
    }
  }

  /** Paints the display at the zoom. */
  private final class Scaled extends LayerUI<JComponent> {
    @Override
    public void doLayout(JLayer<? extends JComponent> l) {
      // JLayer would size the view to the layer, and JComponent.paint clips to the view's bounds
      // in the scaled space: zoomed out, the picture would be cut to the scale squared. As big as
      // the display (for the painting) and as the window (for the clicks).
      Dimension shown = l.getSize();
      view.setBounds(
          0, 0, Math.max(deviceWidth, shown.width), Math.max(deviceHeight, shown.height));
    }

    @Override
    public void paint(Graphics g, JComponent c) {
      Graphics2D scaled = (Graphics2D) g.create();
      try {
        if (scale != 1) {
          scaled.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              scale < 1
                  ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                  : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
          scaled.scale(scale, scale);
        }
        super.paint(scaled, c);
      } finally {
        scaled.dispose();
      }
    }
  }

  /**
   * Replaces the view's mouse listeners with ones that hand them the same events in device pixels.
   */
  private void mapMouse() {
    MouseListener[] clicks = view.getMouseListeners();
    MouseMotionListener[] moves = view.getMouseMotionListeners();
    MouseWheelListener[] wheels = view.getMouseWheelListeners();
    for (MouseListener l : clicks) view.removeMouseListener(l);
    for (MouseMotionListener l : moves) view.removeMouseMotionListener(l);
    for (MouseWheelListener l : wheels) view.removeMouseWheelListener(l);
    view.addMouseListener(
        new MouseListener() {
          @Override
          public void mouseClicked(MouseEvent e) {
            for (MouseListener l : clicks) forward(e, l::mouseClicked);
          }

          @Override
          public void mousePressed(MouseEvent e) {
            for (MouseListener l : clicks) forward(e, l::mousePressed);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            for (MouseListener l : clicks) forward(e, l::mouseReleased);
          }

          @Override
          public void mouseEntered(MouseEvent e) {
            for (MouseListener l : clicks) forward(e, l::mouseEntered);
          }

          @Override
          public void mouseExited(MouseEvent e) {
            for (MouseListener l : clicks) forward(e, l::mouseExited);
          }
        });
    view.addMouseMotionListener(
        new MouseMotionListener() {
          @Override
          public void mouseDragged(MouseEvent e) {
            for (MouseMotionListener l : moves) forward(e, l::mouseDragged);
          }

          @Override
          public void mouseMoved(MouseEvent e) {
            for (MouseMotionListener l : moves) forward(e, l::mouseMoved);
          }
        });
    view.addMouseWheelListener(
        e -> {
          for (MouseWheelListener l : wheels) {
            MouseWheelEvent mapped = (MouseWheelEvent) toDevice(e);
            l.mouseWheelMoved(mapped);
            if (mapped.isConsumed()) e.consume();
          }
        });
  }

  private interface Handler {
    void handle(MouseEvent e);
  }

  private void forward(MouseEvent e, Handler handler) {
    MouseEvent mapped = toDevice(e);
    handler.handle(mapped);
    if (mapped.isConsumed()) e.consume();
  }

  private MouseEvent toDevice(MouseEvent e) {
    int x = toDevice(e.getX(), scale, deviceWidth);
    int y = toDevice(e.getY(), scale, deviceHeight);
    int modifiers = e.getModifiersEx();
    if (e instanceof MouseWheelEvent) {
      MouseWheelEvent w = (MouseWheelEvent) e;
      return new MouseWheelEvent(
          e.getComponent(),
          e.getID(),
          e.getWhen(),
          modifiers,
          x,
          y,
          e.getXOnScreen(),
          e.getYOnScreen(),
          e.getClickCount(),
          e.isPopupTrigger(),
          w.getScrollType(),
          w.getScrollAmount(),
          w.getWheelRotation(),
          w.getPreciseWheelRotation());
    }
    return new MouseEvent(
        e.getComponent(),
        e.getID(),
        e.getWhen(),
        modifiers,
        x,
        y,
        e.getXOnScreen(),
        e.getYOnScreen(),
        e.getClickCount(),
        e.isPopupTrigger(),
        e.getButton());
  }

  // ---------------------------------------------------------------------------------------------
  // The arithmetic, pure for tests.

  /**
   * A zoom the control channel names: a percentage ({@code "75%"}, or a bare number above {@link
   * #MAX}) or a scale ({@code 0.75}). Anything else, NaN and infinities included, is an error.
   */
  static double parse(String value) {
    boolean percent = value.endsWith("%");
    double parsed;
    try {
      parsed = Double.parseDouble(percent ? value.substring(0, value.length() - 1) : value);
    } catch (NumberFormatException e) {
      parsed = Double.NaN;
    }
    if (!Double.isFinite(parsed) || parsed <= 0) {
      throw new IllegalArgumentException(
          "zoom takes in, out, actual, fit, a percentage (\"75%\") or a scale (0.75), not "
              + value);
    }
    return percent || parsed > MAX ? parsed / 100 : parsed;
  }

  static double clamp(double scale) {
    return Math.max(MIN, Math.min(MAX, scale));
  }

  /** One zoom step in or out from [scale], landing on 100% when a step passes it. */
  static double stepped(double scale, boolean in) {
    double next = in ? scale * STEP : scale / STEP;
    if ((scale < 1 && next > 1) || (scale > 1 && next < 1)) next = 1;
    return clamp(next);
  }

  /** The largest scale, at most [max], at which a [width] x [height] display fits [area]. */
  static double fit(int width, int height, Rectangle area, double max) {
    return fit(width, height, area.width, area.height, max);
  }

  static double fit(int width, int height, int areaWidth, int areaHeight, double max) {
    if (width <= 0 || height <= 0 || areaWidth <= 0 || areaHeight <= 0) return 1;
    double fits = Math.min(areaWidth / (double) width, areaHeight / (double) height);
    return clamp(Math.min(max, fits));
  }

  static Dimension scaledSize(int width, int height, double scale) {
    return new Dimension(
        Math.max(1, (int) Math.round(width * scale)),
        Math.max(1, (int) Math.round(height * scale)));
  }

  /** A window coordinate at [scale] as a device pixel, kept inside a display [extent] wide. */
  static int toDevice(int windowCoordinate, double scale, int extent) {
    int device = (int) Math.floor(windowCoordinate / scale);
    return Math.max(0, Math.min(extent - 1, device));
  }
}
