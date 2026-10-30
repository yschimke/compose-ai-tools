package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * App Functions: the functions an app exposes to agents through an {@code AppFunctionService}
 * (the platform's, API 36, or androidx.appfunctions' {@code PlatformAppFunctionService}, which
 * extends it). The {@code appfunctions} command lists each service that handles {@code
 * android.app.appfunctions.AppFunctionService} and the function ids its metadata asset names
 * ({@code app_functions.xml} / {@code app_functions_v2.xml}, which androidx.appfunctions' compiler
 * generates), and with {@code execute} (a function id) and {@code params} (JSON, as the function's
 * parameter document) calls the service's {@code onExecuteFunction} as the system does when an
 * agent calls the app, from {@code com.android.shell} as {@code adb shell cmd app_function} does.
 *
 * <p>The reply carries the result document when the function answers at once; otherwise an {@code
 * appFunction} event carries it when it comes. Parameters map JSON to the document's properties by
 * type (string, long, double, boolean, nested object, or arrays of one of them), and the result
 * back. Execution needs {@code --sdk 36}: the platform type is API 36.
 */
final class AppFunctions {

  static final String SERVICE_ACTION = "android.app.appfunctions.AppFunctionService";
  static final String[] METADATA_ASSETS = {"app_functions_v2.xml", "app_functions.xml"};

  /** What {@code adb shell cmd app_function execute-app-function} calls as. */
  static final String CALLER = "com.android.shell";

  /** The {@link DeviceBus} op another app's {@code executeAppFunction} sends. */
  static final String BUS_OP = "appFunction.execute";

  /**
   * Runs the functions other apps on the device call ({@link SimulatorShadowAppFunctionManager}):
   * each with the caller's package, as the platform passes it, and the answer sent back.
   */
  static void serveOnBus(Application app) {
    DeviceBus.on(
        BUS_OP,
        (from, body, reply) -> {
          if (Build.VERSION.SDK_INT < 36) {
            reply.send(failure(1000, "App Functions need the platform's AppFunctionService: --sdk 36"));
            return;
          }
          ComponentName component;
          try {
            component = service(app, app.getPackageName());
          } catch (IllegalStateException e) {
            // AppFunctionException.ERROR_FUNCTION_NOT_FOUND.
            reply.send(failure(1003, e.getMessage()));
            return;
          }
          Api36.execute(
              Navigator.serviceFor(component),
              app.getPackageName(),
              body.getString("function"),
              body.optJSONObject("params") == null ? new JSONObject() : body.getJSONObject("params"),
              from,
              reply::send);
        });
  }

  /** The {@code AppFunctionService} of [pkg], which must be the app's own. */
  static ComponentName service(Application app, String pkg) {
    Intent intent = new Intent(SERVICE_ACTION).setPackage(pkg);
    for (ResolveInfo info : app.getPackageManager().queryIntentServices(intent, 0)) {
      if (info.serviceInfo != null) {
        return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
      }
    }
    throw new IllegalStateException(pkg + " declares no " + SERVICE_ACTION);
  }

