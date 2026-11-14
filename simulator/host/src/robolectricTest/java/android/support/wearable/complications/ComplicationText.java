package android.support.wearable.complications;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Test-scope wire {@code ComplicationText} Parcelable matching AndroidX Wear's Bundle layout. */
public class ComplicationText implements Parcelable {

  public static final Creator<ComplicationText> CREATOR =
      new Creator<ComplicationText>() {
        @Override
        public ComplicationText createFromParcel(Parcel in) {
          Bundle bundle = in.readBundle(ComplicationText.class.getClassLoader());
          CharSequence cs = bundle == null ? null : bundle.getCharSequence("surrounding_string");
          return new ComplicationText(cs);
        }

        @Override
        public ComplicationText[] newArray(int size) {
          return new ComplicationText[size];
        }
      };

  private final String text;

  public ComplicationText(CharSequence text) {
    this.text = text == null ? "" : text.toString();
  }

  public static ComplicationText plainText(CharSequence text) {
    return new ComplicationText(text);
  }

  public CharSequence getTextAt(Resources resources, long dateTimeMillis) {
    return text;
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    Bundle bundle = new Bundle();
    bundle.putCharSequence("surrounding_string", text);
    dest.writeBundle(bundle);
  }
}
