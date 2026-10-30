package ee.schimke.composeai.simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates an {@code adb shell} command line into the control command that does the same in the
 * simulator, so an agent can drive it with the {@code am}, {@code pm}, {@code input}, {@code
 * settings}, {@code content}, {@code dumpsys}, {@code logcat} … it already knows.
 *
 * <p>The result is the control command as a map ({@code cmd} plus its arguments, nested maps and
 * lists for JSON objects and arrays), which {@link Session} runs as if it had arrived as JSON. A
 * leading {@code adb [-s serial] shell} is accepted and dropped, as are {@code adb exec-out},
 * {@code adb logcat}, {@code adb pull} and {@code adb push}. Pipes and command lists are refused:
 * one command per line, as the control channel takes them. A trailing {@code > file} is honored
 * for the commands that write a file ({@code screencap}, {@code uiautomator dump}, {@code run-as
 * … cat}).
 *
 * <p>Plain Java with no Android or {@code org.json} types, so the parsing is unit-tested on the
 * host. What the commands mean on a device, and which parts the simulator cannot do, is in {@code
 * docs/SIMULATOR.md}.
 */
final class AdbShell {

  /** A command the simulator has no equivalent for, or a malformed one. */
  static final class UnsupportedException extends IllegalArgumentException {
    UnsupportedException(String message) {
      super(message);
    }
  }

  /** One word of a command line; {@code operator} for an unquoted {@code | ; & < >}. */
  static final class Token {
    final String text;
    final boolean operator;

    Token(String text, boolean operator) {
      this.text = text;
      this.operator = operator;
    }

    @Override
    public String toString() {
      return operator ? "<" + text + ">" : text;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tokenizing

  /**
   * Splits a command line as {@code sh} would: on unquoted whitespace, with single quotes taken
   * literally, double quotes honoring {@code \" \\ \$ \`}, and a backslash outside quotes escaping
   * the next character. Unquoted {@code | ; & < >} (and their doubled forms) become operators.
   */
  static List<Token> tokenize(String line) {
    List<Token> tokens = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    boolean inWord = false;
    int i = 0;
    int n = line.length();
    while (i < n) {
      char ch = line.charAt(i);
      if (ch == '\'') {
        int end = line.indexOf('\'', i + 1);
        if (end < 0) throw new UnsupportedException("unterminated ' in: " + line);
        word.append(line, i + 1, end);
        inWord = true;
        i = end + 1;
      } else if (ch == '"') {
        i++;
        boolean closed = false;
        while (i < n) {
          char c = line.charAt(i);
          if (c == '"') {
            closed = true;
            i++;
            break;
          }
          if (c == '\\' && i + 1 < n && "\"\\$`".indexOf(line.charAt(i + 1)) >= 0) {
            word.append(line.charAt(i + 1));
            i += 2;
          } else {
            word.append(c);
            i++;
          }
        }
        if (!closed) throw new UnsupportedException("unterminated \" in: " + line);
        inWord = true;
      } else if (ch == '\\') {
        if (i + 1 < n) word.append(line.charAt(i + 1));
        inWord = true;
        i += 2;
      } else if (Character.isWhitespace(ch)) {
        if (inWord) tokens.add(new Token(word.toString(), false));
        word.setLength(0);
        inWord = false;
        i++;
      } else if ("|;&<>".indexOf(ch) >= 0) {
        if (inWord) tokens.add(new Token(word.toString(), false));
        word.setLength(0);
        inWord = false;
        boolean doubled = i + 1 < n && line.charAt(i + 1) == ch;
        tokens.add(new Token(doubled ? "" + ch + ch : String.valueOf(ch), true));
        i += doubled ? 2 : 1;
      } else {
        word.append(ch);
        inWord = true;
        i++;
      }
    }
    if (inWord) tokens.add(new Token(word.toString(), false));
    return tokens;
  }

  // ---------------------------------------------------------------------------------------------
  // Entry points

  /** Translates one command line. */
  static Map<String, Object> translate(String line) {
    List<Token> tokens = tokenize(line);
    List<String> argv = new ArrayList<>();
    String redirect = null;
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (!token.operator) {
        argv.add(token.text);
        continue;
      }
      if (token.text.equals(">") && i == tokens.size() - 2 && !tokens.get(i + 1).operator) {
        redirect = tokens.get(i + 1).text;
        break;
      }
      throw new UnsupportedException(
          "'"
              + token.text
              + "' is not supported: one command per line, no pipes, lists or redirection"
              + " (except a trailing '> file')");
    }
    return translate(argv, redirect);
  }

