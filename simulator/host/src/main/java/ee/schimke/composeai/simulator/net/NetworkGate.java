package ee.schimke.composeai.simulator.net;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the app's real traffic sees of the simulated network, JVM-wide: offline (no network, or one
 * without internet), connections fail as a device's do — a host name with {@code
 * UnknownHostException: Unable to resolve host "…": No address associated with hostname}, an
 * address with {@code ConnectException: … ENETUNREACH (Network is unreachable)} — and connections
 * already open are cut. Online, latency and a bandwidth cap can be added, and hosts blocked.
 *
 * <p>Three {@code java.net} seams, each installed once and passing traffic through untouched until
 * {@link #configure} says otherwise:
 *
 * <ul>
 *   <li>a {@link ProxySelector} that, while a host is unreachable, answers {@code DIRECT}: a host
 *       JVM behind an HTTP proxy would otherwise hand the name to the proxy, and never resolve it;
 *   <li>the JVM's {@code InetAddress} resolver (Java 18+), wrapped to fail lookups while offline,
 *       its cache cleared on each change;
 *   <li>a {@link java.net.SocketImplFactory} whose sockets wrap the platform's, to refuse a
 *       connect, close what is open, and shape the streams.
 * </ul>
 *
 * Clients on {@code java.net.Socket} and {@code ProxySelector} — OkHttp, {@code HttpURLConnection},
 * Ktor's OkHttp and Android engines — are covered; NIO channels (Ktor CIO, {@code
 * java.net.http.HttpClient}) and SOCKS proxies bypass the socket seam, and see only the resolver.
 *
 * <p>Pure {@code java.net}, and kept out of Robolectric's sandbox ({@code SimulatorSandbox} does
 * not acquire this package), so the launcher and the sandbox share one copy, and its clock is the
 * host's rather than the simulated one. Needs {@code --add-opens java.base/java.net}.
 */
public final class NetworkGate {

  private static volatile boolean internet = true;
  private static volatile long latencyMs;
  private static volatile long kbps;
  private static volatile List<String> blocked = Collections.emptyList();

  private static boolean installed;
  private static final List<String> seams = new ArrayList<>();
  private static final Set<GatedSocketImpl> open =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  private static final AtomicLong refused = new AtomicLong();
  private static final AtomicLong cut = new AtomicLong();

  /** Installs the seams; later calls do nothing. Returns the seams in place. */
  public static synchronized List<String> install() {
    if (installed) return seams;
    installed = true;
    ProxySelector.setDefault(new GatedProxySelector(ProxySelector.getDefault()));
    seams.add("proxySelector");
    if (Resolver.install()) seams.add("resolver");
    if (GatedSocketImpl.supported()) {
      try {
        Socket.setSocketImplFactory(() -> new GatedSocketImpl(GatedSocketImpl.platform()));
        seams.add("sockets");
      } catch (IOException e) {
        // Another factory is already set: leave it.
      }
    }
    return seams;
  }

  /**
   * The traffic the simulated network allows. [internet] false refuses every connection that leaves
   * the machine; [latencyMs] delays each connect and each response; [kbps] caps each socket's
   * throughput both ways (0: no cap); [blockedHosts] are refused even online (a name, or {@code
   * *.suffix}).
   */
  public static synchronized Map<String, Object> configure(
      boolean internet, long latencyMs, long kbps, Collection<String> blockedHosts) {
    boolean cutOff = NetworkGate.internet && !internet;
    List<String> before = blocked;
    NetworkGate.internet = internet;
    NetworkGate.latencyMs = Math.max(0, latencyMs);
    NetworkGate.kbps = Math.max(0, kbps);
    List<String> hosts = new ArrayList<>();
    for (String host : blockedHosts) hosts.add(host.toLowerCase(Locale.ROOT));
    blocked = Collections.unmodifiableList(hosts);
    Resolver.clearCache();
    if (cutOff || !before.containsAll(hosts)) {
      List<GatedSocketImpl> sockets;
      synchronized (open) {
        sockets = new ArrayList<>(open);
      }
      for (GatedSocketImpl socket : sockets) {
        if (refusal(socket.host(), socket.remote()) != null) {
          socket.cut();
          cut.incrementAndGet();
        }
      }
    }
    return state();
  }

