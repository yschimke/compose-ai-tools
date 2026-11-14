package android.support.wearable.complications;

import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/** Test-scope wire {@code ComplicationData} Parcelable matching AndroidX Wear's Bundle layout. */
public class ComplicationData implements Parcelable {

  public static final Creator<ComplicationData> CREATOR =
      new Creator<ComplicationData>() {
        @Override
        public ComplicationData createFromParcel(Parcel in) {
          int type = in.readInt();
          Bundle fields = in.readBundle(ComplicationData.class.getClassLoader());
          return new ComplicationData(type, fields == null ? new Bundle() : fields);
        }

        @Override
        public ComplicationData[] newArray(int size) {
          return new ComplicationData[size];
        }
      };

  private final int mType;
  private final Bundle mFields;

  public ComplicationData(int type, Bundle fields) {
    this.mType = type;
    this.mFields = fields;
  }

  public int getType() {
    return mType;
  }

  public ComplicationText getShortText() {
    return mFields.getParcelable("SHORT_TEXT");
  }

  public ComplicationText getShortTitle() {
    return mFields.getParcelable("SHORT_TITLE");
  }

  public ComplicationText getLongText() {
    return mFields.getParcelable("LONG_TEXT");
  }

  public ComplicationText getLongTitle() {
    return mFields.getParcelable("LONG_TITLE");
  }

  public ComplicationText getContentDescription() {
    return mFields.getParcelable("CONTENT_DESCRIPTION");
  }

  public boolean hasRangedValue() {
    return mFields.containsKey("VALUE");
  }

  public float getRangedValue() {
    return mFields.getFloat("VALUE", 0f);
  }

  public boolean hasRangedMinValue() {
    return mFields.containsKey("MIN_VALUE");
  }

  public float getRangedMinValue() {
    return mFields.getFloat("MIN_VALUE", 0f);
  }

  public boolean hasRangedMaxValue() {
    return mFields.containsKey("MAX_VALUE");
  }

  public float getRangedMaxValue() {
    return mFields.getFloat("MAX_VALUE", 0f);
  }

  public boolean hasTargetValue() {
    return mFields.containsKey("TARGET_VALUE");
  }

  public float getTargetValue() {
    return mFields.getFloat("TARGET_VALUE", 0f);
  }

  public boolean hasIcon() {
    return getIcon() != null;
  }

  public Icon getIcon() {
    return mFields.getParcelable("ICON");
  }

  public Icon getSmallImage() {
    return mFields.getParcelable("SMALL_IMAGE");
  }

  public PendingIntent getTapAction() {
    return mFields.getParcelable("TAP_ACTION");
  }

  public Long getTimelineStartEpochSecond() {
    return mFields.containsKey("TIMELINE_START_TIME")
        ? Long.valueOf(mFields.getLong("TIMELINE_START_TIME"))
        : null;
  }

  public Long getTimelineEndEpochSecond() {
    return mFields.containsKey("TIMELINE_END_TIME")
        ? Long.valueOf(mFields.getLong("TIMELINE_END_TIME"))
        : null;
  }

  public List<ComplicationData> getTimelineEntries() {
    Parcelable[] arr = mFields.getParcelableArray("TIMELINE_ENTRIES");
    if (arr == null) return null;
    List<ComplicationData> out = new ArrayList<>(arr.length);
    for (Parcelable p : arr) {
      if (p instanceof ComplicationData) out.add((ComplicationData) p);
    }
    return out;
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(mType);
    dest.writeBundle(mFields);
  }
}