  /** Translates an argument vector, as {@code adb shell} receives it. */
  static Map<String, Object> translate(List<String> argv, String redirect) {
    if (argv.isEmpty()) throw new UnsupportedException("empty command");
    Args a = new Args(argv);
    String program = a.next();
    Map<String, Object> command;
    switch (program) {
      case "adb":
        return adb(a, redirect);
      case "am":
        command = am(a);
        break;
      case "pm":
        command = pm(a);
        break;
      case "cmd":
        command = cmd(a);
        break;
      case "input":
        command = input(a);
        break;
      case "settings":
        command = settings(a);
        break;
      case "content":
        command = content(a);
        break;
      case "wm":
        command = wm(a);
        break;
      case "svc":
        command = svc(a);
        break;
      case "dumpsys":
        command = dumpsys(a);
        break;
      case "logcat":
        command = logcat(a);
        break;
      case "screencap":
        return screencap(a, redirect);
      case "uiautomator":
        return uiautomator(a, redirect);
      case "getprop":
        command = cmd("getprop");
        if (a.hasNext()) command.put("name", a.next());
        break;
      case "monkey":
        command = monkey(a);
        break;
      case "run-as":
        {
          String pkg = a.required("package");
          return files(a, pkg, redirect);
        }
      case "ls":
      case "cat":
      case "rm":
      case "mkdir":
        a.back();
        return files(a, null, redirect);
      default:
        throw new UnsupportedException(
            "'" + program + "' is not a command the simulator knows; see docs/SIMULATOR.md");
    }
    a.end();
    if (redirect != null) {
      throw new UnsupportedException("'> " + redirect + "' is not supported for " + program);
    }
    return command;
  }

  // ---------------------------------------------------------------------------------------------
  // adb (host side)

  private static final Set<String> ADB_OPTIONS_WITH_VALUE =
      Set.of("-s", "-t", "-H", "-P", "-L", "--one-device");

  private static Map<String, Object> adb(Args a, String redirect) {
    while (a.hasNext() && a.peek().startsWith("-")) {
      String option = a.next();
      if (ADB_OPTIONS_WITH_VALUE.contains(option)) a.required(option);
    }
    String sub = a.required("adb command");
    switch (sub) {
      case "shell":
      case "exec-out":
        // Shell options: -n (no stdin), -t / -T (tty), -x (no exit-status channel).
        while (a.hasNext() && Arrays.asList("-n", "-t", "-tt", "-T", "-x").contains(a.peek())) {
          a.next();
        }
        if (!a.hasNext()) throw new UnsupportedException("an interactive shell is not supported");
        List<String> rest = a.rest();
        // `adb shell "am start …"` arrives as one word: split it as the device's shell would.
        if (rest.size() == 1 && rest.get(0).contains(" ")) {
          Map<String, Object> inner = translate(rest.get(0));
          if (redirect != null) {
            throw new UnsupportedException("put '> file' inside the quoted command");
          }
          return inner;
        }
        return translate(rest, redirect);
      case "logcat":
        {
          Map<String, Object> command = logcat(a);
          a.end();
          return command;
        }
      case "pull":
        {
          Map<String, Object> command = cmd("files");
          command.put("op", "pull");
          skipOptions(a, "-a", "-z", "-Z");
          command.put("path", a.required("remote path"));
          command.put("to", a.hasNext() ? a.next() : ".");
          a.end();
          return command;
        }
      case "push":
        {
          Map<String, Object> command = cmd("files");
          command.put("op", "push");
          skipOptions(a, "--sync", "-z", "-Z", "-n");
          command.put("from", a.required("local path"));
          command.put("path", a.required("remote path"));
          a.end();
          return command;
        }
      case "install":
      case "install-multiple":
      case "uninstall":
        throw new UnsupportedException(
            "adb " + sub + ": the simulator runs the one APK it was launched with");
      default:
        throw new UnsupportedException("adb " + sub + " is not supported");
    }
  }

  private static void skipOptions(Args a, String... options) {
    List<String> known = Arrays.asList(options);
    while (a.hasNext() && known.contains(a.peek())) a.next();
  }

  // ---------------------------------------------------------------------------------------------
  // am

