package ee.schimke.composeai.simulator;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Base64;

/**
 * Parcelables as text, for what crosses between the device's apps over the {@link DeviceBus}: an
 * intent with its extras, a {@code ContentValues}, a {@code Bundle}. Marshalled as the binder
 * marshals them, so values keep their types; a custom {@code Parcelable} in an extra unparcels in
 * the receiving app only if that app has its class, as on a device.
 */
final class Parcels {

  static String encode(Parcelable value) {
    Parcel parcel = Parcel.obtain();
    try {
      value.writeToParcel(parcel, 0);
      return Base64.getEncoder().encodeToString(parcel.marshall());
    } finally {
      parcel.recycle();
    }
  }

  /** [text] from {@link #encode}, or null for null. */
  static <T> T decode(String text, Parcelable.Creator<T> creator) {
    if (text == null || text.isEmpty()) return null;
    byte[] bytes = Base64.getDecoder().decode(text);
    Parcel parcel = Parcel.obtain();
    try {
      parcel.unmarshall(bytes, 0, bytes.length);
      parcel.setDataPosition(0);
      return creator.createFromParcel(parcel);
    } finally {
      parcel.recycle();
    }
  }

  private Parcels() {}
}
