package ee.schimke.composeai.simulator.net;

import java.io.FileDescriptor;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.net.SocketOptions;
import java.util.Set;

/**
 * A client socket that asks {@link NetworkGate} before it connects, can be cut while open, and
 * shapes its streams (latency before each response, a throughput cap). Everything else is the
 * platform's own socket, reached by reflection: {@code SocketImpl}'s methods are protected.
 */
final class GatedSocketImpl extends SocketImpl {

  private static final Method CREATE_PLATFORM;
  private static final Method CREATE;
  private static final Method CONNECT_ADDRESS;
  private static final Method CONNECT_HOST;
  private static final Method CONNECT_INET;
  private static final Method BIND;
  private static final Method LISTEN;
  private static final Method ACCEPT;
  private static final Method INPUT;
  private static final Method OUTPUT;
  private static final Method AVAILABLE;
  private static final Method CLOSE;
  private static final Method SHUTDOWN_INPUT;
  private static final Method SHUTDOWN_OUTPUT;
  private static final Method URGENT;
  private static final Method SUPPORTS_URGENT;
  private static final Method INET_ADDRESS;
  private static final Method PORT;
  private static final Method LOCAL_PORT;
  private static final Method FILE_DESCRIPTOR;
  private static final Method SET_OPTION;
  private static final Method GET_OPTION;
  private static final Method SUPPORTED_OPTIONS;

  static {
    Method[] methods = new Method[23];
    try {
      Class<SocketImpl> c = SocketImpl.class;
      methods[0] = c.getDeclaredMethod("createPlatformSocketImpl", boolean.class);
      methods[1] = c.getDeclaredMethod("create", boolean.class);
      methods[2] = c.getDeclaredMethod("connect", SocketAddress.class, int.class);
      methods[3] = c.getDeclaredMethod("connect", String.class, int.class);
      methods[4] = c.getDeclaredMethod("connect", InetAddress.class, int.class);
      methods[5] = c.getDeclaredMethod("bind", InetAddress.class, int.class);
      methods[6] = c.getDeclaredMethod("listen", int.class);
      methods[7] = c.getDeclaredMethod("accept", SocketImpl.class);
      methods[8] = c.getDeclaredMethod("getInputStream");
      methods[9] = c.getDeclaredMethod("getOutputStream");
      methods[10] = c.getDeclaredMethod("available");
      methods[11] = c.getDeclaredMethod("close");
      methods[12] = c.getDeclaredMethod("shutdownInput");
      methods[13] = c.getDeclaredMethod("shutdownOutput");
      methods[14] = c.getDeclaredMethod("sendUrgentData", int.class);
      methods[15] = c.getDeclaredMethod("supportsUrgentData");
      methods[16] = c.getDeclaredMethod("getInetAddress");
      methods[17] = c.getDeclaredMethod("getPort");
      methods[18] = c.getDeclaredMethod("getLocalPort");
      methods[19] = c.getDeclaredMethod("getFileDescriptor");
      methods[20] = c.getDeclaredMethod("setOption", SocketOption.class, Object.class);
      methods[21] = c.getDeclaredMethod("getOption", SocketOption.class);
      methods[22] = c.getDeclaredMethod("supportedOptions");
      for (Method m : methods) m.setAccessible(true);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // java.net is not opened to us: no socket gating.
      methods = new Method[23];
    }
    CREATE_PLATFORM = methods[0];
    CREATE = methods[1];
    CONNECT_ADDRESS = methods[2];
    CONNECT_HOST = methods[3];
    CONNECT_INET = methods[4];
    BIND = methods[5];
    LISTEN = methods[6];
    ACCEPT = methods[7];
    INPUT = methods[8];
    OUTPUT = methods[9];
    AVAILABLE = methods[10];
    CLOSE = methods[11];
    SHUTDOWN_INPUT = methods[12];
    SHUTDOWN_OUTPUT = methods[13];
    URGENT = methods[14];
    SUPPORTS_URGENT = methods[15];
    INET_ADDRESS = methods[16];
    PORT = methods[17];
    LOCAL_PORT = methods[18];
    FILE_DESCRIPTOR = methods[19];
    SET_OPTION = methods[20];
    GET_OPTION = methods[21];
    SUPPORTED_OPTIONS = methods[22];
  }

