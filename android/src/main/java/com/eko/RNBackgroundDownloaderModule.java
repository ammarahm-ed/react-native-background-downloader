package com.eko;

import android.annotation.SuppressLint;
import android.os.Environment;
import android.util.Log;
import android.os.Build;
import android.os.StatFs;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Callback;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2core.DownloadBlock;
import com.tonyodev.fetch2core.Func;
import com.tonyodev.fetch2.FetchConfiguration;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchListener;
import com.tonyodev.fetch2.NetworkType;
import com.tonyodev.fetch2.Priority;
import com.tonyodev.fetch2.Request;
import com.tonyodev.fetch2.EnqueueAction;
import com.tonyodev.fetch2.Status;
import com.tonyodev.fetch2.DefaultFetchNotificationManager;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

import javax.annotation.Nullable;

public class RNBackgroundDownloaderModule extends ReactContextBaseJavaModule implements FetchListener {

  private static final int TASK_RUNNING = 0;
  private static final int TASK_SUSPENDED = 1;
  private static final int TASK_CANCELING = 2;
  private static final int TASK_COMPLETED = 3;

  private static Map<Status, Integer> stateMap = new HashMap<Status, Integer>() {
    {
      put(Status.DOWNLOADING, TASK_RUNNING);
      put(Status.COMPLETED, TASK_COMPLETED);
      put(Status.PAUSED, TASK_SUSPENDED);
      put(Status.QUEUED, TASK_RUNNING);
      put(Status.CANCELLED, TASK_CANCELING);
      put(Status.FAILED, TASK_CANCELING);
      put(Status.REMOVED, TASK_CANCELING);
      put(Status.DELETED, TASK_CANCELING);
      put(Status.NONE, TASK_CANCELING);
    }
  };

  private Fetch fetch;
  private Map<String, Integer> idToRequestId = new HashMap<>();
  @SuppressLint("UseSparseArrays")
  private Map<Integer, RNBGDTaskConfig> requestIdToConfig = new HashMap<>();
  private DeviceEventManagerModule.RCTDeviceEventEmitter ee;
  private Date lastProgressReport = new Date();
  private HashMap<String, WritableMap> progressReports = new HashMap<>();

  public RNBackgroundDownloaderModule(ReactApplicationContext reactContext) {
    super(reactContext);
    loadConfigMap();
    final FetchConfiguration fetchConfiguration = new FetchConfiguration.Builder(this.getReactApplicationContext())
        .setDownloadConcurrentLimit(4)
        .setNotificationManager(new DefaultFetchNotificationManager(this.getReactApplicationContext()) {
          @NotNull
          @Override
          public Fetch getFetchInstanceForNamespace(@NotNull String s) {
            return fetch;
          }
        }).build();
    fetch = Fetch.Impl.getInstance(fetchConfiguration);
    fetch.addListener(this);
  }

  @Override
  public void onCatalystInstanceDestroy() {
    fetch.close();
  }

  @Override
  public String getName() {
    return "RNBackgroundDownloader";
  }

  @Override
  public void initialize() {
    ee = getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
  }

