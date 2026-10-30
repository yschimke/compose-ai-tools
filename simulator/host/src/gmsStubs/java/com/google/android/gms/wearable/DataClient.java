// Compile-only stub of Play services' API; see `gmsStubs` in build.gradle.kts. Never packaged.
package com.google.android.gms.wearable;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.common.api.Releasable;
import com.google.android.gms.tasks.Task;
import java.io.InputStream;

public abstract class DataClient extends GoogleApi {
  public static final int FILTER_LITERAL = 0;
  public static final int FILTER_PREFIX = 1;

  public DataClient(Context context, GoogleApi.Settings settings) {}

  public abstract Task<DataItem> putDataItem(PutDataRequest request);

  public abstract Task<DataItem> getDataItem(Uri uri);

  public abstract Task<DataItemBuffer> getDataItems();

  public abstract Task<DataItemBuffer> getDataItems(Uri uri);

  public abstract Task<DataItemBuffer> getDataItems(Uri uri, int filterType);

  public abstract Task<Integer> deleteDataItems(Uri uri);

  public abstract Task<Integer> deleteDataItems(Uri uri, int filterType);

  public abstract Task<GetFdForAssetResponse> getFdForAsset(Asset asset);

  public abstract Task<GetFdForAssetResponse> getFdForAsset(DataItemAsset asset);

  public abstract Task<Void> addListener(OnDataChangedListener listener);

  public abstract Task<Void> addListener(OnDataChangedListener listener, Uri uri, int filterType);

  public abstract Task<Boolean> removeListener(OnDataChangedListener listener);

  public interface OnDataChangedListener {
    void onDataChanged(DataEventBuffer events);
  }

  public abstract static class GetFdForAssetResponse implements Releasable {
    public GetFdForAssetResponse() {}

    public abstract ParcelFileDescriptor getFdForAsset();

    public abstract InputStream getInputStream();
  }
}