  static boolean supported() {
    if (SUPPORTED_OPTIONS == null) return false;
    try {
      platform();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  static SocketImpl platform() {
    try {
      return (SocketImpl) CREATE_PLATFORM.invoke(null, false);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private final SocketImpl delegate;
  private volatile String host;
  private volatile InetSocketAddress remote;
  private volatile boolean cut;

  /** Set by a write, cleared by the read after it: that read waits out the latency first. */
  private volatile boolean awaitingResponse;

  GatedSocketImpl(SocketImpl delegate) {
    this.delegate = delegate;
  }

  String host() {
    return host;
  }

  InetSocketAddress remote() {
    return remote;
  }

  /** Drops the connection, as a device does when its network goes. */
  void cut() {
    cut = true;
    try {
      call(CLOSE);
    } catch (IOException e) {
      // Already closed.
    }
  }

  private Object call(Method method, Object... args) throws IOException {
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) throw (IOException) cause;
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new IOException(cause);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object get(Method method) {
    try {
      return call(method);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void create(boolean stream) throws IOException {
    call(CREATE, stream);
  }

  @Override
  protected void connect(String host, int port) throws IOException {
    connect(new InetSocketAddress(host, port), 0);
  }

  @Override
  protected void connect(InetAddress address, int port) throws IOException {
    connect(new InetSocketAddress(address, port), 0);
  }

  @Override
  protected void connect(SocketAddress address, int timeout) throws IOException {
    InetSocketAddress target =
        address instanceof InetSocketAddress ? (InetSocketAddress) address : null;
    String name = target == null ? null : target.getHostString();
    IOException refusal = NetworkGate.refusal(name, target);
    if (refusal != null) {
      NetworkGate.countRefusal();
      try {
        call(CLOSE);
      } catch (IOException ignored) {
        // Nothing was open.
      }
      throw refusal;
    }
    pause(NetworkGate.latencyMs());
    call(CONNECT_ADDRESS, address, timeout);
    host = name;
    remote = target;
    NetworkGate.opened(this);
  }

  @Override
  protected void bind(InetAddress host, int port) throws IOException {
    call(BIND, host, port);
  }

  @Override
  protected void listen(int backlog) throws IOException {
    call(LISTEN, backlog);
  }

  @Override
  protected void accept(SocketImpl s) throws IOException {
    call(ACCEPT, s);
  }

  @Override
  protected InputStream getInputStream() throws IOException {
    InputStream in = (InputStream) call(INPUT);
    return new FilterInputStream(in) {
      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (awaitingResponse) {
          awaitingResponse = false;
          pause(NetworkGate.latencyMs());
        }
        checkCut();
        int n = super.read(b, off, limit(len));
        if (n > 0) throttle(n);
        return n;
      }
    };
  }

  @Override
  protected OutputStream getOutputStream() throws IOException {
    OutputStream out = (OutputStream) call(OUTPUT);
    return new FilterOutputStream(out) {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        checkCut();
        int done = 0;
        while (done < len) {
          int chunk = limit(len - done);
          out.write(b, off + done, chunk);
          throttle(chunk);
          done += chunk;
        }
        awaitingResponse = true;
      }
    };
  }

  private void checkCut() throws IOException {
    if (cut) {
      throw new java.net.SocketException("Software caused connection abort (network lost)");
    }
  }

  /** Under a cap, moves at most a tenth of a second's worth of bytes at a time. */
  private static int limit(int len) {
    long kbps = NetworkGate.kbps();
    if (kbps <= 0) return len;
    long perTenth = Math.max(1, kbps * 1000 / 8 / 10);
    return (int) Math.max(1, Math.min(len, perTenth));
  }

  private static void throttle(int bytes) {
    long kbps = NetworkGate.kbps();
    if (kbps > 0) pause(bytes * 8L / kbps);
  }

  private static void pause(long ms) {
    if (ms <= 0) return;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  protected int available() throws IOException {
    return (Integer) call(AVAILABLE);
  }

  @Override
  protected void close() throws IOException {
    NetworkGate.closed(this);
    call(CLOSE);
  }

  @Override
  protected void shutdownInput() throws IOException {
    call(SHUTDOWN_INPUT);
  }

  @Override
  protected void shutdownOutput() throws IOException {
    call(SHUTDOWN_OUTPUT);
  }

  @Override
  protected void sendUrgentData(int data) throws IOException {
    call(URGENT, data);
  }

  @Override
  protected boolean supportsUrgentData() {
    return (Boolean) get(SUPPORTS_URGENT);
  }

  @Override
  protected InetAddress getInetAddress() {
    return (InetAddress) get(INET_ADDRESS);
  }

  @Override
  protected int getPort() {
    return (Integer) get(PORT);
  }

  @Override
  protected int getLocalPort() {
    return (Integer) get(LOCAL_PORT);
  }

  @Override
  protected FileDescriptor getFileDescriptor() {
    return (FileDescriptor) get(FILE_DESCRIPTOR);
  }

  @Override
  protected <T> void setOption(SocketOption<T> name, T value) throws IOException {
    call(SET_OPTION, name, value);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected <T> T getOption(SocketOption<T> name) throws IOException {
    return (T) call(GET_OPTION, name);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Set<SocketOption<?>> supportedOptions() {
    return (Set<SocketOption<?>>) get(SUPPORTED_OPTIONS);
  }

  @Override
  public void setOption(int optID, Object value) throws java.net.SocketException {
    ((SocketOptions) delegate).setOption(optID, value);
  }

  @Override
  public Object getOption(int optID) throws java.net.SocketException {
    return ((SocketOptions) delegate).getOption(optID);
  }
}
