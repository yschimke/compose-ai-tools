package ee.schimke.composeai.simulator;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.robolectric.RuntimeEnvironment;

/**
 * androidx.appsearch's {@code AppSearchSession}, in memory, for both of its storages: {@code
 * LocalStorage}, whose Icing engine is a native library that cannot load here ({@code
 * UnsatisfiedLinkError: no icing}), and {@code PlatformStorage}, whose framework {@code
 * AppSearchManager} Robolectric does not have (a {@code NullPointerException} in the app). {@link
 * ShadowLocalStorage} and {@link ShadowPlatformStorage} hand out these sessions.
 *
 * <p>The session is a {@link Proxy} of the app's own {@code androidx.appsearch.app.AppSearchSession}
 * interface, and every androidx type it returns is built through that library's public builders by
 * reflection, so it follows whichever androidx.appsearch the APK ships. It keeps the schema types
 * set and the documents put, per storage and database, for the life of the simulator; it puts,
 * gets, removes (by id or by query) and searches ({@link AppSearchQuery}, filtered by the spec's
 * schema types and namespaces, newest first, paged). Joins, projections, ranking expressions,
 * snippets, suggestions, blobs and visibility settings are not simulated: a call to one fails with
 * {@code UnsupportedOperationException}.
 */
final class AppSearchSessions {

  private static final class Database {
    final Map<String, Object> schemas = new LinkedHashMap<>();
    /** Documents by namespace and id. */
    final Map<String, Object> documents = new LinkedHashMap<>();
  }

  private static final Map<String, Database> databases = new ConcurrentHashMap<>();

