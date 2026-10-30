package ee.schimke.composeai.simulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The permission controller's side of a runtime permission request, without Android: which of the
 * requested permissions it answers on its own (already granted, not in the manifest, denied for
 * good, granted with their group, or a request the platform refuses) and which it asks the user
 * about; and what each answer leaves behind — granted, denied once (so {@code
 * shouldShowRequestPermissionRationale} is true), or denied twice and not asked again, as since
 * Android 11. {@link Permissions} applies it to Robolectric.
 */
final class PermissionModel {

  static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
  static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
  static final String BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION";
  static final String BODY_SENSORS = "android.permission.BODY_SENSORS";
  static final String BODY_SENSORS_BACKGROUND = "android.permission.BODY_SENSORS_BACKGROUND";
  static final String VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";

  /** How a permission in a request was answered. */
  enum How {
    /** By the user, in the dialog: the agent's answer. */
    DIALOG("dialog"),
    /** Granted before: no dialog. */
    ALREADY_GRANTED("alreadyGranted"),
    /** Not in the manifest: denied without asking. */
    NOT_IN_MANIFEST("notInManifest"),
    /** Not a runtime permission: its install-time state. */
    INSTALL_TIME("installTime"),
    /** Denied twice before: denied without asking until the user changes it in Settings. */
    DONT_ASK_AGAIN("dontAskAgain"),
    /** Another permission of its group is granted: granted without asking. */
    GROUP_GRANTED("groupGranted"),
    /** A request the platform refuses, as it logs: denied without asking. */
    IGNORED("ignored");

    final String json;

    How(String json) {
      this.json = json;
    }
  }

  /** One permission of a request: decided, or {@link How#DIALOG} until the user answers. */
  static final class Decision {
    final String name;
    How how;
    boolean granted;

    /** Why the platform refused it, for {@link How#IGNORED}. */
    String reason;

    Decision(String name) {
      this.name = name;
    }

    boolean waiting() {
      return how == How.DIALOG;
    }
  }

  /** What the user did in the dialog, for one permission. */
  enum Answer {
    /** "Allow" (or "While using the app"). */
    GRANT,
    /** "Don't allow": counts towards "don't ask again". */
    DENY,
    /** Backed out of the dialog: denied, and not counted, as since Android 11. */
    DISMISS;

    static Answer parse(String value) {
      switch (value) {
        case "grant":
        case "allow":
          return GRANT;
        case "deny":
          return DENY;
        case "dismiss":
          return DISMISS;
        default:
          throw new IllegalArgumentException("answer is grant, deny or dismiss, not " + value);
      }
    }
  }

  /**
   * Platform permission groups whose permissions the controller grants together: once one is
   * granted, a request for another is granted without a dialog. Location (precise is an upgrade the
   * user is asked about), the visual media group (partial access asks again), notifications,
   * camera and microphone are deliberately absent.
   */
  private static final List<Set<String>> GROUPS =
      Arrays.asList(
          group("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS"),
          group("READ_CALENDAR", "WRITE_CALENDAR"),
          group("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS"),
          group(
              "READ_PHONE_STATE",
              "READ_PHONE_NUMBERS",
              "CALL_PHONE",
              "ANSWER_PHONE_CALLS",
              "USE_SIP",
              "ACCEPT_HANDOVER",
              "com.android.voicemail.permission.ADD_VOICEMAIL"),
          group("SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS"),
          group(
              "BLUETOOTH_SCAN",
              "BLUETOOTH_CONNECT",
              "BLUETOOTH_ADVERTISE",
              "NEARBY_WIFI_DEVICES",
              "UWB_RANGING",
              "RANGING"),
          group("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"));

  private static Set<String> group(String... names) {
    Set<String> set = new HashSet<>();
    for (String n : names) set.add(n.contains(".") ? n : "android.permission." + n);
    return set;
  }

  private final Set<String> manifest;
  private final Predicate<String> runtime;
  private final int sdk;
  private final int targetSdk;
  private final Set<String> granted = new HashSet<>();

  /** Times the user denied each permission in the dialog since it was last granted. */
  private final Map<String, Integer> denials = new HashMap<>();

  PermissionModel(Collection<String> manifest, Predicate<String> runtime, int sdk, int targetSdk) {
    this.manifest = new HashSet<>(manifest);
    this.runtime = runtime;
    this.sdk = sdk;
    this.targetSdk = targetSdk;
  }

  boolean inManifest(String name) {
    return manifest.contains(name);
  }

  boolean isRuntime(String name) {
    return runtime.test(name);
  }

  boolean isGranted(String name) {
    return granted.contains(name);
  }

  /** Sets a permission's state from outside a request: install, Settings, {@code pm grant}. */
  void set(String name, boolean grant) {
    if (grant) {
      granted.add(name);
      denials.remove(name);
    } else {
      granted.remove(name);
    }
  }

  /** As after a fresh install: every runtime permission revoked and never asked about. */
  void reset() {
    granted.removeIf(runtime);
    denials.clear();
  }

  /**
   * Whether the app should show a rationale: denied once in the dialog, and not for good. False
   * once granted, before the first request, and after "don't ask again".
   */
  boolean rationale(String name) {
    return !granted.contains(name) && denials.getOrDefault(name, 0) == 1;
  }

  /** Denied twice in the dialog: further requests are denied without one. */
  boolean dontAskAgain(String name) {
    return !granted.contains(name) && denials.getOrDefault(name, 0) >= 2;
  }