  @Nullable
  @Override
  public Map<String, Object> getConstants() {
    Map<String, Object> constants = new HashMap<>();
    constants.put("documents", this.getReactApplicationContext().getFilesDir().getAbsolutePath());
    constants.put("downloads",
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
    constants.put("music",
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());
    constants.put("freeDiskStorage", this.getFreeDiskStorage());
    constants.put("TaskRunning", TASK_RUNNING);
    constants.put("TaskSuspended", TASK_SUSPENDED);
    constants.put("TaskCanceling", TASK_CANCELING);
    constants.put("TaskCompleted", TASK_COMPLETED);
    constants.put("PriorityHigh", Priority.HIGH.getValue());
    constants.put("PriorityNormal", Priority.NORMAL.getValue());
    constants.put("PriorityLow", Priority.LOW.getValue());
    constants.put("OnlyWifi", NetworkType.WIFI_ONLY.getValue());
    constants.put("AllNetworks", NetworkType.ALL.getValue());
    return constants;
  }

  private void removeFromMaps(int requestId) {
    RNBGDTaskConfig config = requestIdToConfig.get(requestId);
    if (config != null) {
      idToRequestId.remove(config.id);
      requestIdToConfig.remove(requestId);

      saveConfigMap();
    }
  }

  private void saveConfigMap() {
    File file = new File(this.getReactApplicationContext().getFilesDir(), "RNFileBackgroundDownload_configMap");
    try {
      if(!file.canWrite()) return;
      ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(file));
      outputStream.writeObject(requestIdToConfig);
      outputStream.flush();
      outputStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (ConcurrentModificationException e){
      e.printStackTrace();
    }
  }

  private void loadConfigMap() {
    File file = new File(this.getReactApplicationContext().getFilesDir(), "RNFileBackgroundDownload_configMap");
    try {
      if(!file.exists()) return;
      ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(file));
      requestIdToConfig = (Map<Integer, RNBGDTaskConfig>) inputStream.readObject();
    } catch (IOException | ClassNotFoundException e) {
      e.printStackTrace();
    }
  }

  @ReactMethod
  public void deleteFile(String path, Callback callback) {
    File fdelete = new File(path);
    if (fdelete.exists()) {
      callback.invoke(fdelete.delete());
    } else
      callback.invoke(false);
  }

  @ReactMethod
  public void fileExists(String path, Callback callback) {
    File file = new File(path);
    callback.invoke(file.exists());
  }

  // JS Methods
  @ReactMethod
  public void download(ReadableMap options) {
    String id = options.getString("id");
    String url = options.getString("url");
    String destination = options.getString("destination");
    ReadableMap headers = options.getMap("headers");

    if (id == null || url == null || destination == null) {
      Log.e(getName(), "id, url and destination must be set");
      return;
    }

    RNBGDTaskConfig config = new RNBGDTaskConfig(id);
    Request request = new Request(url, destination);
    if (headers != null) {
      ReadableMapKeySetIterator it = headers.keySetIterator();
      while (it.hasNextKey()) {
        String headerKey = it.nextKey();
        request.addHeader(headerKey, headers.getString(headerKey));
      }
    }
    request.setEnqueueAction(EnqueueAction.REPLACE_EXISTING);
    request.setPriority(options.hasKey("priority") ? Priority.valueOf(options.getInt("priority")) : Priority.NORMAL);
    request
        .setNetworkType(options.hasKey("network") ? NetworkType.valueOf(options.getInt("network")) : NetworkType.ALL);
    fetch.enqueue(request, null, null);

    idToRequestId.put(id, request.getId());
    requestIdToConfig.put(request.getId(), config);
    saveConfigMap();
  }

  @ReactMethod
  public void pauseTask(String identifier) {
    Integer requestId = idToRequestId.get(identifier);
    if (requestId != null) {
      fetch.pause(requestId);
    }
  }

  @ReactMethod
  public void resumeTask(String identifier) {
    Integer requestId = idToRequestId.get(identifier);
    if (requestId != null) {
      fetch.resume(requestId);
    }
  }

  @ReactMethod
  public void stopTask(String identifier) {
    Integer requestId = idToRequestId.get(identifier);
    if (requestId != null) {
      fetch.cancel(requestId);
    }
  }

  @ReactMethod
  public void deleteTask(String identifier) {
    Integer requestId = idToRequestId.get(identifier);
    if (requestId != null) {
      removeFromMaps(requestId);
      fetch.delete(requestId);
    }
  }

  @ReactMethod
  public void retryTask(String identifier) {
    Integer requestId = idToRequestId.get(identifier);
    if (requestId != null) {
      fetch.retry(requestId);
    }
  }

