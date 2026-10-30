package com.google.android.gms.tasks;

/** Test stand-in for Play services' {@code Tasks}: a completed task is a {@link Done}. */
public final class Tasks {
  private Tasks() {}

  public static final class Done {
    public final Object result;
    public final Exception exception;

    Done(Object result, Exception exception) {
      this.result = result;
      this.exception = exception;
    }
  }

  public static Object forResult(Object result) {
    return new Done(result, null);
  }

  public static Object forException(Exception exception) {
    return new Done(null, exception);
  }
}