  public static Map<String, Object> state() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("internet", internet);
    state.put("latencyMs", latencyMs);
    state.put("kbps", kbps);
    state.put("blocked", new ArrayList<>(blocked));
    state.put("seams", new ArrayList<>(seams));
    state.put("refused", refused.get());
    state.put("cut", cut.get());
    return state;
  }

  static long latencyMs() {
    return latencyMs;
  }

  static long kbps() {
    return kbps;
  }

  static void opened(GatedSocketImpl socket) {
    open.add(socket);
  }

  static void closed(GatedSocketImpl socket) {
    open.remove(socket);
  }

  /** Whether a lookup or connection to [host] must fail now. */
  static boolean unreachable(String host) {
    if (host == null) return !internet;
    String name = host.toLowerCase(Locale.ROOT);
    if (isBlocked(name)) return true;
    if (internet) return false;
    return !isLocalName(name);
  }

  static boolean isBlocked(String host) {
    if (host == null) return false;
    String name = host.toLowerCase(Locale.ROOT);
    for (String rule : blocked) {
      if (rule.startsWith("*.")) {
        if (name.endsWith(rule.substring(1)) || name.equals(rule.substring(2))) return true;
      } else if (name.equals(rule)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLocalName(String host) {
    return host.equals("localhost") || host.endsWith(".localhost");
  }

  /**
   * The exception a connect to [host] (the name it was looked up by, or a literal) at [address]
   * fails with now, or null when it may go ahead. Offline, the machine itself stays reachable,
   * except a configured proxy: through it, the host's traffic reaches the internet.
   */
  static IOException refusal(String host, InetSocketAddress address) {
    boolean literal = host == null || isLiteral(host);
    if (!literal && isBlocked(host)) return unknownHost(host);
    InetAddress ip = address == null ? null : address.getAddress();
    if (ip != null && isBlocked(ip.getHostAddress())) return unreachable(ip, address.getPort());
    if (internet) return null;
    boolean local = ip != null && (ip.isLoopbackAddress() || ip.isAnyLocalAddress());
    if (local && !isProxy(address)) return null;
    if (!literal && !isLocalName(host.toLowerCase(Locale.ROOT))) return unknownHost(host);
    return unreachable(ip, address == null ? 0 : address.getPort());
  }

  static UnknownHostException unknownHost(String host) {
    return new UnknownHostException(
        "Unable to resolve host \"" + host + "\": No address associated with hostname");
  }

  private static ConnectException unreachable(InetAddress ip, int port) {
    return new ConnectException(
        "failed to connect to "
            + (ip == null ? "?" : "/" + ip.getHostAddress())
            + " (port "
            + port
            + ") after 0ms: connect failed: ENETUNREACH (Network is unreachable)");
  }

  static void countRefusal() {
    refused.incrementAndGet();
  }

  static boolean isLiteral(String host) {
    if (host.isEmpty()) return true;
    if (host.indexOf(':') >= 0) return true; // IPv6.
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (c != '.' && (c < '0' || c > '9')) return false;
    }
    return true;
  }

  /** Whether [address] is a proxy the host JVM is configured with ({@code http.proxyHost}...). */
  private static boolean isProxy(InetSocketAddress address) {
    if (address == null) return false;
    for (String scheme : new String[] {"http", "https", "socks"}) {
      String host =
          System.getProperty(scheme.equals("socks") ? "socksProxyHost" : scheme + ".proxyHost");
      if (host == null || host.isEmpty()) continue;
      String portProperty = scheme.equals("socks") ? "socksProxyPort" : scheme + ".proxyPort";
      int port;
      try {
        port =
            Integer.parseInt(
                System.getProperty(portProperty, scheme.equals("socks") ? "1080" : "80"));
      } catch (NumberFormatException e) {
        continue;
      }
      if (port != address.getPort()) continue;
      if (host.equalsIgnoreCase(address.getHostString())) return true;
      InetAddress ip = address.getAddress();
      if (ip != null
          && (host.equals(ip.getHostAddress()) || isLocalName(host) && ip.isLoopbackAddress())) {
        return true;
      }
    }
    return false;
  }

  /** Answers {@code DIRECT} for a host that is unreachable, so the client resolves it here. */
  private static final class GatedProxySelector extends ProxySelector {
    private final ProxySelector delegate;

    GatedProxySelector(ProxySelector delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
      if (delegate == null || unreachable(uri.getHost())) {
        return Collections.singletonList(java.net.Proxy.NO_PROXY);
      }
      return delegate.select(uri);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException e) {
      if (delegate != null) delegate.connectFailed(uri, address, e);
    }
  }

  /**
   * The JVM's {@code InetAddressResolver} (Java 18+), wrapped. Reached by reflection, since the
   * module compiles for Java 17 and the field is private.
   */
  private static final class Resolver {
    private static Field cache;
    private static Field expirySet;

    static boolean install() {
      try {
        Class<?> type = Class.forName("java.net.spi.InetAddressResolver");
        Method current = InetAddress.class.getDeclaredMethod("resolver");
        current.setAccessible(true);
        Object builtin = current.invoke(null);
        Method lookupByName =
            type.getMethod(
                "lookupByName",
                String.class,
                Class.forName("java.net.spi.InetAddressResolver$LookupPolicy"));
        Object wrapped =
            Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                  if (method.equals(lookupByName) && unreachable((String) args[0])) {
                    countRefusal();
                    throw unknownHost((String) args[0]);
                  }
                  try {
                    return method.invoke(builtin, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
        Field field = InetAddress.class.getDeclaredField("resolver");
        field.setAccessible(true);
        field.set(null, wrapped);
        cache = InetAddress.class.getDeclaredField("cache");
        cache.setAccessible(true);
        expirySet = InetAddress.class.getDeclaredField("expirySet");
        expirySet.setAccessible(true);
        return true;
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Java 17, or java.net not opened: lookups go through untouched.
        return false;
      }
    }

    /** Forgets cached lookups, so a change applies to the next one. */
    static void clearCache() {
      try {
        if (cache != null) ((Map<?, ?>) cache.get(null)).clear();
        if (expirySet != null) ((Collection<?>) expirySet.get(null)).clear();
      } catch (ReflectiveOperationException e) {
        // Left to expire.
      }
    }
  }

  private NetworkGate() {}
}
