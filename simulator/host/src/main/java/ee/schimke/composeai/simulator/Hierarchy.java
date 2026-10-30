package ee.schimke.composeai.simulator;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inspector.WindowInspector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The on-screen UI as accessibility nodes: classic views through {@code
 * View.createAccessibilityNodeInfo()}, and anything that exposes an {@link
 * AccessibilityNodeProvider} — a Compose {@code AndroidComposeView} above all — through its
 * virtual node tree, so Compose semantics (text, content descriptions, {@code testTag}s exposed as
 * resource ids, click and scroll actions) appear alongside views.
 *
 * <p>Only the windows a user could see on one display are walked: the resumed activity's, and
 * dialogs and popups on top of it. Stopped activities further down the back stack are skipped.
 */
final class Hierarchy {

  private static final int MAX_DEPTH = 80;
  private static final int MAX_NODES = 8000;

  /** One node, kept as data so both the dump and the selectors read the same snapshot. */
  static final class Node {
    String cls;
    String text;
    String desc;
    String id;
    String hint;
    String state;
    final Rect bounds = new Rect();
    final List<String> flags = new ArrayList<>();
    final List<Node> children = new ArrayList<>();

    boolean has(String flag) {
      return flags.contains(flag);
    }

    JSONObject toJson(boolean recursive) {
      Protocol.Json json = new Protocol.Json();
      json.put("cls", cls);
      if (text != null) json.put("text", text);
      if (desc != null) json.put("desc", desc);
      if (id != null) json.put("resourceId", id);
      if (hint != null) json.put("hint", hint);
      if (state != null) json.put("state", state);
      json.put(
          "bounds",
          new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
      if (!flags.isEmpty()) json.put("flags", new JSONArray(flags));
      if (recursive && !children.isEmpty()) {
        JSONArray kids = new JSONArray();
        for (Node child : children) kids.put(child.toJson(true));
        json.put("children", kids);
      }
      return json.object();
    }
  }

  private static Method getChildId;
  private static Method getAccessibilityViewId;

  private int count;

  /** Snapshots every visible window on display [displayId], top-most last. */
  List<Node> snapshot(Set<Object> hiddenWindowTokens, int displayId) {
    count = 0;
    List<Node> windows = new ArrayList<>();
    for (View root : WindowInspector.getGlobalWindowViews()) {
      if (!root.isShown()) continue;
      if (Displays.displayOf(root) != displayId) continue;
      if (hiddenWindowTokens.contains(root.getApplicationWindowToken())) continue;
      Node node = view(root, 0);
      if (node != null) windows.add(node);
    }
    return windows;
  }