  /** Whether the app's manifest asks for [permission]. */
  static boolean declares(Application app, String permission) {
    try {
      String[] requested =
          app.getPackageManager()
              .getPackageInfo(app.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS)
              .requestedPermissions;
      return requested != null && java.util.Arrays.asList(requested).contains(permission);
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /** A result as {@link Api36} reports one, for an error the system raises itself. */
  static JSONObject failure(int code, String message) {
    return new Protocol.Json()
        .put("error", new Protocol.Json().put("code", code).put("message", message).object())
        .object();
  }

  private final Application app;
  private final Protocol protocol;
  private int nextId = 1;

  AppFunctions(Application app, Protocol protocol) {
    this.app = app;
    this.protocol = protocol;
  }

  JSONObject command(JSONObject c) throws Exception {
    List<ComponentName> services = services();
    if (!c.has("execute")) {
      JSONArray list = new JSONArray();
      for (ComponentName service : services) {
        list.put(new Protocol.Json().put("service", service.getClassName()).object());
      }
      return new Protocol.Json()
          .put("services", list)
          .put("functions", new JSONArray(functionIds()))
          .put("canExecute", Build.VERSION.SDK_INT >= 36)
          .object();
    }
    if (services.isEmpty()) {
      throw new IllegalStateException("the app declares no " + SERVICE_ACTION);
    }
    if (Build.VERSION.SDK_INT < 36) {
      throw new IllegalStateException(
          "executing an app function needs the platform's AppFunctionService: launch with --sdk 36");
    }
    ComponentName component =
        c.has("service")
            ? new ComponentName(app.getPackageName(), qualify(c.getString("service")))
            : services.get(0);
    int requestId = nextId++;
    JSONObject params = c.optJSONObject("params");
    Object[] outcome = {null};
    Api36.execute(
        Navigator.serviceFor(component),
        app.getPackageName(),
        c.getString("execute"),
        params == null ? new JSONObject() : params,
        CALLER,
        result -> {
          JSONObject event = result;
          try {
            event.put("requestId", requestId).put("function", c.getString("execute"));
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
          if (Looper.myLooper() == Looper.getMainLooper() && outcome[0] == null) {
            outcome[0] = event;
          }
          new Handler(Looper.getMainLooper()).post(() -> protocol.event("appFunction", event));
        });
    Protocol.Json reply =
        new Protocol.Json()
            .put("requestId", requestId)
            .put("function", c.getString("execute"))
            .put("service", component.getClassName());
    if (outcome[0] != null) {
      JSONObject done = (JSONObject) outcome[0];
      reply.put("result", done.opt("result")).put("error", done.opt("error"));
    } else {
      reply.put("pending", true);
    }
    return reply.object();
  }

  private String qualify(String name) {
    return name.startsWith(".") ? app.getPackageName() + name : name;
  }

  /** The app's services that handle {@link #SERVICE_ACTION}. */
  private List<ComponentName> services() {
    List<ComponentName> found = new ArrayList<>();
    Intent intent = new Intent(SERVICE_ACTION).setPackage(app.getPackageName());
    for (ResolveInfo info : app.getPackageManager().queryIntentServices(intent, 0)) {
      if (info.serviceInfo != null) {
        found.add(new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name));
      }
    }
    return found;
  }

  /** The function ids the metadata assets name, in order, without repeats. */
  private List<String> functionIds() {
    List<String> ids = new ArrayList<>();
    for (String asset : METADATA_ASSETS) {
      try (InputStream in = app.getAssets().open(asset)) {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, "UTF-8");
        String tag = null;
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
          if (type == XmlPullParser.START_TAG) tag = parser.getName();
          else if (type == XmlPullParser.END_TAG) tag = null;
          else if (type == XmlPullParser.TEXT && isIdTag(tag)) {
            String id = parser.getText().trim();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
          }
        }
      } catch (java.io.FileNotFoundException e) {
        // This asset is not in the APK; the other may be.
      } catch (Exception e) {
        throw new IllegalStateException("cannot read assets/" + asset + ": " + e, e);
      }
    }
    return ids;
  }

  private static boolean isIdTag(String tag) {
    return tag != null && (tag.equals("function_id") || tag.equals("functionId") || tag.equals("id"));
  }

  /** The API 36 types, loaded only on a level that has them. */
  static final class Api36 {
    interface Result {
      void accept(JSONObject result);
    }

