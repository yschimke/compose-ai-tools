package ee.schimke.composeai.simulator;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Builds real wire {@code android.support.wearable.complications.ComplicationData} {@link
 * Parcelable} instances through their R8-preserved {@code CREATOR}s so fake complications and
 * simulated system data sources work on {@code DeclarativeWatchFaceRuntime.apk} and {@code
 * androidx.wear.watchface} watch faces, and resolves timeline entries and {@code tapAction} {@link
 * PendingIntent}s on wire complication objects.
 */
final class ComplicationParcels {

  private static final String DATA_CLASS =
      "android.support.wearable.complications.ComplicationData";
  private static final String TEXT_CLASS =
      "android.support.wearable.complications.ComplicationText";

  private ComplicationParcels() {}

  /**
   * Builds a wire {@code ComplicationData} {@link Parcelable} loaded from {@code classLoader} for
   * {@code fake}, choosing a compatible wire type from {@code slot} if needed.
   */
  static Parcelable buildFakeParcelable(
      ClassLoader classLoader,
      DwfWatchFaceClient.FakeComplication fake,
      WatchFaceClient.Slot slot) {
    if (fake == null) return null;
    int wireType = resolveWireType(fake, slot);
    try {
      Class<?> dataType = Class.forName(DATA_CLASS, false, classLoader);
      Parcelable.Creator<?> dataCreator =
          (Parcelable.Creator<?>) dataType.getField("CREATOR").get(null);
      Bundle fields = new Bundle(classLoader);
      fields.putBoolean("TAP_ACTION_LOST", false);

      String textStr = fake.text != null ? fake.text : defaultTextFor(fake);
      Parcelable shortText =
          textStr != null ? buildComplicationText(classLoader, textStr) : null;
      Parcelable titleText =
          fake.title != null ? buildComplicationText(classLoader, fake.title) : null;

      switch (wireType) {
        case 3: // TYPE_SHORT_TEXT
          fields.putParcelable(
              "SHORT_TEXT",
              shortText != null ? shortText : buildComplicationText(classLoader, "--"));
          if (titleText != null) fields.putParcelable("SHORT_TITLE", titleText);
          if (Boolean.TRUE.equals(fake.hasMonochromaticImage)) {
            fields.putParcelable("ICON", createSyntheticIcon(true));
          }
          break;
        case 4: // TYPE_LONG_TEXT
          fields.putParcelable(
              "LONG_TEXT",
              shortText != null ? shortText : buildComplicationText(classLoader, "--"));
          if (titleText != null) fields.putParcelable("LONG_TITLE", titleText);
          if (Boolean.TRUE.equals(fake.hasMonochromaticImage)) {
            fields.putParcelable("ICON", createSyntheticIcon(true));
          }
          if (Boolean.TRUE.equals(fake.hasSmallImage)) {
            fields.putParcelable("SMALL_IMAGE", createSyntheticIcon(false));
            fields.putInt("IMAGE_STYLE", 1);
          }
          break;
        case 5: // TYPE_RANGED_VALUE
          {
            float min = fake.min != null ? fake.min.floatValue() : 0f;
            float max = fake.max != null ? fake.max.floatValue() : 100f;
            float val = fake.value != null ? fake.value.floatValue() : min;
            fields.putFloat("VALUE", val);
            fields.putFloat("MIN_VALUE", min);
            fields.putFloat("MAX_VALUE", max);
            if (shortText != null) fields.putParcelable("SHORT_TEXT", shortText);
            if (titleText != null) fields.putParcelable("SHORT_TITLE", titleText);
            if (Boolean.TRUE.equals(fake.hasMonochromaticImage)) {
              fields.putParcelable("ICON", createSyntheticIcon(true));
            }
            break;
          }
        case 13: // TYPE_GOAL_PROGRESS
          {
            float target = fake.max != null ? fake.max.floatValue() : 100f;
            float val = fake.value != null ? fake.value.floatValue() : 0f;
            fields.putFloat("VALUE", val);
            fields.putFloat("TARGET_VALUE", target);
            if (shortText != null) fields.putParcelable("SHORT_TEXT", shortText);
            if (titleText != null) fields.putParcelable("SHORT_TITLE", titleText);
            if (Boolean.TRUE.equals(fake.hasMonochromaticImage)) {
              fields.putParcelable("ICON", createSyntheticIcon(true));
            }
            break;
          }
        case 6: // TYPE_ICON / MONOCHROMATIC_IMAGE
          fields.putParcelable("ICON", createSyntheticIcon(true));
          if (shortText != null) {
            fields.putParcelable("CONTENT_DESCRIPTION", shortText);
          }
          break;
        case 7: // TYPE_SMALL_IMAGE
          fields.putParcelable("SMALL_IMAGE", createSyntheticIcon(false));
          fields.putInt("IMAGE_STYLE", 1); // IMAGE_STYLE_PHOTO
          break;
        case 8: // TYPE_LARGE_IMAGE / PHOTO_IMAGE
          fields.putParcelable("LARGE_IMAGE", createSyntheticIcon(false));
          break;
        case 0: // EMPTY -> wire TYPE_EMPTY is 2 in ComplicationData
          wireType = 2;
          break;
        default:
          break;
      }

      Parcel parcel = Parcel.obtain();
      try {
        parcel.writeInt(wireType);
        parcel.writeBundle(fields);
        parcel.setDataPosition(0);
        return (Parcelable) dataCreator.createFromParcel(parcel);
      } finally {
        parcel.recycle();
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "could not construct wire ComplicationData parcelable: " + e, e);
    }
  }