  /**
   * Decides what the controller answers on its own for each of [names], in order (duplicates
   * included, as the result arrays carry them); the rest wait for the user.
   */
  List<Decision> triage(String[] names) {
    Set<String> requested = new HashSet<>(Arrays.asList(names));
    // Android 11+: background location (and, from 13, background body sensors) is its own,
    // later request; asked together with a foreground permission not yet granted, the platform
    // ignores the whole request for that group.
    boolean locationIgnored =
        sdk >= 30
            && targetSdk >= 30
            && requested.contains(BACKGROUND)
            && ((requested.contains(FINE) && !granted.contains(FINE))
                || (requested.contains(COARSE) && !granted.contains(COARSE)));
    boolean sensorsIgnored =
        sdk >= 33
            && requested.contains(BODY_SENSORS_BACKGROUND)
            && requested.contains(BODY_SENSORS)
            && !granted.contains(BODY_SENSORS);
    List<Decision> decisions = new ArrayList<>();
    for (String name : names) {
      Decision d = new Decision(name);
      decisions.add(d);
      if (!manifest.contains(name)) {
        d.how = How.NOT_IN_MANIFEST;
      } else if (!runtime.test(name)) {
        d.how = How.INSTALL_TIME;
        d.granted = granted.contains(name);
      } else if (granted.contains(name)) {
        d.how = How.ALREADY_GRANTED;
        d.granted = true;
      } else if (locationIgnored && isLocation(name)) {
        ignore(d, "background location must be requested on its own, after foreground location");
      } else if (sensorsIgnored && (name.equals(BODY_SENSORS) || name.equals(BODY_SENSORS_BACKGROUND))) {
        ignore(d, "BODY_SENSORS_BACKGROUND must be requested on its own, after BODY_SENSORS");
      } else if (name.equals(BACKGROUND)
          && sdk >= 30
          && targetSdk >= 30
          && !granted.contains(FINE)
          && !granted.contains(COARSE)) {
        ignore(d, "ACCESS_BACKGROUND_LOCATION needs foreground location granted first");
      } else if (name.equals(BODY_SENSORS_BACKGROUND)
          && sdk >= 33
          && !granted.contains(BODY_SENSORS)) {
        ignore(d, "BODY_SENSORS_BACKGROUND needs BODY_SENSORS granted first");
      } else if (name.equals(FINE)
          && sdk >= 31
          && targetSdk >= 31
          && !requested.contains(COARSE)) {
        ignore(d, "ACCESS_FINE_LOCATION must be requested with ACCESS_COARSE_LOCATION");
      } else if (dontAskAgain(name)) {
        d.how = How.DONT_ASK_AGAIN;
      } else if (groupGranted(name)) {
        d.how = How.GROUP_GRANTED;
        d.granted = true;
      } else {
        d.how = How.DIALOG;
      }
    }
    for (Decision d : decisions) {
      if (d.how == How.GROUP_GRANTED) set(d.name, true);
    }
    return decisions;
  }

  private static void ignore(Decision d, String reason) {
    d.how = How.IGNORED;
    d.reason = reason;
  }

  private static boolean isLocation(String name) {
    return name.equals(FINE) || name.equals(COARSE) || name.equals(BACKGROUND);
  }

  private boolean groupGranted(String name) {
    for (Set<String> group : GROUPS) {
      if (!group.contains(name)) continue;
      for (String other : group) if (granted.contains(other)) return true;
    }
    return false;
  }

  /** The permissions of [decisions] still waiting for the user, once each, in request order. */
  static List<String> waiting(List<Decision> decisions) {
    List<String> names = new ArrayList<>();
    Set<String> added = new HashSet<>();
    for (Decision d : decisions) if (d.waiting() && added.add(d.name)) names.add(d.name);
    return names;
  }

  /**
   * The user's answer for each waiting permission of a request, from a whole-dialog answer: {@code
   * grant}, {@code deny}, {@code dismiss}, {@code approximate} (location: coarse granted, precise
   * not; anything else granted) or {@code selected} (photos and videos: only the user-selected
   * ones, {@code READ_MEDIA_VISUAL_USER_SELECTED}; anything else granted).
   */
  static Map<String, Answer> expand(List<String> waiting, String answer) {
    Map<String, Answer> answers = new LinkedHashMap<>();
    for (String name : waiting) {
      Answer a;
      switch (answer) {
        case "approximate":
          a = name.equals(FINE) ? Answer.DISMISS : Answer.GRANT;
          break;
        case "selected":
          a =
              isVisualMedia(name) && !name.equals(VISUAL_USER_SELECTED)
                  ? Answer.DISMISS
                  : Answer.GRANT;
          break;
        default:
          try {
            a = Answer.parse(answer);
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "answer is grant, deny, dismiss, approximate or selected, or an object of"
                    + " permission → grant / deny / dismiss; not "
                    + answer);
          }
      }
      answers.put(name, a);
    }
    return answers;
  }

  private static boolean isVisualMedia(String name) {
    return name.equals("android.permission.READ_MEDIA_IMAGES")
        || name.equals("android.permission.READ_MEDIA_VIDEO")
        || name.equals(VISUAL_USER_SELECTED);
  }

  /** Applies the user's answers to the waiting permissions of [decisions]. */
  void answer(List<Decision> decisions, Map<String, Answer> answers) {
    Set<String> counted = new HashSet<>();
    for (Decision d : decisions) {
      if (!d.waiting()) continue;
      Answer a = answers.get(d.name);
      if (a == null) throw new IllegalArgumentException("no answer for " + d.name);
      d.granted = a == Answer.GRANT;
      if (a == Answer.GRANT) {
        set(d.name, true);
      } else {
        granted.remove(d.name);
        // A duplicate is one permission in one dialog: counted once.
        if (a == Answer.DENY && counted.add(d.name)) denials.merge(d.name, 1, Integer::sum);
      }
    }
  }
}