  private static Map<String, Object> am(Args a) {
    String sub = a.required("am command");
    switch (sub) {
      case "start":
      case "start-activity":
        return intentCommand("startActivity", a, true);
      case "start-service":
      case "startservice":
      case "start-foreground-service":
      case "startforegroundservice":
        return intentCommand("startService", a, false);
      case "stop-service":
      case "stopservice":
        return intentCommand("stopService", a, false);
      case "broadcast":
        return intentCommand("broadcast", a, false);
      case "force-stop":
      case "kill":
        {
          skipUser(a);
          Map<String, Object> command = cmd("forceStop");
          command.put("package", a.required("package"));
          return command;
        }
      case "kill-all":
        return cmd("forceStop");
      case "stack":
      case "task":
        if (a.hasNext() && a.peek().equals("list")) a.next();
        return cmd("activities");
      default:
        throw new UnsupportedException("am " + sub + " is not supported");
    }
  }

  /**
   * An intent from {@code am}'s options: {@code -a -d -t -c -n -p -f}, the {@code --e*} extras,
   * the {@code --activity-*} / {@code --receiver-*} / {@code --grant-*} flags, and a trailing URI,
   * component or package.
   */
  static Map<String, Object> intentCommand(String name, Args a, boolean activity) {
    Map<String, Object> command = cmd(name);
    Map<String, Object> intent = new LinkedHashMap<>();
    List<Object> categories = new ArrayList<>();
    List<Object> flags = new ArrayList<>();
    Map<String, Object> extras = new LinkedHashMap<>();
    while (a.hasNext()) {
      String option = a.next();
      switch (option) {
        case "-a":
          intent.put("action", a.required(option));
          break;
        case "-d":
          intent.put("data", a.required(option));
          break;
        case "-t":
          intent.put("type", a.required(option));
          break;
        case "-c":
          categories.add(a.required(option));
          break;
        case "-n":
          intent.put("component", a.required(option));
          break;
        case "-p":
          intent.put("package", a.required(option));
          break;
        case "-f":
          flags.add(parseInt(a.required(option), option));
          break;
        case "-i":
          // The intent's identifier: a filterEquals tie-breaker with nothing to do here.
          a.required(option);
          break;
        case "-e":
        case "--es":
          extras.put(a.required(option), typed("string", a.required(option)));
          break;
        case "--esn":
          extras.put(a.required(option), typed("null", null));
          break;
        case "--ez":
          {
            String key = a.required(option);
            extras.put(key, typed("boolean", parseBoolean(a.required(option))));
            break;
          }
        case "--ei":
          {
            String key = a.required(option);
            extras.put(key, typed("int", parseInt(a.required(option), option)));
            break;
          }
        case "--el":
          {
            String key = a.required(option);
            extras.put(key, typed("long", parseLong(a.required(option), option)));
            break;
          }
        case "--ef":
          {
            String key = a.required(option);
            extras.put(key, typed("float", parseDouble(a.required(option), option)));
            break;
          }
        case "--ed":
          {
            String key = a.required(option);
            extras.put(key, typed("double", parseDouble(a.required(option), option)));
            break;
          }
        case "--eu":
          extras.put(a.required(option), typed("uri", a.required(option)));
          break;
        case "--ecn":
          extras.put(a.required(option), typed("component", a.required(option)));
          break;
        case "--esa":
          extras.put(a.required(option), typed("string[]", splitArray(a.required(option))));
          break;
        case "--eia":
          {
            String key = a.required(option);
            List<Object> ints = new ArrayList<>();
            for (Object s : splitArray(a.required(option))) ints.add(parseInt((String) s, option));
            extras.put(key, typed("int[]", ints));
            break;
          }
        case "--display":
          {
            // An activity starts on that display; nothing else is placed by display.
            int display = parseInt(a.required(option), option);
            if (activity) command.put("display", display);
            break;
          }
        case "--user":
        case "--receiver-permission":
        case "--windowingMode":
        case "--activityType":
          // Single-user, and nothing checks a receiver permission.
          a.required(option);
          break;
        case "-W":
        case "--async":
        case "--allow-background-activity-starts":
        case "--wait-for-finish":
          // Replies already wait for the command to settle.
          break;
        case "-S":
          if (!activity) throw new UnsupportedException("-S applies to am start");
          command.put("forceStop", true);
          break;
        default:
          if (option.startsWith("--activity-")) {
            flags.add("FLAG_ACTIVITY_" + constant(option.substring("--activity-".length())));
          } else if (option.startsWith("--receiver-")) {
            flags.add("FLAG_RECEIVER_" + constant(option.substring("--receiver-".length())));
          } else if (option.startsWith("--grant-")
              || option.equals("--include-stopped-packages")
              || option.equals("--exclude-stopped-packages")
              || option.equals("--debug-log-resolution")) {
            flags.add("FLAG_" + constant(option.substring(2)));
          } else if (option.startsWith("-")) {
            throw new UnsupportedException("am option " + option + " is not supported");
          } else {
            target(intent, option);
            a.end();
          }
      }
    }
    if (!categories.isEmpty()) intent.put("categories", categories);
    if (!flags.isEmpty()) intent.put("flags", flags);
    if (!extras.isEmpty()) intent.put("extras", extras);
    if (intent.isEmpty()) throw new UnsupportedException("am: no intent given");
    command.put("intent", intent);
    return command;
  }