  /** A session on database [name] of [storage] ({@code local} or {@code platform}). */
  static ListenableFuture<Object> open(String storage, Object searchContext) {
    try {
      String name = (String) call(searchContext, "getDatabaseName");
      ClassLoader loader = searchContext.getClass().getClassLoader();
      Class<?> type = Class.forName("androidx.appsearch.app.AppSearchSession", true, loader);
      Database database = databases.computeIfAbsent(storage + ":" + name, k -> new Database());
      Object session =
          Proxy.newProxyInstance(loader, new Class<?>[] {type}, new Session(name, database, loader));
      return Futures.immediateFuture(session);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  private static final class Session implements InvocationHandler {
    private final String databaseName;
    private final Database db;
    private final ClassLoader loader;

    Session(String databaseName, Database db, ClassLoader loader) {
      this.databaseName = databaseName;
      this.db = db;
      this.loader = loader;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object[] a = args == null ? new Object[0] : args;
      switch (method.getName()) {
        case "toString":
          return "SimulatedAppSearchSession(" + databaseName + ")";
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return proxy == a[0];
        case "close":
          return null;
        case "getFeatures":
          return features(method.getReturnType());
        case "search":
          // Synchronous: the results' pages are what is asynchronous.
          synchronized (db) {
            return results(method.getReturnType(), (String) a[0], a[1]);
          }
        default:
          break;
      }
      try {
        synchronized (db) {
          return Futures.immediateFuture(run(method.getName(), a));
        }
      } catch (java.lang.reflect.InvocationTargetException e) {
        return Futures.immediateFailedFuture(e.getCause());
      } catch (Exception e) {
        return Futures.immediateFailedFuture(e);
      }
    }

    private Object run(String name, Object[] a) throws Exception {
      switch (name) {
        case "setSchemaAsync":
          {
            db.schemas.clear();
            for (Object schema : (Collection<?>) call(a[0], "getSchemas")) {
              db.schemas.put((String) call(schema, "getSchemaType"), schema);
            }
            return build("androidx.appsearch.app.SetSchemaResponse$Builder");
          }
        case "getSchemaAsync":
          {
            Object builder = construct("androidx.appsearch.app.GetSchemaResponse$Builder");
            for (Object schema : db.schemas.values()) call(builder, "addSchema", schema);
            return call(builder, "build");
          }
        case "getNamespacesAsync":
          {
            Set<String> namespaces = new LinkedHashSet<>();
            for (Object doc : db.documents.values()) namespaces.add((String) call(doc, "getNamespace"));
            return namespaces;
          }
        case "putAsync":
          {
            Object batch = construct("androidx.appsearch.app.AppSearchBatchResult$Builder");
            for (Object doc : (Collection<?>) call(a[0], "getGenericDocuments")) {
              String type = (String) call(doc, "getSchemaType");
              String id = (String) call(doc, "getId");
              if (!db.schemas.containsKey(type)) {
                // AppSearchResult.RESULT_NOT_FOUND: Icing's answer for a type never set.
                call(batch, "setFailure", id, 6, "schema type " + type + " is not in the schema");
                continue;
              }
              db.documents.put(key((String) call(doc, "getNamespace"), id), doc);
              call(batch, "setSuccess", id, null);
            }
            return call(batch, "build");
          }
        case "getByDocumentIdAsync":
          {
            Object batch = construct("androidx.appsearch.app.AppSearchBatchResult$Builder");
            String namespace = (String) call(a[0], "getNamespace");
            for (Object id : (Collection<?>) call(a[0], "getIds")) {
              Object doc = db.documents.get(key(namespace, (String) id));
              if (doc == null) call(batch, "setFailure", id, 6, "document " + id + " not found");
              else call(batch, "setSuccess", id, doc);
            }
            return call(batch, "build");
          }
        case "removeAsync":
          {
            if (a.length == 2) {
              for (Object doc : search((String) a[0], a[1])) {
                db.documents.remove(key((String) call(doc, "getNamespace"), (String) call(doc, "getId")));
              }
              return null;
            }
            Object batch = construct("androidx.appsearch.app.AppSearchBatchResult$Builder");
            String namespace = (String) call(a[0], "getNamespace");
            for (Object id : (Collection<?>) call(a[0], "getIds")) {
              if (db.documents.remove(key(namespace, (String) id)) == null) {
                call(batch, "setFailure", id, 6, "document " + id + " not found");
              } else {
                call(batch, "setSuccess", id, null);
              }
            }
            return call(batch, "build");
          }
        case "reportUsageAsync":
        case "requestFlushAsync":
          return null;
        case "getStorageInfoAsync":
          return build("androidx.appsearch.app.StorageInfo$Builder");
        default:
          throw new UnsupportedOperationException(
              "AppSearchSession." + name + " is not simulated");
      }
    }

    /** Matching documents, newest first. */
    private List<Object> search(String query, Object spec) throws ReflectiveOperationException {
      int termMatch = (int) call(spec, "getTermMatch");
      List<?> schemas = (List<?>) call(spec, "getFilterSchemas");
      List<?> namespaces = (List<?>) call(spec, "getFilterNamespaces");
      List<Object> matched = new ArrayList<>();
      for (Object doc : db.documents.values()) {
        if (!schemas.isEmpty() && !schemas.contains(call(doc, "getSchemaType"))) continue;
        if (!namespaces.isEmpty() && !namespaces.contains(call(doc, "getNamespace"))) continue;
        List<String> values = new ArrayList<>();
        strings(doc, values);
        if (AppSearchQuery.matches(values, query, termMatch)) matched.add(doc);
      }
      Collections.reverse(matched);
      matched.sort(
          (x, y) -> {
            try {
              return Long.compare(
                  (long) call(y, "getCreationTimestampMillis"),
                  (long) call(x, "getCreationTimestampMillis"));
            } catch (ReflectiveOperationException e) {
              return 0;
            }
          });
      return matched;
    }

    /** Every string value in [doc] and the documents nested in it. */
    private static void strings(Object doc, List<String> into) throws ReflectiveOperationException {
      for (Object name : (Collection<?>) call(doc, "getPropertyNames")) {
        Object value = call(doc, "getProperty", name);
        if (value instanceof String[]) {
          Collections.addAll(into, (String[]) value);
        } else if (value instanceof Object[]) {
          for (Object nested : (Object[]) value) {
            if (nested != null && nested.getClass().getName().endsWith("GenericDocument")) {
              strings(nested, into);
            }
          }
        }
      }
    }

    /** The {@code SearchResults} the synchronous {@code search} returns. */
    Object results(Class<?> type, String query, Object spec) throws ReflectiveOperationException {
      List<Object> matched = search(query, spec);
      int perPage = Math.max(1, (int) call(spec, "getResultCountPerPage"));
      String pkg = RuntimeEnvironment.getApplication().getPackageName();
      int[] next = {0};
      return Proxy.newProxyInstance(
          loader,
          new Class<?>[] {type},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "getNextPageAsync":
                {
                  List<Object> page = new ArrayList<>();
                  for (; next[0] < matched.size() && page.size() < perPage; next[0]++) {
                    Object builder =
                        construct("androidx.appsearch.app.SearchResult$Builder", pkg, databaseName);
                    call(builder, "setGenericDocument", matched.get(next[0]));
                    page.add(call(builder, "build"));
                  }
                  return Futures.immediateFuture(page);
                }
              case "close":
                return null;
              case "hashCode":
                return System.identityHashCode(proxy);
              case "equals":
                return proxy == args[0];
              case "toString":
                return "SimulatedSearchResults(" + query + ")";
              default:
                throw new UnsupportedOperationException("SearchResults." + method.getName());
            }
          });
    }

    private Object features(Class<?> type) {
      return Proxy.newProxyInstance(
          loader,
          new Class<?>[] {type},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "isFeatureSupported":
                return false;
              case "getMaxIndexedProperties":
                return 64;
              case "hashCode":
                return System.identityHashCode(proxy);
              case "equals":
                return proxy == args[0];
              case "toString":
                return "SimulatedFeatures";
              default:
                return null;
            }
          });
    }

    private Object construct(String className, Object... args) throws ReflectiveOperationException {
      Class<?> type = Class.forName(className, true, loader);
      for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
        if (constructor.getParameterCount() == args.length) return constructor.newInstance(args);
      }
      throw new NoSuchMethodException(className + " with " + args.length + " arguments");
    }

    private Object build(String builderClass) throws ReflectiveOperationException {
      return call(construct(builderClass), "build");
    }
  }

  private static String key(String namespace, String id) {
    return namespace + "\u0000" + id;
  }

  /** Calls the public method [name] of [target] taking [args.length] arguments. */
  static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
    for (Method method : target.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
      method.setAccessible(true);
      return method.invoke(target, args);
    }
    throw new NoSuchMethodException(target.getClass().getName() + "." + name);
  }

  private AppSearchSessions() {}
}
