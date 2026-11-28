package ee.schimke.composeai.simulator;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.CalendarContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * In-memory {@code CalendarProvider} ({@code content://com.android.calendar/...}) supporting {@code
 * calendars}, {@code events}, {@code reminders}, {@code attendees}, and {@code instances}. Lets
 * instrumentation tests create calendars, insert/query/update/delete events, and read them back
 * without a system CalendarProvider process.
 */
public final class CalendarProvider extends ContentProvider {

  static final String AUTHORITY = CalendarContract.AUTHORITY;

  private static final AtomicLong NEXT_ID = new AtomicLong(1);
  private static final Map<String, Map<Long, Map<String, Object>>> TABLES =
      new ConcurrentHashMap<>();

  /** Registers the provider for {@link CalendarContract#AUTHORITY} and resets any prior state. */
  public static void install(Context context) {
    TABLES.clear();
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTHORITY;
    info.packageName = "com.android.providers.calendar";
    info.name = CalendarProvider.class.getName();
    info.exported = true;
    CalendarProvider provider = new CalendarProvider();
    provider.attachInfo(context, info);
    ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    String table = tableOf(uri);
    if (table == null) return null;
    long id = NEXT_ID.getAndIncrement();
    Map<String, Object> row = new LinkedHashMap<>();
    if (values != null) {
      for (String key : values.keySet()) {
        row.put(key, values.get(key));
      }
    }
    row.put(BaseColumns._ID, id);
    tableMap(table).put(id, row);
    Uri base = Uri.parse("content://" + AUTHORITY + "/" + table);
    return ContentUris.withAppendedId(base, id);
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    String table = tableOf(uri);
    if (table == null) return new MatrixCursor(projection != null ? projection : new String[0]);
    List<Map<String, Object>> rows = matchingRows(uri, table, selection, selectionArgs);
    String[] cols = projection != null && projection.length > 0 ? projection : inferColumns(rows);
    MatrixCursor cursor = new MatrixCursor(cols);
    for (Map<String, Object> row : rows) {
      Object[] values = new Object[cols.length];
      for (int i = 0; i < cols.length; i++) {
        values[i] = row.get(cols[i]);
      }
      cursor.addRow(values);
    }
    return cursor;
  }

  @Override
  public String getType(Uri uri) {
    String table = tableOf(uri);
    if (table == null) return null;
    return parseId(uri) >= 0
        ? "vnd.android.cursor.item/vnd.android.calendar." + table
        : "vnd.android.cursor.dir/vnd.android.calendar." + table;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    String table = tableOf(uri);
    if (table == null || values == null) return 0;
    List<Map<String, Object>> rows = matchingRows(uri, table, selection, selectionArgs);
    for (Map<String, Object> row : rows) {
      for (String key : values.keySet()) {
        row.put(key, values.get(key));
      }
    }
    return rows.size();
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    String table = tableOf(uri);
    if (table == null) return 0;
    Map<Long, Map<String, Object>> map = tableMap(table);
    long id = parseId(uri);
    if (id >= 0) {
      return map.remove(id) != null ? 1 : 0;
    }
    List<Map<String, Object>> rows = matchingRows(uri, table, selection, selectionArgs);
    int removed = 0;
    for (Map<String, Object> row : rows) {
      Object rowId = row.get(BaseColumns._ID);
      if (rowId instanceof Long l && map.remove(l) != null) removed++;
    }
    return removed;
  }

  private static Map<Long, Map<String, Object>> tableMap(String table) {
    return TABLES.computeIfAbsent(table, k -> new ConcurrentHashMap<>());
  }

  private static List<Map<String, Object>> matchingRows(
      Uri uri, String table, String selection, String[] selectionArgs) {
    if ("instances".equals(table)) {
      List<String> segments = uri.getPathSegments();
      if (segments.size() >= 4
          && ("when".equals(segments.get(1)) || "whenbyday".equals(segments.get(1)))) {
        long rangeBegin = parseLong(segments.get(2), Long.MIN_VALUE);
        long rangeEnd = parseLong(segments.get(3), Long.MAX_VALUE);
        if ("whenbyday".equals(segments.get(1))) {
          // Julian day 2440588 is 1970-01-01 (Unix epoch day 0).
          java.time.ZoneId zone = java.time.ZoneId.systemDefault();
          if (rangeBegin != Long.MIN_VALUE) {
            rangeBegin =
                java.time.LocalDate.ofEpochDay(rangeBegin - 2440588L)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli();
          }
          if (rangeEnd != Long.MAX_VALUE) {
            rangeEnd =
                java.time.LocalDate.ofEpochDay(rangeEnd - 2440588L + 1L)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
                    - 1L;
          }
        }
        return matchingInstances(rangeBegin, rangeEnd, selection, selectionArgs);
      }
    }
    Map<Long, Map<String, Object>> map = tableMap(table);
    long id = parseId(uri);
    if (id >= 0) {
      Map<String, Object> row = map.get(id);
      return row != null ? List.of(row) : List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : map.values()) {
      if (matchesSelection(row, selection, selectionArgs)) {
        out.add(row);
      }
    }
    return out;
  }

  private static List<Map<String, Object>> matchingInstances(
      long rangeBegin, long rangeEnd, String selection, String[] selectionArgs) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> event : tableMap("events").values()) {
      long dtStart = asLong(event.get(CalendarContract.Events.DTSTART), Long.MIN_VALUE);
      long dtEnd = asLong(event.get(CalendarContract.Events.DTEND), dtStart);
      if (dtStart == Long.MIN_VALUE) continue;
      if (dtEnd < rangeBegin || dtStart > rangeEnd) continue;
      Map<String, Object> instance = new LinkedHashMap<>(event);
      instance.put(CalendarContract.Instances.EVENT_ID, event.get(BaseColumns._ID));
      instance.put(CalendarContract.Instances.BEGIN, dtStart);
      instance.put(CalendarContract.Instances.END, dtEnd);
      if (matchesSelection(instance, selection, selectionArgs)) {
        out.add(instance);
      }
    }
    return out;
  }

  private static long parseLong(String s, long fallback) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static long asLong(Object value, long fallback) {
    if (value instanceof Number n) return n.longValue();
    if (value instanceof String s) return parseLong(s, fallback);
    return fallback;
  }

  /** Evaluates AND-joined {@code col = ?} / {@code col = 'val'} predicates; rejects others. */
  private static boolean matchesSelection(
      Map<String, Object> row, String selection, String[] selectionArgs) {
    if (selection == null || selection.isBlank()) return true;
    String[] parts = selection.split("(?i)\\s+AND\\s+");
    int argIdx = 0;
    for (String raw : parts) {
      String clause = raw.trim();
      while (clause.startsWith("(") && clause.endsWith(")")) {
        clause = clause.substring(1, clause.length() - 1).trim();
      }
      int eq = clause.indexOf('=');
      if (eq <= 0 || "!<>".indexOf(clause.charAt(eq - 1)) >= 0) {
        throw new IllegalArgumentException("Unsupported CalendarProvider selection: " + selection);
      }
      String col = clause.substring(0, eq).trim();
      String rhs = clause.substring(eq + 1).trim();
      if (rhs.startsWith("=")) {
        throw new IllegalArgumentException("Unsupported CalendarProvider selection: " + selection);
      }
      String expected;
      if ("?".equals(rhs)) {
        if (selectionArgs == null || argIdx >= selectionArgs.length) return false;
        expected = selectionArgs[argIdx++];
      } else if (rhs.startsWith("'") && rhs.endsWith("'") && rhs.length() >= 2) {
        expected = rhs.substring(1, rhs.length() - 1);
      } else if (rhs.indexOf('=') >= 0) {
        throw new IllegalArgumentException("Unsupported CalendarProvider selection: " + selection);
      } else {
        expected = rhs;
      }
      Object actual = row.get(col);
      if (actual == null || !String.valueOf(actual).equals(expected)) {
        return false;
      }
    }
    return true;
  }

  private static String[] inferColumns(List<Map<String, Object>> rows) {
    Set<String> cols = new LinkedHashSet<>();
    cols.add(BaseColumns._ID);
    for (Map<String, Object> row : rows) cols.addAll(row.keySet());
    return cols.toArray(new String[0]);
  }

  private static String tableOf(Uri uri) {
    List<String> segments = uri.getPathSegments();
    return segments.isEmpty() ? null : segments.get(0);
  }

  private static long parseId(Uri uri) {
    List<String> segments = uri.getPathSegments();
    if (segments.size() < 2) return -1;
    try {
      return ContentUris.parseId(uri);
    } catch (NumberFormatException | UnsupportedOperationException e) {
      return -1;
    }
  }
}