  @ReactMethod
  public void checkForExistingDownloads(final Promise promise) {
    fetch.getDownloads(new Func<List<Download>>() {
      @Override
      public void call(List<Download> downloads) {
        WritableArray foundIds = Arguments.createArray();

        for (Download download : downloads) {
          if (requestIdToConfig.containsKey(download.getId())) {
            RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
            if(config == null || config.id == null || config.id.isEmpty())
              return;
            WritableMap params = Arguments.createMap();
            params.putString("id", config.id);
            params.putInt("state", stateMap.get(download.getStatus()));
            params.putInt("bytesWritten", (int) download.getDownloaded());
            params.putInt("totalBytes", (int) download.getTotal());
            params.putDouble("percent", ((double) download.getProgress()) / 100);

            foundIds.pushMap(params);

            idToRequestId.put(config.id, download.getId());
            config.reportedBegin = true;
          } else {
            fetch.delete(download.getId());
          }
        }

        promise.resolve(foundIds);
      }
    });
  }

  @Override
  public void onStarted(@NonNull Download download, @NonNull List<? extends DownloadBlock> downloads, int count) {
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putInt("expectedBytes", (int) download.getTotal());
    ee.emit("downloadBegin", params);
  }

  @Override
  public void onDownloadBlockUpdated(Download download, DownloadBlock block, int id) {

  }

  @Override
  public void onError(@NonNull Download download, @NonNull Error error, Throwable throwable) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    if (error == Error.UNKNOWN && throwable != null) {
      params.putString("error", throwable.getLocalizedMessage());
    } else {
      params.putString("error", error.toString());
    }
    ee.emit("downloadFailed", params);
    saveConfigMap();
    /*
     * removeFromMaps(download.getId()); fetch.remove(download.getId());
     */
  }

  @Override
  public void onWaitingNetwork(@NonNull Download download) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    ee.emit("waiting_for_network", params);
  }

  @Override
  public void onQueued(@NonNull Download download, boolean state) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    params.putBoolean("waitingForNetwork", state);
    ee.emit("downloadQueued", params);
  }

  @Override
  public void onAdded(@NonNull Download download) {
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putInt("expectedBytes", (int) download.getTotal());
    ee.emit("downloadAdded", params);
  }

  @Override
  public void onCompleted(@NonNull Download download) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    ee.emit("downloadComplete", params);

    removeFromMaps(download.getId());
    fetch.remove(download.getId());
  }

  @Override
  public void onProgress(@NonNull Download download, long l, long l1) {
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putInt("written", (int) download.getDownloaded());
    params.putInt("total", (int) download.getTotal());
    params.putDouble("percent", ((double) download.getProgress()) / 100);
    progressReports.put(config.id, params);
    Date now = new Date();
    if (now.getTime() - lastProgressReport.getTime() > 1500) {
      WritableArray reportsArray = Arguments.createArray();
      for (WritableMap report : progressReports.values()) {
        reportsArray.pushMap(report);
      }
      ee.emit("downloadProgress", reportsArray);
      lastProgressReport = now;
      progressReports.clear();
    }
  }

  @Override
  public void onPaused(@NonNull Download download) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    ee.emit("downloadPaused", params);
  }

  @Override
  public void onResumed(@NonNull Download download) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    ee.emit("downloadResumed", params);
  }

  @Override
  public void onCancelled(@NonNull Download download) {
    WritableMap params = Arguments.createMap();
    RNBGDTaskConfig config = requestIdToConfig.get(download.getId());
    if(config == null)
      return;
    params.putString("id", config.id);
    ee.emit("downloadCancelled", params);
    saveConfigMap();
    /*
     * removeFromMaps(download.getId()); fetch.delete(download.getId());
     */
  }

  @Override
  public void onRemoved(Download download) {

  }

  @Override
  public void onDeleted(Download download) {

  }

  @ReactMethod
  public BigInteger getFreeDiskStorage() {
    try {
      StatFs external = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
      long availableBlocks;
      long blockSize;

      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
        availableBlocks = external.getAvailableBlocks();
        blockSize = external.getBlockSize();
      } else {
        availableBlocks = external.getAvailableBlocksLong();
        blockSize = external.getBlockSizeLong();
      }

      return BigInteger.valueOf(availableBlocks).multiply(BigInteger.valueOf(blockSize));
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
}
