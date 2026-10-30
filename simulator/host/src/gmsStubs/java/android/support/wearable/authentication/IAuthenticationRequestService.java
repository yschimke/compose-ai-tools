// Compile-only stub of androidx.wear:wear-phone-interactions' AIDL; see `gmsStubs` in
// build.gradle.kts. Never packaged: the app's own copy is used at run time.
package android.support.wearable.authentication;

import android.os.Binder;
import android.os.Bundle;
import android.os.IInterface;
import android.os.RemoteException;

public interface IAuthenticationRequestService extends IInterface {
  void openUrl(Bundle request, IAuthenticationRequestCallback callback) throws RemoteException;

  int getApiVersion() throws RemoteException;

  abstract class Stub extends Binder implements IAuthenticationRequestService {
    public Stub() {}
  }
}