  private Node view(View view, int depth) {
    if (!view.isShown() || depth > MAX_DEPTH || count >= MAX_NODES) return null;
    count++;
    AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
    Node node = describe(info, view.getClass().getName());
    AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
    if (provider != null) {
      AccessibilityNodeInfo host =
          provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
      if (host != null) virtualChildren(view, provider, host, node, depth + 1, new HashSet<>());
    } else if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Node child = view(group.getChildAt(i), depth + 1);
        if (child != null) node.children.add(child);
      }
    }
    return node;
  }

  /**
   * The provider's virtual children of [parent]. A child can also be a real view inside the host
   * (a Compose {@code AndroidView}): its id names that view, with no virtual part, and asking the
   * provider for it would answer the host again. Such a child is walked as a view; [visited] stops
   * any other cycle a provider reports.
   */
  private void virtualChildren(
      View host,
      AccessibilityNodeProvider provider,
      AccessibilityNodeInfo parent,
      Node into,
      int depth,
      Set<Integer> visited) {
    if (depth > MAX_DEPTH) return;
    for (int i = 0; i < parent.getChildCount() && count < MAX_NODES; i++) {
      long childId = childId(parent, i);
      int virtualId = (int) (childId >> 32);
      if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
        View real = findByAccessibilityViewId(host, (int) childId);
        Node child = real == null || real == host ? null : view(real, depth);
        if (child != null) into.children.add(child);
        continue;
      }
      if (!visited.add(virtualId)) continue;
      AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(virtualId);
      if (info == null) continue;
      count++;
      Node child = describe(info, null);
      virtualChildren(host, provider, info, child, depth + 1, visited);
      into.children.add(child);
    }
  }

  /**
   * The {@code i}th child's node id. {@code AccessibilityNodeInfo.getChildId(int)} is hidden, but
   * the framework jar the sandbox runs is the real one: the id packs the virtual descendant id in
   * its upper 32 bits and the view's accessibility id in its lower.
   */
  private static long childId(AccessibilityNodeInfo parent, int index) {
    try {
      if (getChildId == null) {
        getChildId = AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
        getChildId.setAccessible(true);
      }
      return (long) getChildId.invoke(parent, index);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read accessibility child ids", e);
    }
  }

  /** The view under [root] whose (hidden) accessibility view id is [id], else null. */
  private static View findByAccessibilityViewId(View root, int id) {
    try {
      if (getAccessibilityViewId == null) {
        getAccessibilityViewId = View.class.getDeclaredMethod("getAccessibilityViewId");
        getAccessibilityViewId.setAccessible(true);
      }
      if ((int) getAccessibilityViewId.invoke(root) == id) return root;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read accessibility view ids", e);
    }
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findByAccessibilityViewId(group.getChildAt(i), id);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static Node describe(AccessibilityNodeInfo info, String fallbackClass) {
    Node node = new Node();
    CharSequence cls = info.getClassName();
    node.cls = cls != null ? cls.toString() : fallbackClass;
    node.text = string(info.getText());
    node.desc = string(info.getContentDescription());
    node.id = info.getViewIdResourceName();
    if (Build.VERSION.SDK_INT >= 26) node.hint = string(info.getHintText());
    if (Build.VERSION.SDK_INT >= 30) node.state = string(info.getStateDescription());
    info.getBoundsInScreen(node.bounds);
    if (info.isClickable()) node.flags.add("clickable");
    if (info.isLongClickable()) node.flags.add("longClickable");
    if (info.isCheckable()) node.flags.add(info.isChecked() ? "checked" : "unchecked");
    if (info.isScrollable()) node.flags.add("scrollable");
    if (info.isEditable()) node.flags.add("editable");
    if (info.isFocused()) node.flags.add("focused");
    if (info.isSelected()) node.flags.add("selected");
    if (info.isPassword()) node.flags.add("password");
    if (!info.isEnabled()) node.flags.add("disabled");
    return node;
  }

  private static String string(CharSequence value) {
    return value == null || value.length() == 0 ? null : value.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Selectors

  /**
   * Nodes matching a selector, in tree order. Keys (all optional, all must match): {@code text} /
   * {@code desc} exact, {@code textContains} / {@code descContains} case-insensitive, {@code
   * resourceId} whole or as the part after {@code :id/} (a Compose {@code testTag} when the app
   * sets {@code testTagsAsResourceId}), {@code cls} as a class-name suffix, and {@code clickable}.
   */
  static List<Node> find(List<Node> windows, JSONObject selector) throws JSONException {
    List<Node> matches = new ArrayList<>();
    for (Node window : windows) collect(window, selector, matches);
    return matches;
  }

  private static void collect(Node node, JSONObject selector, List<Node> into)
      throws JSONException {
    if (matches(node, selector)) into.add(node);
    for (Node child : node.children) collect(child, selector, into);
  }

  private static boolean matches(Node node, JSONObject selector) throws JSONException {
    if (selector.has("text") && !selector.getString("text").equals(node.text)) return false;
    if (selector.has("desc") && !selector.getString("desc").equals(node.desc)) return false;
    if (selector.has("textContains") && !contains(node.text, selector.getString("textContains"))) {
      return false;
    }
    if (selector.has("descContains") && !contains(node.desc, selector.getString("descContains"))) {
      return false;
    }
    if (selector.has("resourceId")) {
      String want = selector.getString("resourceId");
      if (node.id == null) return false;
      if (!node.id.equals(want) && !node.id.endsWith(":id/" + want)) return false;
    }
    if (selector.has("cls") && (node.cls == null || !node.cls.endsWith(selector.getString("cls")))) {
      return false;
    }
    if (selector.has("clickable") && selector.getBoolean("clickable") != node.has("clickable")) {
      return false;
    }
    return selector.length() > 0;
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null
        && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  /** Every node with something to say: text, a description, an id, or an action. */
  static JSONArray flatten(List<Node> windows) {
    JSONArray array = new JSONArray();
    Set<Node> seen = new HashSet<>();
    for (Node window : windows) flattenInto(window, array, seen);
    return array;
  }

  private static void flattenInto(Node node, JSONArray into, Set<Node> seen) {
    boolean interesting =
        node.text != null
            || node.desc != null
            || node.id != null
            || node.has("clickable")
            || node.has("scrollable")
            || node.has("editable");
    if (interesting && seen.add(node)) into.put(node.toJson(false));
    for (Node child : node.children) flattenInto(child, into, seen);
  }
}
