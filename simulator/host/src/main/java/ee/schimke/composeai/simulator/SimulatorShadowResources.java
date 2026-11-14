package ee.schimke.composeai.simulator;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.util.TypedValue;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowResources;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Serves watch-face-derived {@code android.R.color.system_*} dynamic colors ({@link
 * WatchFaceDynamicColors}) to Wear Tiles, Glance Wear widgets, and Wear Compose apps when dynamic
 * theming from the active watch face is enabled.
 */
@Implements(Resources.class)
public class SimulatorShadowResources extends ShadowResources {

  @RealObject private Resources realResources;

  @Implementation
  protected int getColor(int id) throws Resources.NotFoundException {
    Integer override = WatchFaceDynamicColors.lookup(id);
    if (override != null) return override.intValue();
    return Shadow.directlyOn(
        realResources, Resources.class, "getColor", ClassParameter.from(int.class, id));
  }

  @Implementation
  protected int getColor(int id, Resources.Theme theme) throws Resources.NotFoundException {
    Integer override = WatchFaceDynamicColors.lookup(id);
    if (override != null) return override.intValue();
    return Shadow.directlyOn(
        realResources,
        Resources.class,
        "getColor",
        ClassParameter.from(int.class, id),
        ClassParameter.from(Resources.Theme.class, theme));
  }

  @Implementation
  protected ColorStateList getColorStateList(int id) throws Resources.NotFoundException {
    Integer override = WatchFaceDynamicColors.lookup(id);
    if (override != null) return ColorStateList.valueOf(override.intValue());
    return Shadow.directlyOn(
        realResources, Resources.class, "getColorStateList", ClassParameter.from(int.class, id));
  }

  @Implementation
  protected ColorStateList getColorStateList(int id, Resources.Theme theme)
      throws Resources.NotFoundException {
    Integer override = WatchFaceDynamicColors.lookup(id);
    if (override != null) return ColorStateList.valueOf(override.intValue());
    return Shadow.directlyOn(
        realResources,
        Resources.class,
        "getColorStateList",
        ClassParameter.from(int.class, id),
        ClassParameter.from(Resources.Theme.class, theme));
  }

  @Implementation
  protected void getValue(int id, TypedValue outValue, boolean resolveRefs)
      throws Resources.NotFoundException {
    Integer override = WatchFaceDynamicColors.lookup(id);
    if (override != null && outValue != null) {
      outValue.resourceId = id;
      outValue.type = TypedValue.TYPE_INT_COLOR_ARGB8;
      outValue.data = override.intValue();
      outValue.changingConfigurations = 0;
      return;
    }
    Shadow.directlyOn(
        realResources,
        Resources.class,
        "getValue",
        ClassParameter.from(int.class, id),
        ClassParameter.from(TypedValue.class, outValue),
        ClassParameter.from(boolean.class, resolveRefs));
  }
}
