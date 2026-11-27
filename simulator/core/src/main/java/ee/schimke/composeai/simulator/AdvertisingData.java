package ee.schimke.composeai.simulator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A BLE advertisement's bytes, as a peripheral puts them on air: flags, the local name, service
 * UUIDs (16- or 128-bit), service data, manufacturer data and TX power, each as an AD structure
 * ({@code length, type, data}). The framework parses them back into a {@code ScanRecord}, so an app
 * sees exactly what a radio would hand it. No Android types, for tests.
 */
final class AdvertisingData {

  private static final String BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb";

  private AdvertisingData() {}

  /**
   * {@code name}, {@code serviceUuids} [uuid], {@code serviceData} {uuid: bytes}, {@code
   * manufacturerData} {companyId: bytes} (a company id in decimal or {@code 0x…}), {@code txPower};
   * bytes as {@link #bytes}.
   */
  static byte[] build(JSONObject spec) throws JSONException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // LE General Discoverable, BR/EDR not supported.
    structure(out, 0x01, new byte[] {0x06});
    JSONArray uuids = spec.optJSONArray("serviceUuids");
    if (uuids != null && uuids.length() > 0) {
      ByteArrayOutputStream shortUuids = new ByteArrayOutputStream();
      ByteArrayOutputStream longUuids = new ByteArrayOutputStream();
      for (int i = 0; i < uuids.length(); i++) {
        UUID uuid = uuid(uuids.getString(i));
        Integer short16 = shortForm(uuid);
        if (short16 != null) {
          shortUuids.write(short16 & 0xff);
          shortUuids.write((short16 >> 8) & 0xff);
        } else {
          longUuids.writeBytes(littleEndian(uuid));
        }
      }
      if (shortUuids.size() > 0) structure(out, 0x03, shortUuids.toByteArray());
      if (longUuids.size() > 0) structure(out, 0x07, longUuids.toByteArray());
    }
    if (spec.has("txPower")) structure(out, 0x0A, new byte[] {(byte) spec.getInt("txPower")});
    JSONObject serviceData = spec.optJSONObject("serviceData");
    if (serviceData != null) {
      for (Iterator<String> keys = serviceData.keys(); keys.hasNext(); ) {
        String key = keys.next();
        UUID uuid = uuid(key);
        byte[] data = bytes(serviceData.get(key));
        Integer short16 = shortForm(uuid);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        if (short16 != null) {
          payload.write(short16 & 0xff);
          payload.write((short16 >> 8) & 0xff);
          payload.writeBytes(data);
          structure(out, 0x16, payload.toByteArray());
        } else {
          payload.writeBytes(littleEndian(uuid));
          payload.writeBytes(data);
          structure(out, 0x21, payload.toByteArray());
        }
      }
    }
    JSONObject manufacturer = spec.optJSONObject("manufacturerData");
    if (manufacturer != null) {
      for (Iterator<String> keys = manufacturer.keys(); keys.hasNext(); ) {
        String key = keys.next();
        int company = Integer.decode(key);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(company & 0xff);
        payload.write((company >> 8) & 0xff);
        payload.writeBytes(bytes(manufacturer.get(key)));
        structure(out, 0xFF, payload.toByteArray());
      }
    }
    String name = spec.optString("name", null);
    if (name != null && !name.isEmpty()) {
      structure(out, 0x09, name.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }

  private static void structure(ByteArrayOutputStream out, int type, byte[] data) {
    if (data.length + 1 > 255) throw new IllegalArgumentException("AD structure too long");
    out.write(data.length + 1);
    out.write(type);
    out.writeBytes(data);
  }

  /** A full UUID, a 16-bit ({@code 180D}) or 32-bit short form on the Bluetooth base UUID. */
  static UUID uuid(String value) {
    String v = value.trim().toLowerCase(Locale.ROOT);
    if (v.startsWith("0x")) v = v.substring(2);
    if (v.length() == 4) return UUID.fromString("0000" + v + BASE_SUFFIX);
    if (v.length() == 8) return UUID.fromString(v + BASE_SUFFIX);
    return UUID.fromString(v);
  }

  private static Integer shortForm(UUID uuid) {
    String s = uuid.toString();
    if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX))
      return Integer.parseInt(s.substring(4, 8), 16);
    return null;
  }

  private static byte[] littleEndian(UUID uuid) {
    byte[] b = new byte[16];
    long lsb = uuid.getLeastSignificantBits();
    long msb = uuid.getMostSignificantBits();
    for (int i = 0; i < 8; i++) {
      b[i] = (byte) (lsb >>> (8 * i));
      b[i + 8] = (byte) (msb >>> (8 * i));
    }
    return b;
  }

  /**
   * Bytes from JSON: a hex string ({@code "0a1b"}, spaces or colons allowed), {@code {"text": "…"}}
   * (UTF-8), {@code {"base64": "…"}}, or an array of numbers.
   */
  static byte[] bytes(Object value) throws JSONException {
    if (value instanceof JSONObject) {
      JSONObject o = (JSONObject) value;
      if (o.has("text")) return o.getString("text").getBytes(StandardCharsets.UTF_8);
      if (o.has("base64")) return Base64.getDecoder().decode(o.getString("base64"));
      if (o.has("hex")) return hex(o.getString("hex"));
      throw new IllegalArgumentException("bytes are hex, {text}, {base64} or [n…]");
    }
    if (value instanceof JSONArray) {
      JSONArray a = (JSONArray) value;
      byte[] out = new byte[a.length()];
      for (int i = 0; i < out.length; i++) out[i] = (byte) a.getInt(i);
      return out;
    }
    return hex(String.valueOf(value));
  }

  static byte[] hex(String value) {
    String h = value.replaceAll("[\\s:_-]", "");
    if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
    if (h.length() % 2 != 0) throw new IllegalArgumentException("odd-length hex: " + value);
    byte[] out = new byte[h.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }

  static String hex(byte[] value) {
    if (value == null) return null;
    StringBuilder s = new StringBuilder();
    for (byte b : value) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    return s.toString();
  }
}