    static void execute(
        Service service,
        String pkg,
        String function,
        JSONObject params,
        String caller,
        Result result)
        throws Exception {
      if (!(service instanceof android.app.appfunctions.AppFunctionService)) {
        throw new IllegalStateException(
            service.getClass().getName() + " is not an android.app.appfunctions.AppFunctionService");
      }
      android.app.appfunctions.ExecuteAppFunctionRequest request =
          new android.app.appfunctions.ExecuteAppFunctionRequest.Builder(pkg, function)
              .setParameters(document(params))
              .build();
      android.os.OutcomeReceiver<
              android.app.appfunctions.ExecuteAppFunctionResponse,
              android.app.appfunctions.AppFunctionException>
          receiver =
              new android.os.OutcomeReceiver<>() {
                @Override
                public void onResult(android.app.appfunctions.ExecuteAppFunctionResponse response) {
                  result.accept(
                      new Protocol.Json().put("result", json(response.getResultDocument())).object());
                }

                @Override
                public void onError(android.app.appfunctions.AppFunctionException error) {
                  result.accept(
                      new Protocol.Json()
                          .put(
                              "error",
                              new Protocol.Json()
                                  .put("code", error.getErrorCode())
                                  .put("message", error.getMessage())
                                  .object())
                          .object());
                }
              };
      ((android.app.appfunctions.AppFunctionService) service)
          .onExecuteFunction(
              request,
              caller,
              new android.content.pm.SigningInfo(),
              new android.os.CancellationSignal(),
              receiver);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static android.app.appsearch.GenericDocument document(JSONObject json) throws JSONException {
      android.app.appsearch.GenericDocument.Builder builder =
          new android.app.appsearch.GenericDocument.Builder("", "", "");
      for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
        String name = keys.next();
        Object value = json.get(name);
        JSONArray values = value instanceof JSONArray ? (JSONArray) value : new JSONArray().put(value);
        if (values.length() == 0) continue;
        Object first = values.get(0);
        if (first instanceof String) {
          String[] strings = new String[values.length()];
          for (int i = 0; i < strings.length; i++) strings[i] = values.getString(i);
          builder.setPropertyString(name, strings);
        } else if (first instanceof Boolean) {
          boolean[] booleans = new boolean[values.length()];
          for (int i = 0; i < booleans.length; i++) booleans[i] = values.getBoolean(i);
          builder.setPropertyBoolean(name, booleans);
        } else if (first instanceof Integer || first instanceof Long) {
          long[] longs = new long[values.length()];
          for (int i = 0; i < longs.length; i++) longs[i] = values.getLong(i);
          builder.setPropertyLong(name, longs);
        } else if (first instanceof Number) {
          double[] doubles = new double[values.length()];
          for (int i = 0; i < doubles.length; i++) doubles[i] = values.getDouble(i);
          builder.setPropertyDouble(name, doubles);
        } else if (first instanceof JSONObject) {
          android.app.appsearch.GenericDocument[] nested =
              new android.app.appsearch.GenericDocument[values.length()];
          for (int i = 0; i < nested.length; i++) nested[i] = document(values.getJSONObject(i));
          builder.setPropertyDocument(name, nested);
        } else {
          throw new IllegalArgumentException("parameter " + name + " is not a string, number,"
              + " boolean or object: " + first);
        }
      }
      return builder.build();
    }

    /** [doc]'s properties as JSON: one value as itself, several as an array. */
    static JSONObject json(android.app.appsearch.GenericDocument doc) {
      JSONObject out = new JSONObject();
      if (doc == null) return out;
      for (String name : doc.getPropertyNames()) {
        Object value = doc.getProperty(name);
        JSONArray array = new JSONArray();
        if (value instanceof android.app.appsearch.GenericDocument[]) {
          for (android.app.appsearch.GenericDocument nested :
              (android.app.appsearch.GenericDocument[]) value) {
            array.put(json(nested));
          }
        } else if (value != null && value.getClass().isArray()) {
          int length = java.lang.reflect.Array.getLength(value);
          for (int i = 0; i < length; i++) {
            Object item = java.lang.reflect.Array.get(value, i);
            array.put(item instanceof byte[] ? java.util.Base64.getEncoder().encodeToString((byte[]) item) : item);
          }
        }
        try {
          out.put(name, array.length() == 1 ? array.get(0) : array);
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      }
      return out;
    }

    private Api36() {}
  }
}