  /** {@code am}'s trailing argument: a URI if it has a scheme, else a component or a package. */
  private static void target(Map<String, Object> intent, String value) {
    if (value.contains(":")) {
      if (value.startsWith("intent:") || value.contains("#Intent;")) {
        throw new UnsupportedException("intent: URIs are not supported; use -a/-d/-n/--e* options");
      }
      intent.put("data", value);
      // Intent.parseUri gives a bare URI the VIEW action.
      intent.putIfAbsent("action", "android.intent.action.VIEW");
    } else if (value.contains("/")) {
      intent.put("component", value);
    } else {
      intent.put("package", value);
    }
  }

  private static List<Object> splitArray(String value) {
    // am splits on commas not preceded by a backslash.
    List<Object> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\\' && i + 1 < value.length() && value.charAt(i + 1) == ',') {
        part.append(',');
        i++;
      } else if (ch == ',') {
        parts.add(part.toString());
        part.setLength(0);
      } else {
        part.append(ch);
      }
    }
    parts.add(part.toString());
    return parts;
  }

  // ---------------------------------------------------------------------------------------------
  // pm, cmd

  private static Map<String, Object> pm(Args a) {
    String sub = a.required("pm command");
    switch (sub) {
      case "grant":
      case "revoke":
        {
          skipUser(a);
          Map<String, Object> command = cmd("permissions");
          command.put("package", a.required("package"));
          List<Object> names = new ArrayList<>();
          names.add(permission(a.required("permission")));
          while (a.hasNext()) names.add(permission(a.next()));
          command.put(sub.equals("grant") ? "grant" : "deny", names);
          return command;
        }
      case "reset-permissions":
        {
          Map<String, Object> command = cmd("permissions");
          command.put("reset", true);
          return command;
        }
      case "clear":
        {
          skipUser(a);
          Map<String, Object> command = cmd("clearData");
          command.put("package", a.required("package"));
          return command;
        }
      case "list":
        {
          String what = a.required("pm list target");
          while (a.hasNext() && a.peek().startsWith("-")) {
            if (a.next().equals("--user")) a.required("--user");
          }
          if (what.equals("packages")) {
            Map<String, Object> command = cmd("packages");
            if (a.hasNext()) command.put("filter", a.next());
            return command;
          }
          if (what.equals("permissions")) {
            if (a.hasNext()) a.next(); // A permission group; the app's own are listed.
            return cmd("permissions");
          }
          throw new UnsupportedException("pm list " + what + " is not supported");
        }
      case "path":
      case "dump":
        {
          skipUser(a);
          Map<String, Object> command = cmd("package");
          command.put("package", a.required("package"));
          return command;
        }
      case "install":
      case "install-create":
      case "uninstall":
        throw new UnsupportedException(
            "pm " + sub + ": the simulator runs the one APK it was launched with");
      default:
        throw new UnsupportedException("pm " + sub + " is not supported");
    }
  }

  private static String permission(String name) {
    return name.contains(".") ? name : "android.permission." + name;
  }

  private static Map<String, Object> cmd(Args a) {
    String service = a.required("cmd service");
    switch (service) {
      case "package":
        return pm(a);
      case "activity":
        return am(a);
      case "settings":
        return settings(a);
      case "input":
        return input(a);
      case "uimode":
        {
          String sub = a.required("uimode command");
          if (!sub.equals("night")) throw new UnsupportedException("cmd uimode " + sub);
          Map<String, Object> command = cmd("qualifiers");
          String mode = a.required("yes|no|auto");
          switch (mode) {
            case "yes":
              command.put("value", "+night");
              break;
            case "no":
            case "auto":
              command.put("value", "+notnight");
              break;
            default:
              throw new UnsupportedException("cmd uimode night " + mode + ": yes, no or auto");
          }
          return command;
        }
      case "connectivity":
        {
          String sub = a.required("connectivity command");
          if (!sub.equals("airplane-mode")) {
            throw new UnsupportedException("cmd connectivity " + sub + " is not supported");
          }
          Map<String, Object> command = cmd("network");
          if (a.hasNext()) command.put("airplane", enable(a.next()));
          return command;
        }
      case "wifi":
        {
          String sub = a.required("wifi command");
          Map<String, Object> command = cmd("network");
          if (sub.equals("set-wifi-enabled")) {
            command.put("wifi", enable(a.required("enabled|disabled")));
          } else if (!sub.equals("status")) {
            throw new UnsupportedException("cmd wifi " + sub + " is not supported");
          }
          return command;
        }
      default:
        throw new UnsupportedException("cmd " + service + " is not supported");
    }
  }

  private static boolean enable(String value) {
    switch (value) {
      case "enable":
      case "enabled":
      case "on":
      case "true":
      case "1":
        return true;
      case "disable":
      case "disabled":
      case "off":
      case "false":
      case "0":
        return false;
      default:
        throw new UnsupportedException("expected enable or disable, not " + value);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // input

  private static final Set<String> INPUT_SOURCES =
      Set.of(
          "touchscreen",
          "touchpad",
          "touchnavigation",
          "mouse",
          "stylus",
          "keyboard",
          "dpad",
          "gamepad",
          "joystick",
          "trackball",
          "rotaryencoder");

  private static Map<String, Object> input(Args a) {
    Integer display = null;
    if (a.hasNext() && a.peek().equals("-d")) {
      a.next();
      display = parseInt(a.required("-d"), "-d");
    }
    Map<String, Object> command = inputCommand(a);
    // `input -d <id>` sends the event to that display's windows.
    if (display != null) command.put("display", display);
    return command;
  }

  private static Map<String, Object> inputCommand(Args a) {
    if (a.hasNext() && INPUT_SOURCES.contains(a.peek())) a.next();
    String sub = a.required("input command");
    switch (sub) {
      case "tap":
        {
          Map<String, Object> command = cmd("tap");
          command.put("x", parseDouble(a.required("x"), "x"));
          command.put("y", parseDouble(a.required("y"), "y"));
          return command;
        }
      case "swipe":
      case "draganddrop":
        {
          Map<String, Object> command = cmd("swipe");
          command.put("x1", parseDouble(a.required("x1"), "x1"));
          command.put("y1", parseDouble(a.required("y1"), "y1"));
          command.put("x2", parseDouble(a.required("x2"), "x2"));
          command.put("y2", parseDouble(a.required("y2"), "y2"));
          if (a.hasNext()) command.put("durationMs", parseLong(a.next(), "duration"));
          // A drag starts with a long press.
          if (sub.equals("draganddrop")) command.put("holdMs", 800L);
          return command;
        }
      case "text":
        {
          // `input text` takes the rest of the line, with %s for a space.
          List<String> words = a.rest();
          if (words.isEmpty()) throw new UnsupportedException("input text: no text");
          Map<String, Object> command = cmd("text");
          command.put("text", String.join(" ", words).replace("%s", " "));
          return command;
        }
      case "keyevent":
      case "keycombination":
        {
          Map<String, Object> command = cmd("key");
          while (a.hasNext() && a.peek().startsWith("--")) {
            String option = a.next();
            if (option.equals("--longpress")) command.put("longPress", true);
            else if (!option.equals("--doubletap")) {
              throw new UnsupportedException("input keyevent " + option + " is not supported");
            }
          }
          List<Object> keys = new ArrayList<>();
          for (String key : a.rest()) keys.add(keyName(key));
          if (keys.isEmpty()) throw new UnsupportedException("input keyevent: no key");
          command.put("key", keys.size() == 1 ? keys.get(0) : keys);
          return command;
        }
      case "press":
        {
          Map<String, Object> command = cmd("key");
          command.put("key", "DPAD_CENTER");
          return command;
        }
      default:
        throw new UnsupportedException("input " + sub + " is not supported");
    }
  }

  private static Object keyName(String key) {
    try {
      return Integer.parseInt(key);
    } catch (NumberFormatException e) {
      return key.toUpperCase(Locale.ROOT);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // settings, content

  private static Map<String, Object> settings(Args a) {
    skipUser(a);
    String op = a.required("settings command");
    Map<String, Object> command = cmd("settings");
    command.put("op", op);
    switch (op) {
      case "get":
      case "delete":
        command.put("namespace", namespace(a.required("namespace")));
        command.put("key", a.required("key"));
        break;
      case "put":
        command.put("namespace", namespace(a.required("namespace")));
        command.put("key", a.required("key"));
        command.put("value", a.required("value"));
        // `settings put` takes an optional tag and `default`; neither matters here.
        a.rest();
        break;
      case "list":
        command.put("namespace", namespace(a.required("namespace")));
        break;
      default:
        throw new UnsupportedException("settings " + op + " is not supported");
    }
    return command;
  }

  private static String namespace(String value) {
    if (value.equals("system") || value.equals("secure") || value.equals("global")) return value;
    throw new UnsupportedException("settings namespace is system, secure or global, not " + value);
  }

  private static Map<String, Object> content(Args a) {
    String op = a.required("content command");
    if (!Arrays.asList("query", "insert", "update", "delete").contains(op)) {
      throw new UnsupportedException("content " + op + " is not supported");
    }
    Map<String, Object> command = cmd("content");
    command.put("op", op);
    Map<String, Object> values = new LinkedHashMap<>();
    while (a.hasNext()) {
      String option = a.next();
      switch (option) {
        case "--uri":
          command.put("uri", a.required(option));
          break;
        case "--user":
          a.required(option);
          break;
        case "--projection":
          command.put(
              "projection", new ArrayList<Object>(Arrays.asList(a.required(option).split(":"))));
          break;
        case "--where":
          command.put("selection", a.required(option));
          break;
        case "--sort":
          command.put("sort", a.required(option));
          break;
        case "--bind":
          {
            String[] parts = a.required(option).split(":", 3);
            if (parts.length < 2 || (parts.length < 3 && !parts[1].equals("n"))) {
              throw new UnsupportedException(
                  "--bind is name:type:value, not " + String.join(":", parts));
            }
            values.put(parts[0], bind(parts[1], parts.length > 2 ? parts[2] : null));
            break;
          }
        default:
          throw new UnsupportedException("content option " + option + " is not supported");
      }
    }
    if (!command.containsKey("uri")) throw new UnsupportedException("content: --uri is required");
    if (!values.isEmpty()) command.put("values", values);
    return command;
  }

  private static Map<String, Object> bind(String type, String value) {
    switch (type) {
      case "s":
        return typed("string", value);
      case "b":
        return typed("boolean", parseBoolean(value));
      case "i":
        return typed("int", parseInt(value, "--bind"));
      case "l":
        return typed("long", parseLong(value, "--bind"));
      case "f":
        return typed("float", parseDouble(value, "--bind"));
      case "d":
        return typed("double", parseDouble(value, "--bind"));
      case "n":
        return typed("null", null);
      default:
        throw new UnsupportedException("--bind type is one of s b i l f d n, not " + type);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // wm, svc, dumpsys, logcat, screencap, uiautomator, monkey

  private static Map<String, Object> wm(Args a) {
    String sub = a.required("wm command");
    Map<String, Object> command = cmd("display");
    switch (sub) {
      case "size":
        if (a.hasNext()) {
          String size = a.next();
          if (!size.equals("reset") && !size.matches("\\d+x\\d+")) {
            throw new UnsupportedException("wm size takes WxH in pixels or reset, not " + size);
          }
          command.put("size", size);
        }
        return command;
      case "density":
        if (a.hasNext()) {
          String density = a.next();
          command.put("density", density.equals("reset") ? density : parseInt(density, sub));
        }
        return command;
      default:
        throw new UnsupportedException("wm " + sub + " is not supported");
    }
  }

  private static Map<String, Object> svc(Args a) {
    String sub = a.required("svc command");
    if (!sub.equals("wifi")) {
      throw new UnsupportedException(
          "svc " + sub + " is not supported: the simulated network is Wi-Fi only (svc wifi)");
    }
    Map<String, Object> command = cmd("network");
    command.put("wifi", enable(a.required("enable|disable")));
    return command;
  }

  private static Map<String, Object> dumpsys(Args a) {
    String service = a.required("dumpsys service");
    switch (service) {
      case "activity":
        {
          String what = a.hasNext() ? a.next() : "activities";
          switch (what) {
            case "activities":
            case "a":
            case "top":
            case "recents":
            case "r":
              return cmd("activities");
            case "services":
            case "s":
              if (a.hasNext()) a.next(); // A package or component filter: there is one app.
              return cmd("services");
            default:
              throw new UnsupportedException("dumpsys activity " + what + " is not supported");
          }
        }
      case "notification":
        a.rest(); // --noredact and the like.
        return cmd("notifications");
      case "window":
      case "display":
        a.rest();
        return cmd("info");
      case "package":
        {
          Map<String, Object> command = cmd("package");
          if (a.hasNext()) command.put("package", a.next());
          return command;
        }
      case "connectivity":
      case "wifi":
        return cmd("network");
      default:
        throw new UnsupportedException("dumpsys " + service + " is not supported");
    }
  }

  /**
   * {@code logcat}: {@code -d} dumps, {@code -c} clears, neither follows (streams {@code log}
   * events); {@code -s}, {@code TAG:LEVEL} filter specs, {@code -t N}, {@code -e REGEX}.
   */
  static Map<String, Object> logcat(Args a) {
    Map<String, Object> command = cmd("logcat");
    String op = "follow";
    Map<String, Object> filters = new LinkedHashMap<>();
    boolean silent = false;
    while (a.hasNext()) {
      String option = a.next();
      switch (option) {
        case "-d":
          op = "dump";
          break;
        case "-c":
          op = "clear";
          break;
        case "-s":
          silent = true;
          break;
        case "-t":
        case "-T":
        case "-m":
          {
            command.put("tail", parseInt(a.required(option), option));
            if (option.equals("-t")) op = "dump";
            break;
          }
        case "-e":
        case "--regex":
          command.put("regex", a.required(option));
          break;
        case "-v":
        case "-b":
        case "--pid":
        case "--uid":
          // Output format, buffer and process: there is one process, its main buffer.
          a.required(option);
          break;
        default:
          if (option.startsWith("-")) {
            throw new UnsupportedException("logcat option " + option + " is not supported");
          }
          int colon = option.lastIndexOf(':');
          String tag = colon < 0 ? option : option.substring(0, colon);
          String level = colon < 0 ? "V" : option.substring(colon + 1).toUpperCase(Locale.ROOT);
          if (level.length() != 1 || "VDIWEFAS".indexOf(level.charAt(0)) < 0) {
            throw new UnsupportedException("logcat level is one of V D I W E F S, not " + level);
          }
          if (tag.equals("*")) command.put("default", level);
          else filters.put(tag, level);
      }
    }
    command.put("op", op);
    if (silent) command.putIfAbsent("default", "S");
    if (!filters.isEmpty()) command.put("filters", filters);
    return command;
  }

  private static Map<String, Object> screencap(Args a, String redirect) {
    Map<String, Object> command = cmd("screenshot");
    while (a.hasNext() && a.peek().startsWith("-")) {
      String option = a.next();
      if (option.equals("-d")) command.put("display", parseInt(a.required(option), option));
      else if (!option.equals("-p")) {
        throw new UnsupportedException("screencap " + option + " is not supported (PNG only)");
      }
    }
    if (a.hasNext()) {
      command.put("path", a.next());
      command.put("devicePath", true);
    } else if (redirect != null) {
      command.put("path", redirect);
    }
    a.end();
    return command;
  }

  private static Map<String, Object> uiautomator(Args a, String redirect) {
    String sub = a.required("uiautomator command");
    if (!sub.equals("dump")) throw new UnsupportedException("uiautomator " + sub);
    Map<String, Object> command = cmd("hierarchy");
    command.put("flat", true);
    while (a.hasNext() && a.peek().startsWith("--")) a.next(); // --compressed
    if (a.hasNext()) {
      command.put("path", a.next());
      command.put("devicePath", true);
    } else if (redirect != null) {
      command.put("path", redirect);
    }
    a.end();
    return command;
  }

  private static Map<String, Object> monkey(Args a) {
    Map<String, Object> command = cmd("launch");
    String count = null;
    while (a.hasNext()) {
      String option = a.next();
      if (option.equals("-p")) command.put("package", a.required(option));
      else if (option.equals("-c") || option.equals("--throttle") || option.equals("-s")) {
        a.required(option);
      } else if (option.startsWith("-v")) {
        // Verbosity.
      } else if (!option.startsWith("-")) {
        count = option;
      } else {
        throw new UnsupportedException("monkey " + option + " is not supported");
      }
    }
    if (count != null && !count.equals("1")) {
      throw new UnsupportedException("monkey runs only as a launcher (an event count of 1)");
    }
    return command;
  }

  /** {@code run-as <pkg> ls|cat|rm|mkdir}, and the same against {@code /data/data/<pkg>} paths. */
  private static Map<String, Object> files(Args a, String pkg, String redirect) {
    String program = a.required("command");
    Map<String, Object> command = cmd("files");
    if (pkg != null) command.put("package", pkg);
    boolean recursive = false;
    while (a.hasNext() && a.peek().startsWith("-") && a.peek().length() > 1) {
      String option = a.next();
      if (option.contains("r") || option.contains("R")) recursive = true;
    }
    switch (program) {
      case "ls":
        command.put("op", "list");
        command.put("path", a.hasNext() ? a.next() : ".");
        if (recursive) command.put("recursive", true);
        break;
      case "cat":
        command.put("op", redirect == null ? "read" : "pull");
        command.put("path", a.required("path"));
        if (redirect != null) command.put("to", redirect);
        a.end();
        return command;
      case "rm":
        command.put("op", "delete");
        command.put("path", a.required("path"));
        if (recursive) command.put("recursive", true);
        break;
      case "mkdir":
        command.put("op", "mkdir");
        command.put("path", a.required("path"));
        break;
      default:
        throw new UnsupportedException(
            "run-as " + program + " is not supported: ls, cat, rm and mkdir are");
    }
    a.end();
    if (redirect != null) throw new UnsupportedException("'> file' works with cat only");
    return command;
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Whether a file argument names the simulated device's storage ({@code AppFiles} maps it) rather
   * than the host: a relative path (the app's data directory, as {@code run-as} sees it), or one
   * under {@code /data/data}, {@code /data/user}, {@code /sdcard}, {@code /storage} or {@code
   * /data/local/tmp}. Any other absolute path is the host's.
   */
  static boolean isDevicePath(String path) {
    if (!path.startsWith("/")) return true;
    for (String prefix :
        new String[] {"/data/data/", "/data/user/", "/data/user_de/", "/data/local/tmp"}) {
      if (path.startsWith(prefix)) return true;
    }
    for (String root : new String[] {"/sdcard", "/storage"}) {
      if (path.equals(root) || path.startsWith(root + "/")) return true;
    }
    return false;
  }

  private static void skipUser(Args a) {
    while (a.hasNext() && a.peek().equals("--user")) {
      a.next();
      a.required("--user");
    }
  }

  private static Map<String, Object> cmd(String name) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("cmd", name);
    return command;
  }

  private static Map<String, Object> typed(String type, Object value) {
    Map<String, Object> typed = new LinkedHashMap<>();
    typed.put("type", type);
    typed.put("value", value);
    return typed;
  }

  /** {@code --activity-clear-top} → {@code CLEAR_TOP}. */
  private static String constant(String option) {
    return option.replace('-', '_').toUpperCase(Locale.ROOT);
  }

  private static int parseInt(String value, String what) {
    try {
      if (value.startsWith("0x") || value.startsWith("0X")) {
        return (int) Long.parseLong(value.substring(2), 16);
      }
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new UnsupportedException(what + " expects an integer, not " + value);
    }
  }

  private static long parseLong(String value, String what) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new UnsupportedException(what + " expects an integer, not " + value);
    }
  }

  private static double parseDouble(String value, String what) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new UnsupportedException(what + " expects a number, not " + value);
    }
  }

  private static boolean parseBoolean(String value) {
    switch (value.toLowerCase(Locale.ROOT)) {
      case "true":
      case "t":
      case "1":
        return true;
      case "false":
      case "f":
      case "0":
        return false;
      default:
        throw new UnsupportedException("expected true or false, not " + value);
    }
  }

  /** A cursor over the arguments. */
  static final class Args {
    private final List<String> argv;
    private int next;

    Args(List<String> argv) {
      this.argv = argv;
    }

    boolean hasNext() {
      return next < argv.size();
    }

    String peek() {
      return argv.get(next);
    }

    String next() {
      return argv.get(next++);
    }

    void back() {
      next--;
    }

    String required(String what) {
      if (!hasNext()) throw new UnsupportedException("missing " + what);
      return next();
    }

    List<String> rest() {
      List<String> rest = new ArrayList<>(argv.subList(next, argv.size()));
      next = argv.size();
      return rest;
    }

    void end() {
      if (hasNext()) {
        throw new UnsupportedException("unexpected arguments: " + String.join(" ", rest()));
      }
    }
  }

  private AdbShell() {}
}