  private static String defaultTextFor(DwfWatchFaceClient.FakeComplication fake) {
    if (fake.value != null) {
      float v = fake.value.floatValue();
      if (v == Math.rint(v)) return Integer.toString((int) v);
      return Float.toString(v);
    }
    return null;
  }

  static int resolveWireType(DwfWatchFaceClient.FakeComplication fake, WatchFaceClient.Slot slot) {
    int requested = fake.wireType;
    if (slot == null || slot.supportedTypes.length == 0 || slot.supports(requested)) {
      return requested;
    }
    if (fake.value != null) {
      if (slot.supports(5)) return 5;
      if (slot.supports(13)) return 13;
    }
    if (slot.supports(3)) return 3;
    if (slot.supports(4)) return 4;
    if (slot.supports(6)) return 6;
    return slot.supportedTypes[0];
  }

  static Parcelable buildComplicationText(ClassLoader classLoader, String text)
      throws ReflectiveOperationException {
    Class<?> textClass = Class.forName(TEXT_CLASS, false, classLoader);
    Parcelable.Creator<?> creator =
        (Parcelable.Creator<?>) textClass.getField("CREATOR").get(null);
    Bundle bundle = new Bundle(classLoader);
    bundle.putCharSequence("surrounding_string", text);
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeBundle(bundle);
      parcel.setDataPosition(0);
      return (Parcelable) creator.createFromParcel(parcel);
    } finally {
      parcel.recycle();
    }
  }

  private static Icon createSyntheticIcon(boolean monochrome) {
    Bitmap bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    if (monochrome) {
      paint.setColor(Color.WHITE);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(4f);
      canvas.drawCircle(24f, 24f, 16f, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawCircle(24f, 24f, 6f, paint);
    } else {
      paint.setColor(0xFF4285F4);
      canvas.drawCircle(24f, 24f, 22f, paint);
      paint.setColor(Color.WHITE);
      canvas.drawCircle(24f, 20f, 7f, paint);
    }
    return Icon.createWithBitmap(bmp);
  }

  /** Extracts {@code getTapAction()} from a wire {@code ComplicationData}, or null. */
  static PendingIntent tapAction(Object wireData) {
    if (wireData == null) return null;
    try {
      Object action = wireData.getClass().getMethod("getTapAction").invoke(wireData);
      if (action instanceof PendingIntent) return (PendingIntent) action;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    // Fallback: inspect wire Bundle via Parcel if R8 stripped getTapAction()
    if (wireData instanceof Parcelable) {
      Parcel p = Parcel.obtain();
      try {
        ((Parcelable) wireData).writeToParcel(p, 0);
        p.setDataPosition(0);
        p.readInt(); // type
        Bundle b = p.readBundle(wireData.getClass().getClassLoader());
        if (b != null) {
          Parcelable pi = b.getParcelable("TAP_ACTION");
          if (pi instanceof PendingIntent) return (PendingIntent) pi;
        }
      } catch (RuntimeException ignored) {
      } finally {
        p.recycle();
      }
    }
    return null;
  }

  /**
   * Resolves the active timeline entry on {@code wireData} at {@code nowMillis} if timeline entries
   * are attached, falling back to {@code wireData} itself.
   */
  static Object activeTimelineEntry(Object wireData, long nowMillis) {
    if (wireData == null) return null;
    long epochSec = nowMillis / 1000L;
    try {
      Method getEntries = wireData.getClass().getMethod("getTimelineEntries");
      Object rawList = getEntries.invoke(wireData);
      if (rawList instanceof List<?>) {
        for (Object entry : (List<?>) rawList) {
          if (entry == null) continue;
          Long start = invokeOptionalLong(entry, "getTimelineStartEpochSecond");
          Long end = invokeOptionalLong(entry, "getTimelineEndEpochSecond");
          boolean afterStart = start == null || epochSec >= start.longValue();
          boolean beforeEnd = end == null || epochSec < end.longValue();
          if (afterStart && beforeEnd) {
            return entry;
          }
        }
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return wireData;
  }

  /**
   * Decodes a Base64-encoded wire {@code ComplicationData} parcel using {@code classLoader}'s
   * {@code ComplicationData.CREATOR}, or returns null if {@code classLoader} has no complication
   * wire classes (for example a resource-only WFF APK running without the runtime).
   */
  @SuppressWarnings("unchecked")
  static Parcelable decodeParcelable(ClassLoader classLoader, String encoded) {
    if (encoded == null || encoded.isEmpty()) return null;
    try {
      Class<?> dataType = Class.forName(DATA_CLASS, false, classLoader);
      Parcelable.Creator<Parcelable> creator =
          (Parcelable.Creator<Parcelable>) dataType.getField("CREATOR").get(null);
      return Parcels.decode(encoded, creator);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return null;
    }
  }

  /**
   * Summarizes a wire {@code ComplicationData} into a JSON object compatible with {@link
   * DwfWatchFaceClient.FakeComplication#fromJson} so cross-JVM {@code --install} complication
   * providers can feed resource-only DWF watch faces even when the watch face JVM has no {@code
   * ComplicationData} class on its classpath.
   */
  static org.json.JSONObject summarizeToJson(
      Object wireData, android.content.res.Resources resources, long nowMillis, int slot) {
    if (wireData == null) return null;
    Object effective = activeTimelineEntry(wireData, nowMillis);
    Protocol.Json json = new Protocol.Json().put("slot", slot);
    Integer type = invokeOptionalInt(effective, "getType");
    if (type != null) json.put("type", type.intValue());
    String shortText = evalComplicationText(effective, "getShortText", resources, nowMillis);
    String longText = evalComplicationText(effective, "getLongText", resources, nowMillis);
    String shortTitle = evalComplicationText(effective, "getShortTitle", resources, nowMillis);
    String longTitle = evalComplicationText(effective, "getLongTitle", resources, nowMillis);
    String text = shortText != null ? shortText : longText;
    String title = shortTitle != null ? shortTitle : longTitle;
    if (text != null) json.put("text", text);
    if (title != null) json.put("title", title);
    Float val =
        invokeBooleanOrMissing(effective, "hasRangedValue")
            ? invokeOptionalFloat(effective, "getRangedValue")
            : null;
    if (val == null) val = invokeOptionalFloat(effective, "getValue");
    Float min =
        invokeBooleanOrMissing(effective, "hasRangedMinValue")
            ? invokeOptionalFloat(effective, "getRangedMinValue")
            : null;
    if (min == null) min = invokeOptionalFloat(effective, "getMinValue");
    Float max = null;
    if (type != null && type.intValue() == 13) {
      max =
          invokeBooleanOrMissing(effective, "hasTargetValue")
              ? invokeOptionalFloat(effective, "getTargetValue")
              : null;
    }
    if (max == null && invokeBooleanOrMissing(effective, "hasRangedMaxValue")) {
      max = invokeOptionalFloat(effective, "getRangedMaxValue");
    }
    if (max == null && invokeBooleanOrMissing(effective, "hasTargetValue")) {
      max = invokeOptionalFloat(effective, "getTargetValue");
    }
    if (val != null) json.put("value", val.doubleValue());
    if (min != null) json.put("min", min.doubleValue());
    if (max != null) json.put("max", max.doubleValue());
    json.put("hasMonochromaticImage", invokeNonNull(effective, "getIcon"));
    json.put("hasSmallImage", invokeNonNull(effective, "getSmallImage"));
    PendingIntent tap = tapAction(effective);
    if (tap == null) tap = tapAction(wireData);
    json.put("hasTapAction", tap != null);
    return json.object();
  }

  private static boolean invokeBooleanOrMissing(Object target, String method) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof Boolean) return ((Boolean) v).booleanValue();
      return true;
    } catch (NoSuchMethodException e) {
      return true;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return false;
    }
  }

  private static String evalComplicationText(
      Object target, String getter, android.content.res.Resources resources, long nowMillis) {
    try {
      Object ct = target.getClass().getMethod(getter).invoke(target);
      if (ct == null) return null;
      Object cs =
          ct.getClass()
              .getMethod("getTextAt", android.content.res.Resources.class, long.class)
              .invoke(ct, resources, nowMillis);
      return cs != null ? cs.toString() : null;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return null;
    }
  }

  private static Integer invokeOptionalInt(Object target, String method) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return null;
  }

  private static Float invokeOptionalFloat(Object target, String method) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof Number) return Float.valueOf(((Number) v).floatValue());
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return null;
  }

  private static boolean invokeNonNull(Object target, String method) {
    try {
      return target.getClass().getMethod(method).invoke(target) != null;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return false;
    }
  }

  private static Long invokeOptionalLong(Object target, String method) {
    try {
      Object v = target.getClass().getMethod(method).invoke(target);
      if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return null;
  }
}
