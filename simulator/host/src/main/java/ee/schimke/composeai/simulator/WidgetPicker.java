package ee.schimke.composeai.simulator;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The widget picker, and the confirm sheet of a pin request: a list of the app's providers, each
 * with its label, size in cells, description and preview — the generated preview the app set with
 * {@code setWidgetPreview} (API 35), else its {@code previewLayout} inflated, else its {@code
 * previewImage}, else the app's icon, as Launcher3 picks — drawn at the widget's size and scaled
 * down to fit. A long press on a preview places the widget, as dragging it out does on a device;
 * {@code widget add} is the same without the picker.
 */
final class WidgetPicker {

  private final HomeScreen home;

  WidgetPicker(HomeScreen home) {
    this.home = home;
  }

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    if (!name.equals("picker")) return null;
    String action = c.optString("action", c.optString("value", "open"));
    switch (action) {
      case "open":
        home.navigator().goHome();
        home.setOverlay("picker");
        return home.widgets.describe();
      case "close":
        if ("picker".equals(home.overlay())) home.closeOverlay();
        return home.describe();
      default:
        throw new IllegalArgumentException("picker takes open or close, not " + action);
    }
  }

  /** The picker's list, for {@link PhoneHome}'s overlay. */
  View build(Context context, int widthPx) {
    LinearLayout list = new LinearLayout(context);
    list.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(context, 16);
    list.setPadding(pad, dp(context, 40), pad, pad);
    TextView title = PhoneHome.text(context, "Widgets", 22, Color.WHITE);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    list.addView(title);
    TextView hint = PhoneHome.text(context, "Touch & hold a widget to place it", 13, 0xCCFFFFFF);
    hint.setPadding(0, dp(context, 4), 0, dp(context, 12));
    list.addView(hint);
    int shown = 0;
    for (AppWidgetProviderInfo info : home.widgets.providers()) {
      if ((info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER) != 0) {
        continue;
      }
      if ((info.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) continue;
      list.addView(row(context, info, widthPx - 2 * pad));
      shown++;
    }
    if (shown == 0) {
      list.addView(PhoneHome.text(context, "This app has no widgets", 15, Color.WHITE));
    }
    ScrollView scroll = new ScrollView(context);
    scroll.addView(list);
    PhoneHome.simId(scroll, "picker");
    FrameLayout sheet = new FrameLayout(context);
    sheet.setBackgroundColor(0xF2202124);
    sheet.setClickable(true);
    sheet.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    return sheet;
  }

  private View row(Context context, AppWidgetProviderInfo info, int widthPx) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(0, dp(context, 8), 0, dp(context, 16));
    int[] span = home.widgets.defaultSpan(info);
    TextView label = PhoneHome.text(context, home.widgets.label(info), 16, Color.WHITE);
    label.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(label);
    TextView size = PhoneHome.text(context, span[0] + " × " + span[1], 13, 0xCCFFFFFF);
    row.addView(size);
    String description = home.widgets.description(info);
    if (description != null) row.addView(PhoneHome.text(context, description, 13, 0xCCFFFFFF));
    View preview = preview(context, info, span[0], span[1], widthPx);
    preview.setContentDescription(home.widgets.label(info) + " widget preview");
    PhoneHome.simId(preview, "picker_preview");
    preview.setOnLongClickListener(
        v -> {
          try {
            home.widgets.add(info, null, null, null, null, false);
          } catch (RuntimeException e) {
            home.protocol()
                .event(
                    "error",
                    new Protocol.Json()
                        .put("kind", "widget")
                        .put("provider", info.provider.getClassName())
                        .put("message", String.valueOf(e))
                        .object());
          }
          return true;
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.topMargin = dp(context, 8);
    row.addView(preview, lp);
    return row;
  }

  /**
   * [info]'s preview at a [spanX]×[spanY] widget's size, scaled to at most [maxWidthPx] wide, on a
   * rounded card as the picker shows it.
   */
  View preview(Context context, AppWidgetProviderInfo info, int spanX, int spanY, int maxWidthPx) {
    float density = context.getResources().getDisplayMetrics().density;
    int w = Math.round(LauncherGrid.sizeDp(spanX) * density);
    int h = Math.round(LauncherGrid.sizeDp(spanY) * density);
    float scale = Math.min(1f, maxWidthPx / (float) w);
    FrameLayout card = new FrameLayout(context);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0x33FFFFFF);
    background.setCornerRadius(16 * density);
    card.setBackground(background);
    card.setClipToOutline(true);
    View content = content(context, info, card);
    FrameLayout inner = new FrameLayout(context);
    inner.addView(content, new FrameLayout.LayoutParams(w, h));
    inner.setPivotX(0);
    inner.setPivotY(0);
    inner.setScaleX(scale);
    inner.setScaleY(scale);
    card.addView(inner, new FrameLayout.LayoutParams(w, h));
    card.setLayoutParams(new ViewGroup.LayoutParams(Math.round(w * scale), Math.round(h * scale)));
    card.setLongClickable(true);
    return card;
  }

  private View content(Context context, AppWidgetProviderInfo info, ViewGroup parent) {
    String pkg = context.getPackageName();
    RemoteViews generated = home.widgets.generatedPreview(info.provider);
    try {
      if (generated != null) return generated.apply(context, parent);
      if (info.previewLayout != 0)
        return new RemoteViews(pkg, info.previewLayout).apply(context, parent);
    } catch (RuntimeException e) {
      // Fall through to the image, as the picker does when a layout cannot inflate.
    }
    ImageView image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    Drawable drawable = null;
    try {
      if (info.previewImage != 0)
        drawable = context.getResources().getDrawable(info.previewImage, null);
    } catch (RuntimeException e) {
      drawable = null;
    }
    if (drawable == null)
      drawable = context.getApplicationInfo().loadIcon(context.getPackageManager());
    image.setImageDrawable(drawable);
    return image;
  }

  /** The pin request's confirm sheet: the widget's preview, Add and Cancel. */
  View buildPinSheet(Context context, AppWidgetProviderInfo info, int widthPx) {
    FrameLayout scrim = new FrameLayout(context);
    scrim.setBackgroundColor(0x99000000);
    scrim.setClickable(true);
    LinearLayout sheet = new LinearLayout(context);
    sheet.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(context, 24);
    sheet.setPadding(pad, pad, pad, pad);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xFF2D2E31);
    float r = 28 * context.getResources().getDisplayMetrics().density;
    background.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
    sheet.setBackground(background);
    PhoneHome.simId(sheet, "pin_sheet");
    TextView title = PhoneHome.text(context, "Add to home screen?", 20, Color.WHITE);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    sheet.addView(title);
    sheet.addView(PhoneHome.text(context, home.widgets.label(info), 14, 0xCCFFFFFF));
    int[] span = home.widgets.defaultSpan(info);
    View preview = preview(context, info, span[0], span[1], widthPx - 2 * pad);
    LinearLayout.LayoutParams plp =
        new LinearLayout.LayoutParams(
            preview.getLayoutParams().width, preview.getLayoutParams().height);
    plp.topMargin = dp(context, 16);
    plp.bottomMargin = dp(context, 16);
    plp.gravity = Gravity.CENTER_HORIZONTAL;
    sheet.addView(preview, plp);
    LinearLayout buttons = new LinearLayout(context);
    buttons.setGravity(Gravity.END);
    TextView cancel = PhoneHome.button(context, "Cancel", false);
    PhoneHome.simId(cancel, "pin_reject");
    cancel.setOnClickListener(v -> home.widgets.answerPin(false));
    TextView add = PhoneHome.button(context, "Add to home screen", true);
    PhoneHome.simId(add, "pin_accept");
    add.setOnClickListener(v -> home.widgets.answerPin(true));
    buttons.addView(cancel);
    buttons.addView(add);
    sheet.addView(buttons);
    scrim.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    return scrim;
  }

  private static int dp(Context context, float dp) {
    return Math.round(dp * context.getResources().getDisplayMetrics().density);
  }
}
