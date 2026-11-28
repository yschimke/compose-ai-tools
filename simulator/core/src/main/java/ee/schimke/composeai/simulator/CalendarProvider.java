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

  /** Evaluates simple AND-joined {@code col = ?} / {@code col = 'val'} predicates. */
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
      if (eq <= 0) continue;
      String col = clause.substring(0, eq).trim();
      String rhs = clause.substring(eq + 1).trim();
      String expected;
      if ("?".equals(rhs)) {
        if (selectionArgs == null || argIdx >= selectionArgs.length) return false;
        expected = selectionArgs[argIdx++];
      } else if (rhs.startsWith("'") && rhs.endsWith("'") && rhs.length() >= 2) {
        expected = rhs.substring(1, rhs.length() - 1);
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
