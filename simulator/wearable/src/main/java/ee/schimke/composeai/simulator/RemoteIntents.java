package ee.schimke.composeai.simulator;

import java.util.List;

/**
 * The decisions behind a remote activity launch ({@code RemoteActivityHelper.startRemoteActivity}),
 * kept free of Android types so they can be tested on a plain JVM.
 *
 * <p>On a device the app broadcasts {@code com.google.android.wearable.intent.action.REMOTE_INTENT}
 * to the Wear OS app, which starts the intent on the other node and answers the app's {@code
 * ResultReceiver} with {@link #RESULT_OK} or {@link #RESULT_FAILED}. In the paired simulator {@link
 * DataLayer} takes that broadcast, and these pick the node and the outcome.
 */
final class RemoteIntents {

  static final String ACTION_REMOTE_INTENT =
      "com.google.android.wearable.intent.action.REMOTE_INTENT";
  static final String EXTRA_INTENT = "com.google.android.wearable.intent.extra.INTENT";
  static final String EXTRA_NODE_ID = "com.google.android.wearable.intent.extra.NODE_ID";
  static final String EXTRA_RESULT_RECEIVER =
      "com.google.android.wearable.intent.extra.RESULT_RECEIVER";

  /**
   * The Wear OS app: the target of the {@code REMOTE_INTENT} broadcast, and what {@code
   * getCompanionPackageForNode} names on the watch ({@link PhoneCompanion} on the phone).
   */
  static final String COMPANION_PACKAGE = "com.google.android.wearable.app";

  /** {@code RemoteActivityHelper.RESULT_OK} / {@code RESULT_FAILED}. */
  static final int RESULT_OK = 0;

  static final int RESULT_FAILED = 1;

  /** What the receiving node does with a remote intent. */
  enum Outcome {
    /** An activity of the app on that node handles it: started there. */
    START,
    /**
     * Nothing in the app handles it, but on a device the system would (a web page opens in the
     * browser, {@code market://} in the Play Store), none of which the simulator has.
     */
    SYSTEM,
    /** Nothing on that node handles it. */
    FAIL,
  }

  /**
   * The node a remote intent goes to: the one asked for, when it is connected, or with none named
   * (a watch's call, which means its phone) the first connected node; null when there is none.
   */
  static String target(String requested, List<String> peers) {
    if (requested != null) return peers.contains(requested) ? requested : null;
    return peers.isEmpty() ? null : peers.get(0);
  }

  /**
   * The outcome of starting a remote intent with [scheme] and [targetPackage] on a node running
   * [appPackage], given whether an activity of that app resolves it.
   */
  static Outcome outcome(
      boolean resolvesInApp, String scheme, String targetPackage, String appPackage) {
    boolean forThisApp = targetPackage == null || targetPackage.equals(appPackage);
    if (resolvesInApp && forThisApp) return Outcome.START;
    if (targetPackage == null && systemHandles(scheme)) return Outcome.SYSTEM;
    return Outcome.FAIL;
  }

  /** The schemes a device's browser or Play Store take. */
  static boolean systemHandles(String scheme) {
    return "http".equals(scheme) || "https".equals(scheme) || "market".equals(scheme);
  }

  private RemoteIntents() {}
}
